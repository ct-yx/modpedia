package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为首轮模型请求设置工具选择策略，后续轮次恢复自动选择。 */
public final class AiToolRouter {
    public static final String TASK_TOOL_NAME = "search_tasks";

    private AiToolRouter() {
    }

    public static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion
    ) {
        if (request == null || firstRequest == null || !firstRequest.compareAndSet(true, false)) {
            return request;
        }
        if (taskQuestion) {
            List<ToolSpecification> taskTools = request.toolSpecifications().stream()
                    .filter(tool -> TASK_TOOL_NAME.equals(tool.name()))
                    .toList();
            if (!taskTools.isEmpty()) {
                return request.toBuilder()
                        .toolSpecifications(taskTools)
                        .toolChoice(ToolChoice.REQUIRED)
                        .build();
            }
        }
        return request.toBuilder().toolChoice(ToolChoice.REQUIRED).build();
    }
}
