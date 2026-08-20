package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.recipe.RecipeEntry;
import io.ctyx.modpedia.recipe.RecipeIngredient;
import io.ctyx.modpedia.recipe.RecipeMethod;
import io.ctyx.modpedia.recipe.RecipeQuery;
import io.ctyx.modpedia.recipe.RecipeQueryMode;
import io.ctyx.modpedia.recipe.RecipeResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 配方工具的阶段协议回归；不启动 Minecraft、JEI 或真实模型。 */
public final class RecipeQueryToolSelfTest {
    private RecipeQueryToolSelfTest() {
    }

    public static void main(String[] args) {
        AtomicReference<RecipeQuery> lastQuery = new AtomicReference<>();
        RecipeResponse detail = new RecipeResponse(
                "ok",
                "example:ingot",
                "示例锭",
                RecipeQueryMode.DETAIL,
                List.of(new RecipeMethod(
                        "example:machine",
                        "处理机",
                        1,
                        List.of("基础处理机", "高级处理机", "基础处理机")
                )),
                List.of(new RecipeEntry(
                        "example:recipe",
                        "example:machine",
                        "处理机",
                        List.of(new RecipeIngredient("item", "example:ore", "示例矿", 2)),
                        List.of(new RecipeIngredient("item", "example:ingot", "示例锭", 1)),
                        Map.of("category", "machine"),
                        40
                )),
                List.of("基础处理机", "高级处理机"),
                false,
                "ok"
        );
        RecipeQueryTool tool = new RecipeQueryTool(query -> {
            lastQuery.set(query);
            if (query.mode() == RecipeQueryMode.OTHER) {
                return new RecipeResponse(
                        "ok", query.itemId(), "示例锭", query.mode(), detail.methods(),
                        List.of(), List.of(), false, "select method_id"
                );
            }
            return detail;
        }, 8);

        String other = tool.queryItemRecipes(
                "example:ingot", "OTHER", "", 8
        );
        check(lastQuery.get().mode() == RecipeQueryMode.OTHER, "OTHER 应先查询处理方式");
        check(other.contains("example:machine"), "OTHER 应返回 method_id");
        check(other.contains("基础处理机") && other.indexOf("基础处理机")
                        == other.lastIndexOf("基础处理机"),
                "同一机器的不同等级应合并显示");

        String detailJson = tool.queryItemRecipes(
                "example:ingot", "DETAIL", "example:machine", 8
        );
        check(lastQuery.get().mode() == RecipeQueryMode.DETAIL, "DETAIL 应查询具体处理方式");
        check(detailJson.contains("processing_time_ticks")
                        && detailJson.contains("processing_time_seconds"),
                "配方协议应保留处理时间");
        check(detailJson.contains("example:ore"), "配方协议应保留输入物品 ID");

        String invalid = tool.queryItemRecipes("example:ingot", "DETAIL", "", 8);
        check(invalid.contains("\"status\":\"invalid\""),
                "DETAIL 缺少 method_id 时应返回结构化错误");

        RecipeResponse roundTrip = WorkerPayloadCodec.recipeResponse(
                WorkerPayloadCodec.recipeResponse(detail)
        );
        check(roundTrip.recipes().get(0).processingTimeTicks() == 40,
                "配方 JSON 往返不得丢失处理时间");
        check(roundTrip.machines().size() == 1 && roundTrip.methods().get(0).machines().size() == 1,
                "配方 JSON 往返应保持去重后的机器列表");

        RecipeQueryTool unavailable = new RecipeQueryTool(null, 8);
        check(unavailable.queryItemRecipes("example:ingot", "WORKBENCH", "", 8)
                        .contains("\"status\":\"unavailable\""),
                "缺少可选客户端配方服务时应返回 unavailable");
        System.out.println("ModPedia recipe query tool self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
