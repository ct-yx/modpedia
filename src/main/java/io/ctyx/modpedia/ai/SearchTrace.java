package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.client.SourceReference;

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
        long createdAt
) {
    public SearchTrace {
        query = query == null ? "" : query;
        language = language == null ? "auto" : language;
        focus = focus == null ? "identify" : focus;
        round = Math.max(1, round);
        status = status == null ? "" : status;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
