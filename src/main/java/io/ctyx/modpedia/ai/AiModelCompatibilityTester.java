package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 对 OpenAI Chat Completions 兼容接口执行批量模型探测。
 *
 * <p>这是开发诊断器，不参与游戏中的回答链路。它使用最小请求验证四个边界：普通请求、
 * 非流式工具调用续接、普通 SSE 和流式工具调用续接。这样模型能力差异会在报告中一次性
 * 显示，而不是让玩家逐个模型手测。</p>
 */
public final class AiModelCompatibilityTester {
    private static final Gson JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final int MAX_ERROR_CHARS = 240;
    private static final int DEFAULT_PARALLELISM = 2;
    private static final int MAX_PARALLELISM = 4;
    private static final int MAX_RETRIES = 1;
    private static final String TOOL_NAME = "search_knowledge";

    private final AiSettings settings;
    private final HttpClient client;
    private final URI chatUri;
    private final int requestTimeoutSeconds;
    private final int parallelism;

    public AiModelCompatibilityTester(AiSettings settings) {
        this(settings, Integer.getInteger("modpedia.aiProbeParallelism", DEFAULT_PARALLELISM));
    }

    public AiModelCompatibilityTester(AiSettings settings, int parallelism) {
        this.settings = settings == null ? AiSettings.defaults() : settings;
        this.requestTimeoutSeconds = Math.max(10, Math.min(45, this.settings.timeoutSeconds()));
        this.parallelism = Math.max(1, Math.min(MAX_PARALLELISM, parallelism));
        try {
            String endpoint = AiClient.normalizedEndpoint(this.settings.endpoint());
            this.chatUri = URI.create(stripTrailingSlash(endpoint) + "/chat/completions");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API 地址格式无效", exception);
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
    }

    /** 批量探测指定模型；列表通常直接来自 {@code /models}。 */
    public CompatibilityReport run(List<AiClient.ModelInfo> models, Consumer<Progress> progress) {
        List<AiClient.ModelInfo> selected = models == null
                ? List.of()
                : models.stream()
                .filter(model -> model != null && !model.id().isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                model -> model.id().toLowerCase(Locale.ROOT),
                                model -> model,
                                (first, ignored) -> first,
                                LinkedHashMap::new
                        ),
                        map -> new ArrayList<>(map.values())
                ));
        selected.sort(Comparator.comparing(AiClient.ModelInfo::id, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AiClient.ModelInfo::id));

        long started = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, Math.max(1, selected.size())),
                runnable -> {
                    Thread thread = new Thread(runnable, "modpedia-ai-model-probe");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        try {
            List<Future<ModelReport>> futures = new ArrayList<>();
            for (AiClient.ModelInfo model : selected) {
                futures.add(executor.submit((Callable<ModelReport>) () -> probe(model)));
            }
            List<ModelReport> reports = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                ModelReport report;
                try {
                    report = futures.get(index).get();
                } catch (Exception exception) {
                    report = failedModel(selected.get(index).id(), exception);
                }
                reports.add(report);
                if (progress != null) {
                    progress.accept(new Progress(index + 1, selected.size(), report));
                }
            }
            long duration = System.currentTimeMillis() - started;
            return new CompatibilityReport(
                    safeEndpoint(settings.endpoint()),
                    Instant.ofEpochMilli(started).toString(),
                    duration,
                    reports.size(),
                    (int) reports.stream().filter(ModelReport::usable).count(),
                    (int) reports.stream().filter(report -> !report.usable()).count(),
                    reports
            );
        } finally {
            executor.shutdownNow();
        }
    }

    /** 从当前配置读取模型列表并执行批量探测。 */
    public CompatibilityReport runAll(Consumer<Progress> progress) throws IOException {
        AiClient.ModelListResult modelResult = fetchModels();
        if (modelResult.failed()) {
            throw new IOException(modelResult.message());
        }
        return run(modelResult.models(), progress);
    }

    public void writeReport(CompatibilityReport report, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path json = directory.resolve("ai-model-compatibility.json");
        Path markdown = directory.resolve("ai-model-compatibility.md");
        writeAtomically(json, JSON.toJson(report));
        writeAtomically(markdown, toMarkdown(report));
    }

    private ModelReport probe(AiClient.ModelInfo model) {
        Capability plain = probePlain(model.id());
        Capability tool = probeTool(model.id());
        Capability streaming = probeStreaming(model.id());
        Capability streamingTool = probeStreamingTool(model.id());
        return new ModelReport(model.id(), model.ownedBy(), plain, tool, streaming, streamingTool);
    }

    private Capability probePlain(String model) {
        long started = System.nanoTime();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", messages(
                message("system", "Reply with one short confirmation."),
                message("user", "Reply only with OK.")
        ));
        payload.addProperty("stream", false);
        HttpResult result = request(payload, false);
        if (!result.success()) {
            return result.toCapability(started, "plain");
        }
        JsonObject response = parseObject(result.body());
        if (response == null || !response.has("choices")) {
            return capability(Status.FAIL, started, "plain 响应不是 Chat Completions JSON");
        }
        return capability(Status.PASS, started, "普通请求成功");
    }

    private Capability probeTool(String model) {
        long started = System.nanoTime();
        HttpResult first = request(toolPayload(model, false), false);
        if (!first.success()) {
            return first.toCapability(started, "tool");
        }
        ToolCall call = firstToolCall(parseObject(first.body()));
        if (call == null) {
            return capability(Status.UNSUPPORTED, started, "模型返回普通文本，未发起工具调用");
        }
        HttpResult continuation = request(continuationPayload(model, call, false), false);
        if (!continuation.success()) {
            return continuation.toCapability(started, "tool continuation");
        }
        return capability(Status.PASS, started, "工具调用和 tool 输出续接成功");
    }

    private Capability probeStreaming(String model) {
        long started = System.nanoTime();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", messages(
                message("system", "Reply with one short confirmation."),
                message("user", "Reply only with OK.")
        ));
        payload.addProperty("stream", true);
        HttpResult result = request(payload, true);
        if (!result.success()) {
            return result.toCapability(started, "stream");
        }
        if (!result.body().contains("[DONE]")) {
            return capability(Status.FAIL, started, "流式响应没有 [DONE]");
        }
        return capability(Status.PASS, started, "普通 SSE 成功");
    }

    private Capability probeStreamingTool(String model) {
        long started = System.nanoTime();
        HttpResult first = request(toolPayload(model, true), true);
        if (!first.success()) {
            return first.toCapability(started, "stream tool");
        }
        ToolCall call = firstStreamingToolCall(first.body());
        if (call == null) {
            return capability(Status.UNSUPPORTED, started, "流式响应未发起工具调用");
        }
        HttpResult continuation = request(continuationPayload(model, call, true), true);
        if (!continuation.success()) {
            return continuation.toCapability(started, "stream tool continuation");
        }
        if (!continuation.body().contains("[DONE]")) {
            return capability(Status.FAIL, started, "工具续接流式响应没有 [DONE]");
        }
        return capability(Status.PASS, started, "流式工具调用和续接成功");
    }

    private HttpResult request(JsonObject payload, boolean streaming) {
        String body = JSON.toJson(payload);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            long started = System.nanoTime();
            try {
                HttpRequest request = HttpRequest.newBuilder(chatUri)
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .header("Authorization", "Bearer " + settings.effectiveApiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", streaming ? "text/event-stream" : "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
                if (!success && attempt < MAX_RETRIES && retryable(response.statusCode())) {
                    sleep(350L * (attempt + 1));
                    continue;
                }
                return new HttpResult(response.statusCode(), response.body(),
                        System.nanoTime() - started, success);
            } catch (Exception exception) {
                if (attempt < MAX_RETRIES) {
                    sleep(350L * (attempt + 1));
                    continue;
                }
                return new HttpResult(0, exception.getClass().getSimpleName() + ": "
                        + exception.getMessage(), System.nanoTime() - started, false);
            }
        }
        return new HttpResult(0, "请求未执行", 0, false);
    }

    private AiClient.ModelListResult fetchModels() {
        try {
            URI uri = URI.create(stripTrailingSlash(AiClient.normalizedEndpoint(settings.endpoint())) + "/models");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Authorization", "Bearer " + settings.effectiveApiKey())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return AiClient.parseModelListResponse(response.statusCode(), response.body());
        } catch (Exception exception) {
            return new AiClient.ModelListResult(true, List.of(), safeError(exception.getMessage()));
        }
    }

    private JsonObject toolPayload(String model, boolean streaming) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", messages(
                message("system", "Use the tool exactly once, then answer briefly."),
                message("user", "查询压力管道。")
        ));
        payload.add("tools", tools());
        payload.addProperty("tool_choice", "auto");
        payload.addProperty("parallel_tool_calls", false);
        payload.addProperty("stream", streaming);
        return payload;
    }

    private JsonObject continuationPayload(String model, ToolCall call, boolean streaming) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        JsonArray calls = new JsonArray();
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", call.id());
        toolCall.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", call.name().isBlank() ? TOOL_NAME : call.name());
        function.addProperty("arguments", call.arguments().isBlank() ? "{}" : call.arguments());
        toolCall.add("function", function);
        calls.add(toolCall);
        assistant.add("tool_calls", calls);
        JsonObject tool = message("tool", "{\"status\":\"READY\",\"results\":[]}");
        tool.addProperty("tool_call_id", call.id());
        payload.add("messages", messages(
                message("system", "Use the tool result to answer briefly."),
                message("user", "查询压力管道。"),
                assistant,
                tool
        ));
        payload.add("tools", tools());
        payload.addProperty("parallel_tool_calls", false);
        payload.addProperty("stream", streaming);
        return payload;
    }

    private static JsonArray tools() {
        JsonObject function = new JsonObject();
        function.addProperty("name", TOOL_NAME);
        function.addProperty("description", "搜索本地知识库");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("query", property("string"));
        properties.add("language", property("string"));
        properties.add("limit", property("integer"));
        properties.add("focus", property("string"));
        parameters.add("properties", properties);
        parameters.add("required", arrayOf("query"));
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private static JsonObject property(String type) {
        JsonObject value = new JsonObject();
        value.addProperty("type", type);
        return value;
    }

    private static JsonArray arrayOf(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    private static JsonArray messages(JsonObject... messages) {
        JsonArray array = new JsonArray();
        for (JsonObject message : messages) {
            array.add(message);
        }
        return array;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static ToolCall firstToolCall(JsonObject response) {
        if (response == null || !response.has("choices") || !response.get("choices").isJsonArray()
                || response.getAsJsonArray("choices").isEmpty()) {
            return null;
        }
        JsonObject choice = response.getAsJsonArray("choices").get(0).isJsonObject()
                ? response.getAsJsonArray("choices").get(0).getAsJsonObject()
                : null;
        if (choice == null || !choice.has("message") || !choice.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = choice.getAsJsonObject("message");
        if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()
                || message.getAsJsonArray("tool_calls").isEmpty()) {
            return null;
        }
        return parseToolCall(message.getAsJsonArray("tool_calls").get(0));
    }

    private static ToolCall firstStreamingToolCall(String body) {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).strip();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonObject event = parseObject(data);
            if (event == null || !event.has("choices") || !event.get("choices").isJsonArray()
                    || event.getAsJsonArray("choices").isEmpty()) {
                continue;
            }
            JsonObject choice = event.getAsJsonArray("choices").get(0).isJsonObject()
                    ? event.getAsJsonArray("choices").get(0).getAsJsonObject()
                    : null;
            if (choice == null || !choice.has("delta") || !choice.get("delta").isJsonObject()) {
                continue;
            }
            JsonObject delta = choice.getAsJsonObject("delta");
            if (!delta.has("tool_calls") || !delta.get("tool_calls").isJsonArray()) {
                continue;
            }
            for (JsonElement element : delta.getAsJsonArray("tool_calls")) {
                if (element.isJsonObject()) {
                    accumulator.accept(element.getAsJsonObject());
                }
            }
        }
        return accumulator.call();
    }

    private static ToolCall parseToolCall(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String id = stringValue(object, "id");
        JsonObject function = object.has("function") && object.get("function").isJsonObject()
                ? object.getAsJsonObject("function")
                : object;
        String name = stringValue(function, "name");
        String arguments = stringValue(function, "arguments");
        return id.isBlank() ? null : new ToolCall(id, name, arguments);
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement value = JsonParser.parseString(body == null ? "" : body.strip());
            return value.isJsonObject() ? value.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Capability capability(Status status, long started, String message) {
        return new Capability(status, elapsedMillis(started), safeError(message));
    }

    private static ModelReport failedModel(String model, Exception exception) {
        Capability failure = new Capability(Status.FAIL, 0, safeError(exception.getMessage()));
        return new ModelReport(model, "", failure, failure, failure, failure);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint.strip());
            String host = uri.getHost() == null ? "" : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return host + path;
        } catch (RuntimeException ignored) {
            return "未解析地址";
        }
    }

    private static String safeError(String message) {
        String value = message == null ? "未知错误" : message.strip();
        value = value.replaceAll("(?i)bearer\\s+[a-z0-9._~+/=-]+", "Bearer [已隐藏]");
        return value.length() <= MAX_ERROR_CHARS
                ? value
                : value.substring(0, MAX_ERROR_CHARS) + "…";
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String toMarkdown(CompatibilityReport report) {
        StringBuilder output = new StringBuilder();
        output.append("# ModPedia AI 模型兼容性报告\n\n")
                .append("- 接口：").append(report.endpoint()).append('\n')
                .append("- 开始时间：").append(report.startedAt()).append('\n')
                .append("- 总模型：").append(report.totalModels())
                .append("；可用：").append(report.usableModels())
                .append("；需处理：").append(report.failedModels()).append('\n')
                .append("- 流式工具链可用：").append(report.models().stream()
                        .filter(ModelReport::streamingUsable).count()).append('\n')
                .append("- 总耗时：").append(report.durationMs()).append(" ms\n\n")
                .append("| 模型 | 普通 | 工具续接 | SSE | 流式工具续接 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (ModelReport model : report.models()) {
            output.append('|').append(model.model()).append('|')
                    .append(cell(model.plain())).append('|')
                    .append(cell(model.tool())).append('|')
                    .append(cell(model.streaming())).append('|')
                    .append(cell(model.streamingTool())).append("|\n");
        }
        output.append("\n状态：PASS=通过，UNSUPPORTED=模型不支持该能力，FAIL=接口或链路失败。\n");
        return output.toString();
    }

    private static String cell(Capability capability) {
        return capability.status() + " (" + capability.latencyMs() + " ms)";
    }

    public record Progress(int completed, int total, ModelReport model) {
    }

    public enum Status {
        PASS,
        UNSUPPORTED,
        FAIL
    }

    public record Capability(Status status, long latencyMs, String message) {
        public Capability {
            status = status == null ? Status.FAIL : status;
            message = message == null ? "" : message;
        }
    }

    public record ModelReport(
            String model,
            String ownedBy,
            Capability plain,
            Capability tool,
            Capability streaming,
            Capability streamingTool
    ) {
        public ModelReport {
            model = model == null ? "" : model;
            ownedBy = ownedBy == null ? "" : ownedBy;
        }

        public boolean usable() {
            return plain.status() == Status.PASS && tool.status() == Status.PASS;
        }

        /** 当前设置启用 SSE 时，模型还必须能在流式响应中完成工具调用续接。 */
        public boolean streamingUsable() {
            return usable()
                    && streaming.status() == Status.PASS
                    && streamingTool.status() == Status.PASS;
        }
    }

    public record CompatibilityReport(
            String endpoint,
            String startedAt,
            long durationMs,
            int totalModels,
            int usableModels,
            int failedModels,
            List<ModelReport> models
    ) {
        public CompatibilityReport {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    private record ToolCall(String id, String name, String arguments) {
    }

    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        void accept(JsonObject delta) {
            if (id.isBlank()) {
                id = stringValue(delta, "id");
            }
            JsonObject function = delta.has("function") && delta.get("function").isJsonObject()
                    ? delta.getAsJsonObject("function")
                    : delta;
            String nextName = stringValue(function, "name");
            if (!nextName.isBlank()) {
                name = name + nextName;
            }
            String nextArguments = stringValue(function, "arguments");
            if (!nextArguments.isBlank()) {
                arguments.append(nextArguments);
            }
        }

        ToolCall call() {
            return id.isBlank() ? null : new ToolCall(id, name, arguments.toString());
        }
    }

    private record HttpResult(int status, String body, long latencyNanos, boolean success) {
        Capability toCapability(long started, String operation) {
            Status status = this.status == 400 || this.status == 404 || this.status == 405 || this.status == 422
                    ? Status.UNSUPPORTED
                    : Status.FAIL;
            String message = this.status == 0
                    ? safeError(body)
                    : operation + " HTTP " + this.status + "：" + safeError(body);
            return new Capability(status, elapsedMillis(started), message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path settingsPath = Path.of(System.getProperty(
                "modpedia.aiSettings",
                "run/config/modpedia/ai.json"
        ));
        Path reportDirectory = Path.of(System.getProperty(
                "modpedia.aiReportDirectory",
                "build/reports/modpedia"
        ));
        AiSettings settings = new AiSettingsStore(settingsPath).load();
        if (settings.endpoint().isBlank() || settings.effectiveApiKey().isBlank()) {
            throw new IllegalStateException("当前配置没有可用的 API 地址或密钥；不会开始批量请求。");
        }
        AiModelCompatibilityTester tester = new AiModelCompatibilityTester(settings);
        CompatibilityReport report = tester.runAll(progress -> System.out.println(
                "AI 模型探测 " + progress.completed() + "/" + progress.total()
                        + "：" + progress.model().model()
                        + " plain=" + progress.model().plain().status()
                        + " tool=" + progress.model().tool().status()
                        + " stream=" + progress.model().streaming().status()
        ));
        tester.writeReport(report, reportDirectory);
        System.out.println("AI 模型兼容性报告：" + reportDirectory.toAbsolutePath());
    }
}
