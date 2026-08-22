package io.ctyx.modpedia.worker;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import io.ctyx.modpedia.ai.AiToolRouter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Worker 与客户端 AI 会话共用的预算和首轮工具调用规则。 */
final class WorkerAiSupport {
    private WorkerAiSupport() {
    }

    static ChatRequest requireSearchOnFirstRequest(ChatRequest request, AtomicBoolean firstRequest) {
        return AiToolRouter.requireSearchOnFirstRequest(request, firstRequest, false);
    }

    static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion
    ) {
        return AiToolRouter.requireSearchOnFirstRequest(request, firstRequest, taskQuestion);
    }

    static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion,
            int answerTokens
    ) {
        return AiToolRouter.requireSearchOnFirstRequest(request, firstRequest, taskQuestion, answerTokens);
    }

    static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion,
            int answerTokens,
            boolean useCompletionTokens
    ) {
        return AiToolRouter.requireSearchOnFirstRequest(
                request, firstRequest, taskQuestion, answerTokens, useCompletionTokens
        );
    }

    static int memoryTokenBudget(int contextChars) {
        int normalized = Math.max(4_000, Math.min(64_000, contextChars));
        return Math.max(8_000, (normalized + 1) / 2 + 2_048);
    }

    static int toolCallingRoundTrips(int searchRounds) {
        return Math.max(4, Math.min(12, Math.max(1, searchRounds) + 3));
    }

    static TokenCountEstimator tokenCountEstimator(String model) {
        return tokenCountEstimator(() -> new OpenAiTokenCountEstimator(model));
    }

    static TokenCountEstimator tokenCountEstimator(Supplier<TokenCountEstimator> exactEstimator) {
        try {
            return exactEstimator.get();
        } catch (RuntimeException | LinkageError ignored) {
            // tiktoken 词表属于共享 Worker lib。某个运行环境的类加载器只要无法
            // 读取资源，就使用近似估算继续工作，避免一次初始化失败污染后续重试。
            return new ApproximateTokenCountEstimator();
        }
    }

    static final class ApproximateTokenCountEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInText(String text) {
            return Math.max(1, text == null ? 0 : text.codePointCount(0, text.length()) / 2);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return estimateTokenCountInText(message == null ? "" : message.toString());
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 3;
            if (messages != null) {
                for (ChatMessage message : messages) {
                    total += estimateTokenCountInMessage(message);
                }
            }
            return total;
        }
    }
}
