package io.ctyx.modpedia.search;

/** 一次本地知识库检索请求。 */
public record SearchQuery(String text, int limit, SearchLanguage language, KnowledgeScope scope) {
    public static final int DEFAULT_LIMIT = 8;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 20;

    public SearchQuery(String text, int limit) {
        this(text, limit, SearchLanguage.AUTO, KnowledgeScope.MOD_MANUAL);
    }

    public SearchQuery(String text, int limit, SearchLanguage language) {
        this(text, limit, language, KnowledgeScope.MOD_MANUAL);
    }

    public SearchQuery {
        text = text == null ? "" : text.strip();
        limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
        language = language == null ? SearchLanguage.AUTO : language;
        scope = scope == null ? KnowledgeScope.MOD_MANUAL : scope;
    }

    public static SearchQuery of(String text) {
        return new SearchQuery(text, DEFAULT_LIMIT, SearchLanguage.AUTO);
    }
}
