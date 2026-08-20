package io.ctyx.modpedia.recipe;

import java.util.List;

/** 一个可供模型选择的处理方式。 */
public record RecipeMethod(
        String methodId,
        String name,
        int recipeCount,
        List<String> machines
) {
    public RecipeMethod {
        methodId = methodId == null ? "" : methodId.strip();
        name = name == null ? "" : name.strip();
        recipeCount = Math.max(0, recipeCount);
        machines = machines == null ? List.of() : RecipeMachineNormalizer.unique(machines);
    }
}
