package io.ctyx.modpedia.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 四种 API 格式的普通回答、工具续接和 SSE 回归夹具；全程只访问本地 HTTP 服务。 */
public final class AiProtocolSelfTest {
    private AiProtocolSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-protocol-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/messages", exchange -> handle(exchange, AiApiFormat.NATIVE_MESSAGES));
        server.createContext("/v1/responses", exchange -> handle(exchange, AiApiFormat.RESPONSES));
        server.createContext("/v1beta/models/fixture-model:generateContent",
                exchange -> handle(exchange, AiApiFormat.GENERATE_CONTENT));
        server.createContext("/v1beta/models/fixture-model:streamGenerateContent",
                exchange -> handle(exchange, AiApiFormat.GENERATE_CONTENT));
        server.createContext("/v1/models", exchange -> send(exchange,
                "{\"data\":[{\"id\":\"fixture-model\"}]}"));
        server.createContext("/v1beta/models", exchange -> send(exchange,
                "{\"models\":[{\"name\":\"models/fixture-model\"}]}"));
        server.start();
        try {
            for (AiApiFormat format : new AiApiFormat[]{
                    AiApiFormat.NATIVE_MESSAGES,
                    AiApiFormat.RESPONSES,
                    AiApiFormat.GENERATE_CONTENT
            }) {
                runBlocking(server, format);
                runStreaming(server, format);
                runConnectionAndModels(server, format);
            }
            check(AiApiFormat.parse("native_messages") == AiApiFormat.NATIVE_MESSAGES,
                    "原生 Messages 名称应可解析");
            check(AiApiFormat.parse("generateContent") == AiApiFormat.GENERATE_CONTENT,
                    "generateContent 名称应可解析");
            check("https://example.invalid/v1beta".equals(
                            AiClient.normalizedEndpoint("https://example.invalid", AiApiFormat.GENERATE_CONTENT)),
                    "generateContent 根地址应使用 /v1beta");
            System.out.println("ModPedia AI protocol self-test passed");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void runBlocking(HttpServer server, AiApiFormat format) {
        AiSettings settings = settings(server, format, false);
        ProbeService service = AiServices.builder(ProbeService.class)
                .chatModel(AiClient.chatModel(settings))
                .tools(new ProbeTool())
                .maxToolCallingRoundTrips(2)
                .build();
        String answer = service.chat("请调用工具并返回最终文本");
        check("fixture answer".equals(answer), format + " 阻塞工具调用续接失败：" + answer);
    }

    private static void runStreaming(HttpServer server, AiApiFormat format) throws Exception {
        AiSettings settings = settings(server, format, true);
        ProbeStreamingService service = AiServices.builder(ProbeStreamingService.class)
                .streamingChatModel(AiClient.streamingChatModel(settings))
                .tools(new ProbeTool())
                .maxToolCallingRoundTrips(2)
                .build();
        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        TokenStream stream = service.chat("请流式调用工具并返回最终文本")
                .onPartialResponse(value -> {
                    if (value != null && !value.isBlank()) answer.append(value);
                })
                .onCompleteResponse(ignored -> done.countDown())
                .onError(error -> {
                    failure.set(error);
                    done.countDown();
                });
        stream.start();
        check(done.await(5, TimeUnit.SECONDS), format + " SSE 工具调用超时");
        check(failure.get() == null, format + " SSE 工具调用失败：" + failure.get());
        check("fixture answer".equals(answer.toString()), format + " SSE 文本错误：" + answer);
    }

    private static void runConnectionAndModels(HttpServer server, AiApiFormat format) throws Exception {
        AiSettings settings = settings(server, format, false);
        AiClient.TestResult connection = AiClient.testConnectionBlocking(settings);
        check(!connection.failed(), format + " 连接测试失败：" + connection.message());
        AtomicReference<AiClient.ModelListResult> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AiClient.fetchModels(settings, value -> {
            result.set(value);
            done.countDown();
        });
        check(done.await(5, TimeUnit.SECONDS), format + " 模型列表请求超时");
        check(result.get() != null && !result.get().failed()
                        && result.get().models().stream().anyMatch(model -> "fixture-model".equals(model.id())),
                format + " 模型列表解析失败：" + result.get());
    }

    private static AiSettings settings(HttpServer server, AiApiFormat format, boolean streaming) {
        String version = format == AiApiFormat.GENERATE_CONTENT ? "/v1beta" : "/v1";
        return new AiSettings(
                AssistantMode.AI,
                format,
                "http://127.0.0.1:" + server.getAddress().getPort() + version,
                "fixture-model",
                "fixture-key",
                streaming,
                SearchIntensity.FAST,
                1,
                4,
                8_000,
                10
        );
    }

    private static void handle(HttpExchange exchange, AiApiFormat format) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean hasResult = body.contains("tool_result")
                || body.contains("function_call_output")
                || body.contains("functionResponse");
        boolean streaming = exchange.getRequestURI().getPath().contains("streamGenerateContent")
                || body.contains("\"stream\":true")
                || body.contains("\"stream\" : true");
        String response = streaming
                ? streamResponse(format, hasResult)
                : jsonResponse(format, hasResult);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String jsonResponse(AiApiFormat format, boolean hasResult) {
        if (hasResult) {
            return switch (format) {
                case NATIVE_MESSAGES -> "{\"id\":\"m2\",\"model\":\"fixture-model\",\"content\":[{\"type\":\"text\",\"text\":\"fixture answer\"}]}";
                case RESPONSES -> "{\"id\":\"r2\",\"model\":\"fixture-model\",\"output_text\":\"fixture answer\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"fixture answer\"}]}]}";
                case GENERATE_CONTENT -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"fixture answer\"}]}}]}";
                case CHAT_COMPLETIONS -> "{}";
            };
        }
        return switch (format) {
            case NATIVE_MESSAGES -> "{\"id\":\"m1\",\"model\":\"fixture-model\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"probe_tool\",\"input\":{\"query\":\"x\"}}],\"stop_reason\":\"tool_use\"}";
            case RESPONSES -> "{\"id\":\"r1\",\"model\":\"fixture-model\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"probe_tool\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}]}";
            case GENERATE_CONTENT -> "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"probe_tool\",\"args\":{\"query\":\"x\"}}}]}}]}";
            case CHAT_COMPLETIONS -> "{}";
        };
    }

    private static String streamResponse(AiApiFormat format, boolean hasResult) {
        if (format == AiApiFormat.NATIVE_MESSAGES) {
            return hasResult
                    ? "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"model\":\"fixture-model\"}}\n\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"fixture answer\"}}\n\n"
                    + "data: [DONE]\n\n"
                    : "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"model\":\"fixture-model\"}}\n\n"
                    + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"probe_tool\"}}\n\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"x\\\"}\"}}\n\n"
                    + "data: [DONE]\n\n";
        }
        if (format == AiApiFormat.RESPONSES) {
            return hasResult
                    ? "data: {\"type\":\"response.output_text.delta\",\"delta\":\"fixture answer\"}\n\n"
                    + "data: [DONE]\n\n"
                    : "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc-1\",\"call_id\":\"call-1\",\"name\":\"probe_tool\"}}\n\n"
                    + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc-1\",\"delta\":\"{\\\"query\\\":\\\"x\\\"}\"}\n\n"
                    + "data: [DONE]\n\n";
        }
        return hasResult
                ? "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"fixture answer\"}]}}]}\n\n"
                + "data: [DONE]\n\n"
                : "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"probe_tool\",\"args\":{\"query\":\"x\"}}}]}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    public interface ProbeService {
        String chat(String prompt);
    }

    public interface ProbeStreamingService {
        TokenStream chat(String prompt);
    }

    public static final class ProbeTool {
        @Tool("测试工具")
        public String probe_tool(String query) {
            return "tool result for " + query;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
