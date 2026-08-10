package io.ctyx.modpedia.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 模型兼容性探测器的本地协议回归，不访问真实 API。 */
public final class AiModelCompatibilitySelfTest {
    private AiModelCompatibilitySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-model-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"probe-model\",\"owned_by\":\"fixture\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/v1/chat/completions", AiModelCompatibilitySelfTest::handleCompletion);
        server.start();
        Path reportRoot = Files.createTempDirectory("modpedia-ai-model-report-");
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "probe-model",
                    "test-key",
                    true,
                    SearchIntensity.STANDARD,
                    3,
                    8,
                    16_000,
                    10
            );
            AiModelCompatibilityTester tester = new AiModelCompatibilityTester(settings, 1);
            AiModelCompatibilityTester.CompatibilityReport report = tester.run(
                    List.of(new AiClient.ModelInfo("probe-model", "fixture")),
                    ignored -> { }
            );
            AiModelCompatibilityTester.ModelReport model = report.models().get(0);
            check(report.totalModels() == 1, "应测试模型列表中的一个模型");
            check(model.plain().status() == AiModelCompatibilityTester.Status.PASS,
                    "普通请求应通过");
            check(model.tool().status() == AiModelCompatibilityTester.Status.PASS,
                    "工具调用续接应通过");
            check(model.streaming().status() == AiModelCompatibilityTester.Status.PASS,
                    "普通 SSE 应通过");
            check(model.streamingTool().status() == AiModelCompatibilityTester.Status.PASS,
                    "流式工具调用续接应通过");
            check(model.streamingUsable(), "四项能力均通过时模型应标记为流式可用");

            tester.writeReport(report, reportRoot);
            String json = Files.readString(reportRoot.resolve("ai-model-compatibility.json"));
            check(!json.contains("test-key"), "兼容性报告不能写入 API Key");

            AtomicReference<AiClient.ModelCompatibilityResult> wrapperResult = new AtomicReference<>();
            CountDownLatch wrapperCompleted = new CountDownLatch(1);
            AiClient.testAllModels(settings, reportRoot.resolve("wrapper"), 1, result -> {
                wrapperResult.set(result);
                wrapperCompleted.countDown();
            });
            check(wrapperCompleted.await(5, TimeUnit.SECONDS), "批量测试包装器应在测试超时内完成");
            check(wrapperResult.get() != null && !wrapperResult.get().failed()
                            && wrapperResult.get().report().totalModels() == 1,
                    "设置页使用的批量测试包装器应复用完整探测报告");
            System.out.println("ModPedia AI model compatibility self-test passed");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            deleteTree(reportRoot);
        }
    }

    private static void handleCompletion(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();
        boolean streaming = request.has("stream") && request.get("stream").getAsBoolean();
        boolean hasTools = request.has("tools");
        boolean continuation = request.has("messages")
                && request.getAsJsonArray("messages").toString().contains("\"role\":\"tool\"");
        String response;
        if (streaming) {
            response = hasTools && !continuation
                    ? streamToolResponse()
                    : streamTextResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        } else {
            response = hasTools && !continuation
                    ? toolResponse()
                    : textResponse();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String textResponse() {
        return "{\"id\":\"probe\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
    }

    private static String toolResponse() {
        return "{\"id\":\"probe\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"fc_call-probe\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"压力管道\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }

    private static String streamTextResponse() {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String streamToolResponse() {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"fc_call-probe\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"压力管道\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
