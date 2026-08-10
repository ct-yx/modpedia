package io.ctyx.modpedia.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 验证实际 LangChain4j → OpenAI Chat Completions → 工具结果续接链路。
 * 批量模型探测负责真实 API；本测试用本地协议夹具确保库升级或 Jar-in-Jar 变更不会
 * 再次破坏工具消息顺序。
 */
public final class AiLangChainSelfTest {
    private AiLangChainSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-langchain-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", AiLangChainSelfTest::handleCompletion);
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture-model",
                    "fixture-key",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            ProbeService service = AiServices.builder(ProbeService.class)
                    .chatModel(AiClient.blockingModel(settings))
                    .tools(new ProbeTool())
                    .maxToolCallingRoundTrips(2)
                    .build();
            String answer = service.chat("请调用一次本地工具并返回结果");
            check("fixture answer".equals(answer), "LangChain4j 应完成工具调用并返回最终回答");
            System.out.println("ModPedia LangChain AI chain self-test passed");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void handleCompletion(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean hasToolResult = request.contains("\"role\":\"tool\"")
                || request.contains("\"tool_call_id\"");
        String response = hasToolResult
                ? "{\"id\":\"fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture answer\"},\"finish_reason\":\"stop\"}]}"
                : "{\"id\":\"fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"fixture-call\",\"type\":\"function\",\"function\":{\"name\":\"probe_tool\",\"arguments\":\"{\\\"value\\\":\\\"ok\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    interface ProbeService {
        String chat(String prompt);
    }

    static final class ProbeTool {
        @Tool(name = "probe_tool", value = "调用本地工具验证工具链")
        public String probe(String value) {
            return "tool result: " + value;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
