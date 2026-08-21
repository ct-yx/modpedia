package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.api.SourceReference;

import java.util.List;

/** 一次 AI 知识库工具调用的可回看的摘要。 */
public record SearchTrace(
        String query,
        String language,
        String focus,
        int round,
        String status,
        boolean hasMore,
        List<SourceReference> sources,
        long createdAt,
        String tool
) {
    /** 保留旧调用方的构造方式；旧轨迹默认属于模组手册搜索。 */
    public SearchTrace(
            String query,
            String language,
            String focus,
            int round,
            String status,
            boolean hasMore,
            List<SourceReference> sources,
            long createdAt
    ) {
        this(query, language, focus, round, status, hasMore, sources, createdAt, "search_knowledge");
    }

    public SearchTrace {
        query = query == null ? "" : query;
        language = language == null ? "auto" : language;
        focus = focus == null ? "identify" : focus;
        round = Math.max(1, round);
        status = status == null ? "" : status;
        sources = sources == null ? List.of() : List.copyOf(sources);
        tool = tool == null || tool.isBlank() ? "search_knowledge" : tool;
    }
}
