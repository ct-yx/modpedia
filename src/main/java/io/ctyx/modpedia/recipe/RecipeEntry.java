package io.ctyx.modpedia.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一条由可选客户端配方服务返回的完整配方。 */
public record RecipeEntry(
        String recipeId,
        String methodId,
        String methodName,
        List<RecipeIngredient> inputs,
        List<RecipeIngredient> outputs,
        Map<String, String> metadata,
        Integer processingTimeTicks
) {
    public RecipeEntry {
        recipeId = recipeId == null ? "" : recipeId.strip();
        methodId = methodId == null ? "" : methodId.strip();
        methodName = methodName == null ? "" : methodName.strip();
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        if (processingTimeTicks != null && processingTimeTicks < 0) {
            processingTimeTicks = null;
        }
    }
}
