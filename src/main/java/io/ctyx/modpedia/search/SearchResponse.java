package io.ctyx.modpedia.search;

import java.util.List;

/** 检索结果及索引状态，不把文件读取异常伪装成“没有匹配”。 */
public record SearchResponse(
        SearchStatus status,
        String query,
        List<SearchResult> results,
        String error
) {
    public SearchResponse {
        status = status == null ? SearchStatus.INDEX_ERROR : status;
        query = query == null ? "" : query;
        results = results == null ? List.of() : List.copyOf(results);
        error = error == null ? "" : error;
    }

    public boolean hasResults() {
        return !results.isEmpty();
    }
}
