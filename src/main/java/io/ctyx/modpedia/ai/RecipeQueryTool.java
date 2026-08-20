package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.recipe.RecipeQuery;
import io.ctyx.modpedia.recipe.RecipeQueryMode;
import io.ctyx.modpedia.recipe.RecipeQueryTrace;
import io.ctyx.modpedia.recipe.RecipeResponse;

import java.util.function.Consumer;

/** 让模型分阶段读取可选客户端配方服务中的配方。 */
public final class RecipeQueryTool {
    public static final String TOOL_NAME = "query_item_recipes";

    private final Requester requester;
    private final int maxResults;
    private final Consumer<RecipeQueryTrace> traceSink;

    public RecipeQueryTool(Requester requester, int maxResults, Consumer<RecipeQueryTrace> traceSink) {
        this.requester = requester;
        this.maxResults = Math.max(1, Math.min(RecipeQuery.MAX_LIMIT,
                maxResults <= 0 ? RecipeQuery.DEFAULT_LIMIT : maxResults));
        this.traceSink = traceSink == null ? ignored -> { } : traceSink;
    }

    public RecipeQueryTool(Requester requester, int maxResults) {
        this(requester, maxResults, null);
    }

    @Tool(
            name = TOOL_NAME,
            value = "按物品 ID 查询配方。WORKBENCH/FURNACE 直接返回对应配方；OTHER 先返回方式，"
                    + "再用 DETAIL+method_id 查询配方和已合并的机器列表。FURNACE 返回处理时间。"
    )
    public String queryItemRecipes(
            @P(name = "item_id", value = "已确认 ID，如 namespace:item") String itemId,
            @P(name = "mode", value = "WORKBENCH、FURNACE、OTHER 或 DETAIL") String mode,
            @P(name = "method_id", value = "DETAIL 使用上一步的 method_id；其它模式留空",
                    defaultValue = "", required = false)
            String methodId,
            @P(name = "limit", value = "配方数 1-32",
                    defaultValue = "8", required = false)
            Integer limit
    ) {
        String normalizedItemId = itemId == null ? "" : itemId.strip();
        RecipeQueryMode requestedMode = RecipeQueryMode.parse(mode);
        String normalizedMethodId = methodId == null ? "" : methodId.strip();
        int requestedLimit = limit == null ? maxResults : Math.max(1, Math.min(maxResults, limit));
        RecipeQuery query = new RecipeQuery(normalizedItemId, requestedMode, normalizedMethodId, requestedLimit);
        RecipeResponse response;
        if (normalizedItemId.isBlank()) {
            response = RecipeResponse.invalid(query, "缺少已确认的 item_id");
        } else if (requestedMode == RecipeQueryMode.DETAIL && normalizedMethodId.isBlank()) {
            response = RecipeResponse.invalid(query, "DETAIL 查询必须提供上一步返回的 method_id");
        } else if (requester == null) {
            response = RecipeResponse.unavailable(query, "当前客户端没有可用的配方服务");
        } else {
            try {
                response = requester.request(query);
                if (response == null) {
                    response = RecipeResponse.unavailable(query, "配方服务没有返回结果");
                }
            } catch (Throwable failure) {
                response = RecipeResponse.unavailable(query, "配方查询失败：" + messageOf(failure));
            }
        }
        try {
            traceSink.accept(new RecipeQueryTrace(query, response));
        } catch (Throwable ignored) {
            // 诊断监听器失败不能影响模型继续处理配方结果。
        }
        return WorkerPayloadCodec.recipeResponse(response).toString();
    }

    private static String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? failure == null ? "未知错误" : failure.getClass().getSimpleName()
                : current.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    @FunctionalInterface
    public interface Requester {
        RecipeResponse request(RecipeQuery query);
    }
}
