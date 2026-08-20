package io.ctyx.modpedia.recipe;

/** Worker 与客户端之间传递的配方查询请求。 */
public record RecipeQuery(
        String itemId,
        RecipeQueryMode mode,
        String methodId,
        int limit
) {
    public static final int DEFAULT_LIMIT = 8;
    public static final int MAX_LIMIT = 32;

    public RecipeQuery {
        itemId = itemId == null ? "" : itemId.strip();
        mode = mode == null ? RecipeQueryMode.OTHER : mode;
        methodId = methodId == null ? "" : methodId.strip();
        limit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    }
}
