package io.ctyx.modpedia.search;

/** 检索请求的可观察状态。 */
public enum SearchStatus {
    READY,
    EMPTY_QUERY,
    NO_MATCH,
    INDEX_NOT_READY,
    INDEX_ERROR
}
