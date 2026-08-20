package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为首轮模型请求设置工具选择策略，后续轮次恢复自动选择。 */
public final class AiToolRouter {
    public static final String KNOWLEDGE_TOOL_NAME = "search_knowledge";
    public static final String TASK_TOOL_NAME = "search_tasks";

    private AiToolRouter() {
    }

    public static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion
    ) {
        return requireSearchOnFirstRequest(request, firstRequest, taskQuestion, 0);
    }

    /**
     * 首轮只为生成工具参数预留较小输出窗口；工具结果回传后的请求使用回答预算。
     * 这样不会削弱首轮强制检索，同时避免模型在检索阶段生成过程性长文本。
     */
    public static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion,
            int answerTokens
    ) {
        return requireSearchOnFirstRequest(request, firstRequest, taskQuestion, answerTokens, false);
    }

    /**
     * GPT-5 和 o 系列在 Chat Completions 中使用 max_completion_tokens；旧模型继续
     * 使用通用 maxOutputTokens（由 LangChain4j 映射为 max_tokens）。两者不能同时发出。
     */
    public static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion,
            int answerTokens,
            boolean useCompletionTokens
    ) {
        if (request == null || firstRequest == null || !firstRequest.compareAndSet(true, false)) {
            return limitOutput(request, answerTokens, useCompletionTokens);
        }
        int firstToolBudget = useCompletionTokens
                ? AiTokenBudget.REASONING_FIRST_TOOL_CALL
                : AiTokenBudget.FIRST_TOOL_CALL;
        ChatRequest routed = limitOutput(request, firstToolBudget, useCompletionTokens);
        if (taskQuestion) {
            List<ToolSpecification> taskTools = routed.toolSpecifications().stream()
                    .filter(tool -> TASK_TOOL_NAME.equals(tool.name()))
                    .toList();
            if (!taskTools.isEmpty()) {
                return routed.toBuilder()
                        .toolSpecifications(taskTools)
                        .toolChoice(ToolChoice.REQUIRED)
                        .build();
            }
        }
        // 首轮检索必须落到知识库工具。此前这里仅设置 REQUIRED；在增加
        // calculate/query_item_recipes 后，模型可以合法地选择任意工具，导致
        // 普通手册问题没有调用 search_knowledge，随后直接生成“没有资料”的回答。
        // 只在实际声明了该工具时收窄，保留没有知识库工具的兼容测试/调用方行为。
        List<ToolSpecification> knowledgeTools = routed.toolSpecifications().stream()
                .filter(tool -> KNOWLEDGE_TOOL_NAME.equals(tool.name()))
                .toList();
        if (!knowledgeTools.isEmpty()) {
            return routed.toBuilder()
                    .toolSpecifications(knowledgeTools)
                    .toolChoice(ToolChoice.REQUIRED)
                    .build();
        }
        return routed.toBuilder().toolChoice(ToolChoice.REQUIRED).build();
    }

    private static ChatRequest limitOutput(
            ChatRequest request,
            int maximum,
            boolean useCompletionTokens
    ) {
        if (request == null) {
            return null;
        }
        if (maximum <= 0) {
            return request;
        }
        int normalized = Math.max(128, maximum);
        if (useCompletionTokens) {
            return withCompletionTokenBudget(request, normalized);
        }
        Integer current = request.maxOutputTokens();
        // 首轮请求会把同一个 ChatRequest 链路限制在 FIRST_TOOL_CALL；后续请求
        // 必须把较小的首轮上限提升回最终回答预算，不能把“更小也满足 <=”当成
        // 已经设置完成。
        if (current != null && current == normalized) {
            return request;
        }
        return request.toBuilder().maxOutputTokens(normalized).build();
    }

    private static ChatRequest withCompletionTokenBudget(ChatRequest request, int maximum) {
        Integer current = request.parameters() instanceof OpenAiChatRequestParameters openAi
                ? openAi.maxCompletionTokens()
                : null;
        // 与通用 maxOutputTokens 相同：首轮的小预算不能泄漏到工具结果后的最终回答。
        if (current != null && current == maximum && request.maxOutputTokens() == null) {
            return request;
        }
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .overrideWith(request.parameters())
                // 清掉通用字段，避免 LangChain4j 同时序列化 max_tokens 和
                // max_completion_tokens；GPT-5 网关会拒绝这种请求。
                .maxOutputTokens(null)
                .maxCompletionTokens(maximum)
                .build();
        return request.toBuilder().parameters(parameters).build();
    }
}
