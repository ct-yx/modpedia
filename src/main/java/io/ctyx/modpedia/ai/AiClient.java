package io.ctyx.modpedia.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
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
    private static final ExecutorService TEST_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-ai-connection-test");
        thread.setDaemon(true);
        return thread;
    });

    private AiClient() {
    }

    public static OpenAiChatModel blockingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        return OpenAiChatModel.builder()
                .baseUrl(actual.endpoint())
                .apiKey(actual.effectiveApiKey())
                .modelName(actual.model())
                .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                .parallelToolCalls(false)
                .build();
    }

    public static OpenAiStreamingChatModel streamingModel(AiSettings settings) {
        AiSettings actual = requireConfigured(settings);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(actual.endpoint())
                .apiKey(actual.effectiveApiKey())
                .modelName(actual.model())
                .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                .parallelToolCalls(false)
                .build();
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
                resultConsumer.accept(new TestResult(
                        response == null || response.isBlank(),
                        response == null || response.isBlank()
                                ? "请求完成，但模型返回了空内容。"
                                : "连接成功。"
                ));
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                resultConsumer.accept(new TestResult(false, message == null || message.isBlank()
                        ? throwable.getClass().getSimpleName()
                        : message));
            }
        });
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
}
