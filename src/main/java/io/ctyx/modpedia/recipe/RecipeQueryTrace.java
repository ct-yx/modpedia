package io.ctyx.modpedia.recipe;

/** 配方工具调用的轻量诊断事件，不包含模型密钥或完整会话内容。 */
public record RecipeQueryTrace(RecipeQuery query, RecipeResponse response) {
}
