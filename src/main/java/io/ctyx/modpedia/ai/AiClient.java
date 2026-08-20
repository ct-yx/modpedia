package io.ctyx.modpedia.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * LangChain4j AI 客户端适配器。
 *
 * <p>HTTP、SSE、重试和模型协议由 LangChain4j 处理；本类只集中构造模型并提供设置页的
 * 异步连通性测试，避免在 Screen 和会话层重复拼装客户端。</p>
 */
public final class AiClient {
    private static final String GROK_TOOL_COMPATIBLE_MODEL = "grok-4.5-latest";
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");
    private static final ExecutorService TEST_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-ai-connection-test");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private AiClient() {
    }

    /**
     * LangChain4j 1.18.1 将通用 maxOutputTokens 映射为 max_tokens；GPT-5 和 o 系列
     * 的 Chat Completions 接口要求使用 max_completion_tokens。只在这些模型上切换，
     * 兼容仍接受旧字段的 GPT-4/第三方模型。
     */
    public static boolean usesCompletionTokenParameter(String model) {
        String normalized = model == null ? "" : model.strip().toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4");
    }

    public static OpenAiChatModel blockingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        if (!actual.apiFormat().isChatCompletions()) {
            throw new IllegalArgumentException("当前 API 格式请使用通用协议模型入口");
        }
        return blockingModel(actual, effectiveModelName(actual.model()));
    }

    static OpenAiChatModel blockingModel(AiSettings settings, String modelName) {
        AiSettings actual = requireConfigured(settings);
        return OpenAiChatModel.builder()
                .baseUrl(normalizedEndpoint(actual.endpoint()))
                .apiKey(actual.effectiveApiKey())
                .modelName(normalizedModelName(modelName, actual.model()))
                // 显式指定 JDK 实现，避免独立发布 JAR 的 Jar-in-Jar SPI 在首次请求时
                // 从错误的类加载器发现 HttpClientBuilderFactory。
                .httpClientBuilder(jdkHttpClientBuilder(actual))
                .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                // 业务层只负责一次明确的重试；关闭库内隐式重试，避免 503/超时
                // 叠加成多轮请求后再进入 WorkerChatService 的重试分支。
                .maxRetries(0)
                .parallelToolCalls(false)
                .build();
    }

    public static OpenAiStreamingChatModel streamingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        if (!actual.apiFormat().isChatCompletions()) {
            throw new IllegalArgumentException("当前 API 格式请使用通用协议模型入口");
        }
        return streamingModel(actual, effectiveModelName(actual.model()));
    }

    static OpenAiStreamingChatModel streamingModel(AiSettings settings, String modelName) {
        AiSettings actual = requireConfigured(settings);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(normalizedEndpoint(actual.endpoint()))
                .apiKey(actual.effectiveApiKey())
                .modelName(normalizedModelName(modelName, actual.model()))
                .httpClientBuilder(jdkHttpClientBuilder(actual))
                .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                .parallelToolCalls(false)
                .build();
    }

    /** 真实对话统一入口；Chat Completions 继续使用 LangChain4j 原生实现。 */
    public static ChatModel chatModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        return actual.apiFormat().isChatCompletions()
                ? blockingModel(actual, effectiveModelName(actual.model()))
                : new ProtocolAiModel(actual);
    }

    /** 真实流式对话统一入口；三种新增协议均使用原生 SSE 适配器。 */
    public static StreamingChatModel streamingChatModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        return actual.apiFormat().isChatCompletions()
                ? streamingModel(actual, effectiveModelName(actual.model()))
                : new ProtocolAiModel(actual);
    }

    private static JdkHttpClientBuilder jdkHttpClientBuilder(AiSettings settings) {
        Duration timeout = Duration.ofSeconds(settings.timeoutSeconds());
        return new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
    }

    public static void testConnection(AiSettings settings, Consumer<TestResult> callback) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        Consumer<TestResult> resultConsumer = callback == null ? ignored -> { } : callback;
        TEST_EXECUTOR.execute(() -> {
            resultConsumer.accept(testConnectionBlocking(actual));
        });
    }

    /**
     * 设置页的连接测试只验证“地址、认证和所选模型能否完成一次最小协议请求”。这里故意不经过
     * LangChain4j 的模型封装：不同网关/新模型可能拒绝
     * temperature、max_tokens 或其他可选字段，但这不代表 API Key 或地址错误。
     * 实际对话仍继续使用 LangChain4j，以便保留工具调用和 SSE 能力。
     */
    static TestResult testConnectionBlocking(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        if (!actual.configured()) {
            return new TestResult(true, "请先填写 API 地址和模型名称。", 0, 0);
        }
        if (actual.effectiveApiKey().isBlank()) {
            return new TestResult(true,
                    "请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。", 0, 0);
        }

        URI uri;
        try {
            uri = actual.apiFormat().isChatCompletions()
                    ? URI.create(stripTrailingSlash(normalizedEndpoint(actual.endpoint()))
                    + "/chat/completions")
                    : ProtocolAiModel.endpointFor(actual, false);
        } catch (IllegalArgumentException exception) {
            return new TestResult(true,
                    "API 地址格式无效，请填写完整的 http(s) 地址。", 0, 0);
        }

        JsonObject payload = actual.apiFormat().isChatCompletions()
                ? chatCompletionConnectionPayload(actual)
                : ProtocolAiModel.minimalPayload(actual, false);
        String requestBody = payload.toString();

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = actual.apiFormat().isChatCompletions()
                        ? HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                        .header("Authorization", "Bearer " + actual.effectiveApiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        : ProtocolAiModel.authenticatedBuilder(actual, uri, false);
                HttpRequest request = requestBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );
                int status = response.statusCode();
                String body = response.body() == null ? "" : response.body().strip();
                String retryAfter = retryAfter(response.headers());
                if (attempt == 1 && retryableStatus(status) && retryAfter.isBlank()) {
                    sleepQuietly(350L);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    TestResult result = new TestResult(
                            true,
                            friendlyHttpFailure(
                                    status,
                                    body,
                                    actual.effectiveApiKey(),
                                    retryAfter,
                                    actual.apiFormat()
                            ),
                            status,
                            attempt
                    );
                    logConnectionResult(actual, uri, result);
                    return result;
                }
                if (body.isBlank()) {
                    TestResult result = new TestResult(true,
                            "请求完成，但接口返回了空内容。", status, attempt);
                    logConnectionResult(actual, uri, result);
                    return result;
                }
                if (looksLikeHtml(body)) {
                    TestResult result = new TestResult(true,
                            "API 地址返回了网页内容，请填写当前 API 格式的根地址。",
                            status,
                            attempt
                    );
                    logConnectionResult(actual, uri, result);
                    return result;
                }
                TestResult result = new TestResult(false,
                        "连接成功（HTTP " + status + "）。", status, attempt);
                logConnectionResult(actual, uri, result);
                return result;
            } catch (Throwable throwable) {
                if (attempt == 1 && isRetryableFailure(throwable)) {
                    sleepQuietly(350L);
                    continue;
                }
                TestResult result = new TestResult(
                        true,
                        friendlyError(throwable, actual.effectiveApiKey()),
                        0,
                        attempt
                );
                logConnectionResult(actual, uri, result);
                return result;
            }
        }
        TestResult result = new TestResult(true, "连接测试失败，请稍后重试。", 0, 2);
        logConnectionResult(actual, uri, result);
        return result;
    }

    private static JsonObject chatCompletionConnectionPayload(AiSettings settings) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", effectiveModelName(settings.model()));
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Reply only with OK.");
        messages.add(user);
        payload.add("messages", messages);
        payload.addProperty("stream", false);
        return payload;
    }

    private static String friendlyHttpFailure(
            int statusCode,
            String body,
            String apiKey,
            String retryAfter,
            AiApiFormat apiFormat
    ) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (statusCode == 401 || statusCode == 403 || lower.contains("invalid_api_key")) {
            return "API Key 无效或未被当前接口接受（HTTP " + statusCode + "）。";
        }
        if (statusCode == 404) {
            return "未找到 " + protocolLabel(apiFormat) + " 接口（HTTP 404），请确认 API 地址和格式选择。";
        }
        if (statusCode == 400) {
            String detail = responseErrorMessage(body, apiKey);
            return detail.isBlank()
                    ? "请求参数被接口拒绝（HTTP 400），请确认模型名称和 API 格式。"
                    : "请求参数被接口拒绝（HTTP 400）：“" + detail + "”。";
        }
        if (retryableStatus(statusCode)) {
            String detail = responseErrorMessage(body, apiKey);
            String retryHint = retryAfter == null || retryAfter.isBlank()
                    ? "请稍后重试。"
                    : "上游要求等待约 " + retryAfter + " 秒后再试。";
            return "AI 上游暂时不可用（HTTP " + statusCode + "）"
                    + (detail.isBlank() ? "" : "：" + detail)
                    + "，" + retryHint;
        }
        return "连接测试失败（HTTP " + statusCode + "）。";
    }

    /**
     * 读取网关返回的 Retry-After 秒数。连接测试不能在服务端明确要求等待 30 秒时
     * 只休眠 350 ms 再重复发送一次，否则用户会看到两次相同的 503，诊断信息也会
     * 变得不准确。无该头时继续保留一次短重试，兼容本地夹具和未提供退避信息的网关。
     */
    private static String retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return "";
        }
        return headers.firstValue("Retry-After")
                .map(String::strip)
                .filter(value -> value.matches("\\d+"))
                .orElse("");
    }

    private static String responseErrorMessage(String body, String apiKey) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (parsed.isJsonObject()) {
                JsonElement error = parsed.getAsJsonObject().get("error");
                if (error != null && error.isJsonObject()) {
                    JsonElement message = error.getAsJsonObject().get("message");
                    if (message != null && message.isJsonPrimitive()) {
                        return redact(message.getAsString().strip(), apiKey);
                    }
                }
                JsonElement message = parsed.getAsJsonObject().get("message");
                if (message != null && message.isJsonPrimitive()) {
                    return redact(message.getAsString().strip(), apiKey);
                }
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体不影响状态码诊断。
        }
        return "";
    }

    private static void logConnectionResult(AiSettings settings, URI uri, TestResult result) {
        String endpoint = safeEndpointForLog(uri);
        String model = effectiveModelName(settings.model());
        LOG.info(() -> "AI_CONNECTION_TEST endpoint=" + endpoint
                + " model=" + model
                + " status=" + result.statusCode()
                + " attempts=" + result.attempts()
                + " failed=" + result.failed());
    }

    static String safeEndpointForLog(URI uri) {
        if (uri == null) {
            return "";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme() + "://";
        String host = uri.getHost() == null ? "" : uri.getHost();
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String path = uri.getPath() == null ? "" : uri.getPath();
        return scheme + host + port + path;
    }

    /**
     * 从当前协议的 {@code /models} 端点获取可用模型。
     *
     * <p>模型列表请求不依赖当前模型名称，因此设置页可以在模型输入框为空时使用。请求在
     * 后台线程执行，回调不会在 Minecraft 主线程上运行；客户端调用方负责切回主线程。</p>
     */
    public static void fetchModels(AiSettings settings, Consumer<ModelListResult> callback) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        Consumer<ModelListResult> resultConsumer = callback == null ? ignored -> { } : callback;
        TEST_EXECUTOR.execute(() -> {
            try {
                if (actual.endpoint().isBlank()) {
                    resultConsumer.accept(new ModelListResult(true, List.of(), "请先填写 API 地址。"));
                    return;
                }
                if (actual.effectiveApiKey().isBlank()) {
                    resultConsumer.accept(new ModelListResult(
                            true,
                            List.of(),
                            "请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。"
                    ));
                    return;
                }
                resultConsumer.accept(fetchModelsBlocking(actual));
            } catch (Throwable throwable) {
                resultConsumer.accept(new ModelListResult(
                        true,
                        List.of(),
                        friendlyError(throwable, actual.effectiveApiKey())
                ));
            }
        });
    }

    /**
     * 在后台一次性探测当前接口返回的全部模型，并将脱敏报告写入指定目录。
     * 设置页和开发任务共用同一套探测器，玩家不需要逐个模型手动发送问题。
     */
    public static void testAllModels(
            AiSettings settings,
            Path reportDirectory,
            int parallelism,
            Consumer<ModelCompatibilityResult> callback
    ) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        Consumer<ModelCompatibilityResult> resultConsumer = callback == null ? ignored -> { } : callback;
        TEST_EXECUTOR.execute(() -> {
            if (!actual.apiFormat().isChatCompletions()) {
                resultConsumer.accept(new ModelCompatibilityResult(
                        true,
                        null,
                        "",
                        "批量模型诊断当前只支持 Chat Completions 格式；其他格式请使用连接测试和真实对话。"
                ));
                return;
            }
            if (actual.endpoint().isBlank() || actual.effectiveApiKey().isBlank()) {
                resultConsumer.accept(new ModelCompatibilityResult(
                        true,
                        null,
                        "",
                        "请先填写 API 地址和 API Key，或设置 MODPEDIA_API_KEY 环境变量。"
                ));
                return;
            }
            try {
                AiModelCompatibilityTester tester = new AiModelCompatibilityTester(actual, parallelism);
                AiModelCompatibilityTester.CompatibilityReport report = tester.runAll(null);
                Path directory = reportDirectory == null ? Path.of("build/reports/modpedia") : reportDirectory;
                tester.writeReport(report, directory);
                resultConsumer.accept(new ModelCompatibilityResult(
                        false,
                        report,
                        directory.toAbsolutePath().normalize().toString(),
                        "批量模型兼容性测试完成。"
                ));
            } catch (Throwable throwable) {
                resultConsumer.accept(new ModelCompatibilityResult(
                        true,
                        null,
                        "",
                        friendlyError(throwable, actual.effectiveApiKey())
                ));
            }
        });
    }

    private static ModelListResult fetchModelsBlocking(AiSettings settings) throws Exception {
        URI uri;
        try {
            uri = ProtocolAiModel.modelsEndpointFor(settings);
        } catch (IllegalArgumentException exception) {
            return new ModelListResult(true, List.of(), "API 地址格式无效，请填写完整的 http(s) 地址。");
        }

        HttpRequest request = ProtocolAiModel.authenticatedBuilder(settings, uri, false)
                .GET()
                .build();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (attempt == 0 && retryableStatus(response.statusCode())) {
                    sleepQuietly(350L);
                    continue;
                }
                return parseModelListResponse(response.statusCode(), response.body(), settings.apiFormat());
            } catch (Exception exception) {
                if (attempt == 0 && isRetryableFailure(exception)) {
                    sleepQuietly(350L);
                    continue;
                }
                throw exception;
            }
        }
        return new ModelListResult(true, List.of(), "获取模型列表失败，请稍后重试。");
    }

    /**
     * OpenAI 兼容服务通常把 API 放在 {@code /v1} 下。用户填写域名根地址时自动补上该路径，
     * 同时保留已经填写的自定义路径和版本路径。
     */
    public static String normalizedEndpoint(String endpoint) {
        return AiSettings.normalizeEndpoint(endpoint);
    }

    public static String normalizedEndpoint(String endpoint, AiApiFormat format) {
        return AiSettings.normalizeEndpoint(endpoint, format);
    }

    /**
     * 返回已知的工具调用兼容模型。某些兼容网关把 {@code grok-4.5} 和带 {@code -latest}
     * 后缀的路由分到不同的 OAuth 账户池；前者可能普通对话可用，但携带 tools 时返回 503。
     */
    static String fallbackModelName(String modelName) {
        String value = modelName == null ? "" : modelName.strip();
        return "grok-4.5".equalsIgnoreCase(value) ? GROK_TOOL_COMPATIBLE_MODEL : "";
    }

    static String effectiveModelName(String modelName) {
        String value = modelName == null ? "" : modelName.strip();
        String fallback = fallbackModelName(value);
        return fallback.isBlank() ? value : fallback;
    }

    static boolean isGrokOAuthUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains("no healthy grok oauth account")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断一次失败是否值得由会话层自动重试。
     *
     * <p>400/401 等配置错误不重试；网关的 5xx、限流、网络超时以及上下文中残留的
     * 未完成工具调用允许重试一次。这样用户不需要手动连续点击“重试”，同时不会把
     * 明确的模型不支持错误变成无意义的重复请求。</p>
     */
    public static boolean isRetryableFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof ConnectException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("no tool output found")
                        || lower.contains("connection reset")
                        || lower.contains("connection refused")
                        || lower.contains("timed out")
                        || lower.matches(".*(?:http|status|code)[ =:]?(408|425|429|500|502|503|504)\\b.*")
                        || lower.matches(".*\\b(408|425|429|500|502|503|504)\\b.*")
                        || lower.contains("too many requests")
                        || lower.contains("temporarily unavailable")
                        || lower.contains("temporary unavailable")
                        || lower.contains("service unavailable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断是否可以在没有等待头信息的情况下立即进行一次短退避重试。
     *
     * <p>503/429 通常表示上游路由或限流状态，而不是本地请求瞬态错误。LangChain4j
     * 的模型客户端不会把所有网关响应头暴露给业务层，因此对这两类 HTTP 错误采取保守
     * 策略：不在几百毫秒后重复打上游，让用户按错误提示稍后重试。</p>
     */
    public static boolean shouldRetryImmediately(Throwable throwable) {
        int status = httpStatusCode(throwable);
        if (status == 429 || status == 503 || retryAfterSeconds(throwable) > 0) {
            return false;
        }
        if (status != 0) {
            return status == 408 || status == 425 || status == 500
                    || status == 502 || status == 504;
        }
        return isRetryableFailure(throwable);
    }

    /** 返回一次短退避重试使用的毫秒数，不包含服务端要求的长等待。 */
    public static long retryDelayMillis(Throwable throwable) {
        if (!shouldRetryImmediately(throwable)) {
            return 0L;
        }
        int status = httpStatusCode(throwable);
        return status == 0 ? 350L : 750L;
    }

    /** 从 LangChain4j 的 HTTP 异常或异常文本中提取状态码。 */
    public static int httpStatusCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpException httpException) {
                return httpException.statusCode();
            }
            String message = current.getMessage();
            if (message != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\\b(?:HTTP|status|code)[ =:]*(\\d{3})\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(message);
                if (matcher.find()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        // 继续检查下一个 cause。
                    }
                }
            }
            current = current.getCause();
        }
        return 0;
    }

    /** 从异常文本中读取网关可能附带的 Retry-After 秒数。 */
    public static int retryAfterSeconds(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)retry[-_ ]after\\s*[:=]\\s*(\\d+)|\\\"retry_after\\\"\\s*:\\s*(\\d+)")
                        .matcher(message);
                if (matcher.find()) {
                    String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
            current = current.getCause();
        }
        return 0;
    }

    static ModelListResult parseModelListResponse(int statusCode, String body) {
        return parseModelListResponse(statusCode, body, AiApiFormat.CHAT_COMPLETIONS);
    }

    static ModelListResult parseModelListResponse(
            int statusCode,
            String body,
            AiApiFormat apiFormat
    ) {
        String content = body == null ? "" : body.strip();
        if (statusCode == 401 || statusCode == 403
                || content.toLowerCase(Locale.ROOT).contains("invalid_api_key")) {
            return new ModelListResult(true, List.of(), "API Key 无效或未被当前接口接受。");
        }
        if (looksLikeHtml(content)) {
            return new ModelListResult(
                    true,
                    List.of(),
                    "API 地址返回了网页内容，请填写当前 API 格式的根地址。"
            );
        }
        if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 404) {
                return new ModelListResult(
                        true,
                        List.of(),
                        "未找到 " + protocolLabel(apiFormat) + " 的模型列表接口；请直接填写模型名称。"
                );
            }
            return new ModelListResult(true, List.of(), "获取模型列表失败（HTTP " + statusCode + "）。");
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                return new ModelListResult(true, List.of(), "模型列表响应不是有效对象。");
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonArray modelsArray = object.has("data") && object.get("data").isJsonArray()
                    ? object.getAsJsonArray("data")
                    : object.has("models") && object.get("models").isJsonArray()
                    ? object.getAsJsonArray("models") : null;
            if (modelsArray == null) {
                return new ModelListResult(true, List.of(), "模型列表响应格式不受支持，应包含 data 或 models 数组。");
            }
            Map<String, ModelInfo> unique = new LinkedHashMap<>();
            for (JsonElement element : modelsArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String id = stringValue(element, "id");
                if (id.isBlank()) {
                    String fullName = stringValue(element, "name");
                    id = fullName.startsWith("models/") ? fullName.substring("models/".length()) : fullName;
                }
                if (id.isBlank()) {
                    continue;
                }
                String ownedBy = stringValue(element, "owned_by");
                if (ownedBy.isBlank()) {
                    ownedBy = stringValue(element, "ownedBy");
                }
                unique.putIfAbsent(id, new ModelInfo(id, ownedBy));
            }
            List<ModelInfo> models = new ArrayList<>(unique.values());
            models.sort(Comparator.comparing(ModelInfo::id, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ModelInfo::id));
            return new ModelListResult(false, models,
                    models.isEmpty() ? "接口返回了 0 个可用模型。" : "已获取 " + models.size() + " 个模型。");
        } catch (RuntimeException exception) {
            return new ModelListResult(true, List.of(), "模型列表响应不是有效 JSON。");
        }
    }

    private static String stringValue(JsonElement element, String name) {
        if (!element.isJsonObject() || !element.getAsJsonObject().has(name)
                || element.getAsJsonObject().get(name).isJsonNull()) {
            return "";
        }
        try {
            return element.getAsJsonObject().get(name).getAsString().strip();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String protocolLabel(AiApiFormat apiFormat) {
        return switch (apiFormat == null ? AiApiFormat.CHAT_COMPLETIONS : apiFormat) {
            case CHAT_COMPLETIONS -> "Chat Completions";
            case NATIVE_MESSAGES -> "Native Messages";
            case RESPONSES -> "Responses";
            case GENERATE_CONTENT -> "Gemini generateContent";
        };
    }

    private static boolean looksLikeHtml(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.startsWith("<")
                || lower.startsWith("<!doctype")
                || lower.contains("<html")
                || lower.contains("<head");
    }

    public static String friendlyError(Throwable throwable) {
        return friendlyError(throwable, "");
    }

    public static String friendlyError(Throwable throwable, String apiKey) {
        Throwable cause = deepestCause(throwable);
        String message = allMessages(throwable, apiKey);
        String detail = responseErrorMessageFromThrowable(throwable, apiKey);
        String lower = message.toLowerCase(Locale.ROOT);
        int status = httpStatusCode(throwable);
        int retryAfter = retryAfterSeconds(throwable);
        if (lower.contains("no healthy grok oauth account")) {
            return "当前模型的 Grok 上游没有可用账户；请在设置中选择 grok-4.5-latest，或等待上游恢复。";
        }
        if (lower.contains("httpclientbuilderfactory") && lower.contains("not a subtype")) {
            return "AI HTTP 客户端依赖加载失败，请重启游戏；如果仍然失败，请更新 ModPedia。";
        }
        if (status == 503 || lower.matches(".*\\b503\\b.*") || lower.contains("service unavailable")) {
            return temporaryUpstreamError(503, detail, retryAfter);
        }
        if (status == 429 || lower.matches(".*\\b429\\b.*") || lower.contains("too many requests")) {
            return temporaryUpstreamError(429, detail, retryAfter);
        }
        if (status == 408 || status == 425 || status == 500 || status == 502 || status == 504
                || lower.matches(".*\\b(408|425|500|502|504)\\b.*")) {
            String reportedStatus = status == 0 ? firstHttpStatus(lower) : "HTTP " + status;
            return "AI 网关暂时失败（" + reportedStatus + "）"
                    + (detail.isBlank() ? "" : "：" + detail)
                    + "，请稍后重试。";
        }
        if (lower.contains("tool_calls") || lower.contains("tool call")
                || lower.contains("function call") || lower.contains("tool_choice")) {
            return "当前模型或 API 网关拒绝了工具调用格式；请确认所选 API 格式、模型和工具调用能力匹配。";
        }
        if (looksLikeHtml(message) || message.contains("Unexpected character '<'")
                || message.contains("code 60")) {
            return "API 地址返回了网页内容，请填写所选协议的 API 根地址。";
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("invalid_api_key")
                || lower.contains("invalid api key")) {
            return "API Key 无效或未被当前接口接受。";
        }
        if (!detail.isBlank()) {
            return "AI 请求失败：" + detail + "。";
        }
        if (message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // 不把上游完整 JSON、请求头或异常链直接交给界面；只保留一行脱敏文本。
        String compact = message.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("(?i)bearer\\s+[a-z0-9._~+/=-]+", "Bearer [已隐藏]");
        if (compact.length() > 240) {
            compact = compact.substring(0, 240) + "…";
        }
        return redact(compact, apiKey);
    }

    private static String temporaryUpstreamError(int status, String detail, int retryAfter) {
        String wait = retryAfter > 0
                ? "上游要求等待约 " + retryAfter + " 秒后再试。"
                : "请等待上游恢复后再试。";
        return "AI 上游暂时不可用（HTTP " + status + "）"
                + (detail == null || detail.isBlank() ? "" : "：" + detail)
                + "。" + wait;
    }

    private static Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        if (current == null) {
            return new IllegalStateException("未知错误");
        }
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        return current;
    }

    private static String allMessages(Throwable throwable, String apiKey) {
        StringBuilder result = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(redact(current.getMessage(), apiKey));
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static String responseErrorMessageFromThrowable(Throwable throwable, String apiKey) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String candidate = message.strip();
                int start = candidate.indexOf('{');
                int end = candidate.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String detail = responseErrorMessage(
                            candidate.substring(start, end + 1), apiKey
                    );
                    if (!detail.isBlank()) {
                        return detail;
                    }
                }
            }
            current = current.getCause();
        }
        return "";
    }

    private static String firstHttpStatus(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(408|425|500|502|504)\\b")
                .matcher(message == null ? "" : message);
        return matcher.find() ? "HTTP " + matcher.group(1) : "HTTP 5xx";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean retryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static String redact(String value, String apiKey) {
        String message = value == null ? "" : value;
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "[已隐藏密钥]");
        }
        return message;
    }

    private static String normalizedModelName(String requested, String fallback) {
        String value = requested == null ? "" : requested.strip();
        return value.isBlank() ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static AiSettings requireConfigured(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        if (!actual.configured()) {
            throw new IllegalArgumentException("AI API 地址或模型名称为空");
        }
        if (actual.effectiveApiKey().isBlank()) {
            throw new IllegalArgumentException("AI API Key 为空");
        }
        return actual;
    }

    public record TestResult(boolean failed, String message, int statusCode, int attempts) {
        public TestResult(boolean failed, String message) {
            this(failed, message, 0, 0);
        }

        public TestResult {
            message = message == null ? "" : message;
            statusCode = Math.max(0, statusCode);
            attempts = Math.max(0, attempts);
        }
    }

    public record ModelInfo(String id, String ownedBy) {
        public ModelInfo {
            id = id == null ? "" : id.strip();
            ownedBy = ownedBy == null ? "" : ownedBy.strip();
        }
    }

    public record ModelListResult(boolean failed, List<ModelInfo> models, String message) {
        public ModelListResult {
            models = models == null ? List.of() : List.copyOf(models);
            message = message == null ? "" : message;
        }
    }

    public record ModelCompatibilityResult(
            boolean failed,
            AiModelCompatibilityTester.CompatibilityReport report,
            String reportPath,
            String message
    ) {
        public ModelCompatibilityResult {
            reportPath = reportPath == null ? "" : reportPath;
            message = message == null ? "" : message;
        }
    }
}
