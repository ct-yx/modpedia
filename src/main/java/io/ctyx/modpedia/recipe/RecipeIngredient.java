package io.ctyx.modpedia.recipe;

/** 配方中的一个输入、输出或其它处理材料。 */
public record RecipeIngredient(
        String kind,
        String id,
        String displayName,
        int amount
) {
    public RecipeIngredient {
        kind = kind == null || kind.isBlank() ? "ingredient" : kind.strip();
        id = id == null ? "" : id.strip();
        displayName = displayName == null ? "" : displayName.strip();
        amount = Math.max(1, amount);
    }
}
