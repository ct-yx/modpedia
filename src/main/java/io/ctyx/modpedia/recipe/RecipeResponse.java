package io.ctyx.modpedia.recipe;

import java.util.List;

/** 配方服务的结构化结果；工具把它序列化后交给模型。 */
public record RecipeResponse(
        String status,
        String itemId,
        String itemName,
        RecipeQueryMode mode,
        List<RecipeMethod> methods,
        List<RecipeEntry> recipes,
        List<String> machines,
        boolean hasMore,
        String message
) {
    public RecipeResponse {
        status = status == null || status.isBlank() ? "unavailable" : status.strip();
        itemId = itemId == null ? "" : itemId.strip();
        itemName = itemName == null ? "" : itemName.strip();
        mode = mode == null ? RecipeQueryMode.OTHER : mode;
        methods = methods == null ? List.of() : List.copyOf(methods);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        machines = machines == null ? List.of() : RecipeMachineNormalizer.unique(machines);
        message = message == null ? "" : message.strip();
    }

    public static RecipeResponse unavailable(RecipeQuery query, String message) {
        return new RecipeResponse(
                "unavailable",
                query == null ? "" : query.itemId(),
                "",
                query == null ? RecipeQueryMode.OTHER : query.mode(),
                List.of(),
                List.of(),
                List.of(),
                false,
                message
        );
    }

    public static RecipeResponse invalid(RecipeQuery query, String message) {
        return new RecipeResponse(
                "invalid",
                query == null ? "" : query.itemId(),
                "",
                query == null ? RecipeQueryMode.OTHER : query.mode(),
                List.of(),
                List.of(),
                List.of(),
                false,
                message
        );
    }
}
