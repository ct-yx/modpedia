package io.ctyx.modpedia.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/v1/chat/completions", exchange -> handleCompletion(exchange, requests));
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
            AtomicBoolean firstToolRequest = new AtomicBoolean(true);
            ProbeService service = AiServices.builder(ProbeService.class)
                    .chatModel(AiClient.blockingModel(settings))
                    .tools(new ProbeTool())
                    .chatRequestTransformer(request -> AiAssistantSession.requireSearchOnFirstRequest(
                            request,
                            firstToolRequest
                    ))
                    .maxToolCallingRoundTrips(2)
                    .build();
            String answer = service.chat("请调用一次本地工具并返回结果");
            check("fixture answer".equals(answer), "LangChain4j 应完成工具调用并返回最终回答");
            check(requests.size() >= 2, "工具调用链至少应包含初始请求和工具结果续接请求");
            String firstRequest = requests.get(0);
            String followUpRequest = requests.get(1);
            String compactFirstRequest = firstRequest.replaceAll("\\s+", "");
            String compactFollowUpRequest = followUpRequest.replaceAll("\\s+", "");
            check(compactFirstRequest.contains("\"tool_choice\":\"required\""),
                    "实际 OpenAI 请求的首轮必须发送 tool_choice=required：" + firstRequest);
            check(compactFirstRequest.contains("\"name\":\"probe_tool\""),
                    "首轮请求必须声明可用工具名称");
            check(compactFollowUpRequest.contains("\"tool_call_id\"")
                            && !compactFollowUpRequest.contains("\"tool_choice\":\"required\""),
                    "工具结果后的实际请求必须恢复自动选择，不能再次强制工具调用");

            requests.clear();
            AtomicBoolean streamingFirstRequest = new AtomicBoolean(true);
            StreamingProbeService streamingService = AiServices.builder(StreamingProbeService.class)
                    .streamingChatModel(AiClient.streamingModel(settings))
                    .tools(new ProbeTool())
                    .chatRequestTransformer(request -> AiAssistantSession.requireSearchOnFirstRequest(
                            request,
                            streamingFirstRequest
                    ))
                    .maxToolCallingRoundTrips(2)
                    .build();
            StringBuilder streamingAnswer = new StringBuilder();
            AtomicReference<Throwable> streamingFailure = new AtomicReference<>();
            CountDownLatch streamingCompleted = new CountDownLatch(1);
            streamingService.chat("请流式调用一次本地工具并返回结果")
                    .onPartialResponse(streamingAnswer::append)
                    .onCompleteResponse(ignored -> streamingCompleted.countDown())
                    .onError(error -> {
                        streamingFailure.set(error);
                        streamingCompleted.countDown();
                    })
                    .start();
            check(streamingCompleted.await(5, TimeUnit.SECONDS), "流式工具调用链应在测试超时内完成");
            check(streamingFailure.get() == null, "流式工具调用不应失败：" + streamingFailure.get());
            check("fixture answer".equals(streamingAnswer.toString()),
                    "LangChain4j 应解析流式工具续接后的最终文本");
            check(requests.size() >= 2, "流式工具调用也必须包含工具结果续接请求");
            String streamingFirstBody = requests.get(0).replaceAll("\\s+", "");
            String streamingFollowUpBody = requests.get(1).replaceAll("\\s+", "");
            check(streamingFirstBody.contains("\"stream\":true")
                            && streamingFirstBody.contains("\"tool_choice\":\"required\""),
                    "流式首轮请求必须同时开启 SSE 和 required 工具调用");
            check(streamingFollowUpBody.contains("\"tool_call_id\""),
                    "流式工具结果必须回传 tool_call_id");
            System.out.println("ModPedia LangChain AI chain self-test passed");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void handleCompletion(HttpExchange exchange, List<String> requests) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);
        boolean hasToolResult = request.contains("\"role\":\"tool\"")
                || request.contains("\"tool_call_id\"");
        boolean streaming = request.contains("\"stream\":true")
                || request.contains("\"stream\" : true");
        String response;
        if (streaming) {
            response = hasToolResult ? streamTextResponse() : streamToolResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        } else {
            response = hasToolResult
                    ? "{\"id\":\"fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture answer\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"id\":\"fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"fixture-call\",\"type\":\"function\",\"function\":{\"name\":\"probe_tool\",\"arguments\":\"{\\\"value\\\":\\\"ok\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    interface ProbeService {
        String chat(String prompt);
    }

    interface StreamingProbeService {
        TokenStream chat(String prompt);
    }

    static final class ProbeTool {
        @Tool(name = "probe_tool", value = "调用本地工具验证工具链")
        public String probe(String value) {
            return "tool result: " + value;
        }
    }

    private static String streamTextResponse() {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"fixture \"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String streamToolResponse() {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"fixture-call\",\"type\":\"function\",\"function\":{\"name\":\"probe_tool\",\"arguments\":\"{\\\"value\\\":\\\"\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ok\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
