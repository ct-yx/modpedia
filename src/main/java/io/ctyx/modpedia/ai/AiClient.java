package io.ctyx.modpedia.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
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

/**
 * LangChain4j OpenAI 兼容客户端适配器。
 *
 * <p>HTTP、SSE、重试和模型协议由 LangChain4j 处理；本类只集中构造模型并提供设置页的
 * 异步连通性测试，避免在 Screen 和会话层重复拼装客户端。</p>
 */
public final class AiClient {
    private static final String GROK_TOOL_COMPATIBLE_MODEL = "grok-4.5-latest";
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

    public static OpenAiChatModel blockingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
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
                .parallelToolCalls(false)
                .build();
    }

    public static OpenAiStreamingChatModel streamingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
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
            try {
                if (!actual.configured()) {
                    resultConsumer.accept(new TestResult(false, "请先填写 API 地址和模型名称。"));
                    return;
                }
                String response = blockingModel(actual).chat(
                        "Reply with a short confirmation that the connection is working."
                );
                boolean empty = response == null || response.isBlank();
                resultConsumer.accept(new TestResult(
                        empty,
                        empty
                                ? "请求完成，但模型返回了空内容。"
                        : "连接成功。"
                ));
            } catch (Throwable throwable) {
                resultConsumer.accept(new TestResult(false, friendlyError(throwable)));
            }
        });
    }

    /**
     * 从 OpenAI 兼容接口的 {@code /models} 端点获取可用模型。
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
                resultConsumer.accept(new ModelListResult(true, List.of(), friendlyError(throwable)));
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
                        friendlyError(throwable)
                ));
            }
        });
    }

    private static ModelListResult fetchModelsBlocking(AiSettings settings) throws Exception {
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(normalizedEndpoint(settings.endpoint())) + "/models");
        } catch (IllegalArgumentException exception) {
            return new ModelListResult(true, List.of(), "API 地址格式无效，请填写完整的 http(s) 地址。");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Authorization", "Bearer " + settings.effectiveApiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return parseModelListResponse(response.statusCode(), response.body());
    }

    /**
     * OpenAI 兼容服务通常把 API 放在 {@code /v1} 下。用户填写域名根地址时自动补上该路径，
     * 同时保留已经填写的自定义路径和版本路径。
     */
    public static String normalizedEndpoint(String endpoint) {
        String value = stripTrailingSlash(endpoint == null ? "" : endpoint.strip());
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (uri.isAbsolute() && (path == null || path.isBlank() || "/".equals(path))) {
                return value + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
            // LangChain4j 会在真正构造模型时给出地址格式错误；这里不吞掉用户的原始输入。
        }
        return value;
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
    static boolean isRetryableFailure(Throwable throwable) {
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
                        || lower.contains("too many requests")
                        || lower.contains("temporarily unavailable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    static ModelListResult parseModelListResponse(int statusCode, String body) {
        String content = body == null ? "" : body.strip();
        if (statusCode == 401 || statusCode == 403
                || content.toLowerCase(Locale.ROOT).contains("invalid_api_key")) {
            return new ModelListResult(true, List.of(), "API Key 无效或未被当前接口接受。");
        }
        if (looksLikeHtml(content)) {
            return new ModelListResult(
                    true,
                    List.of(),
                    "API 地址返回了网页内容，请填写 OpenAI 兼容 API 根地址，通常以 /v1 结尾。"
            );
        }
        if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 404) {
                return new ModelListResult(true, List.of(), "未找到模型列表接口，请确认 API 地址通常包含 /v1。");
            }
            return new ModelListResult(true, List.of(), "获取模型列表失败（HTTP " + statusCode + "）。");
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("data")
                    || !parsed.getAsJsonObject().get("data").isJsonArray()) {
                return new ModelListResult(true, List.of(), "模型列表响应格式不受支持，应包含 data 数组。");
            }
            Map<String, ModelInfo> unique = new LinkedHashMap<>();
            for (JsonElement element : parsed.getAsJsonObject().getAsJsonArray("data")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String id = stringValue(element, "id");
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

    private static boolean looksLikeHtml(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.startsWith("<")
                || lower.startsWith("<!doctype")
                || lower.contains("<html")
                || lower.contains("<head");
    }

    static String friendlyError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().strip();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no healthy grok oauth account")) {
            return "当前模型的 Grok 上游没有可用账户；请在设置中选择 grok-4.5-latest，或等待上游恢复。";
        }
        if (lower.contains("httpclientbuilderfactory") && lower.contains("not a subtype")) {
            return "AI HTTP 客户端依赖加载失败，请重启游戏；如果仍然失败，请更新 ModPedia。";
        }
        if (looksLikeHtml(message) || message.contains("Unexpected character '<'")
                || message.contains("code 60")) {
            return "API 地址返回了网页内容，请填写 OpenAI 兼容 API 根地址，通常以 /v1 结尾。";
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("invalid_api_key")
                || lower.contains("invalid api key")) {
            return "API Key 无效或未被当前接口接受。";
        }
        if (message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)bearer\\s+[a-z0-9._~+/=-]+", "Bearer [已隐藏]");
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
        return actual;
    }

    public record TestResult(boolean failed, String message) {
        public TestResult {
            message = message == null ? "" : message;
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
