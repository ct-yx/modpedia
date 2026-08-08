package io.ctyx.modpedia.knowledge;

/** 客户端展示用的知识库构建状态快照。 */
public record KnowledgeStatus(
        boolean updating,
        int sourceCount,
        int documentCount,
        String lastUpdated,
        String error
) {
    public KnowledgeStatus {
        lastUpdated = lastUpdated == null ? "" : lastUpdated;
        error = error == null ? "" : error;
    }

    public static KnowledgeStatus initial() {
        return new KnowledgeStatus(false, 0, 0, "", "");
    }
}
