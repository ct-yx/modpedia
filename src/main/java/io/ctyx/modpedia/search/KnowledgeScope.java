package io.ctyx.modpedia.search;

/** 文本检索的逻辑来源范围。任务运行数据由独立任务 API 查询。 */
public enum KnowledgeScope {
    MOD_MANUAL,
    WIKI,
    ALL;

    public boolean accepts(String contentKind) {
        if (this == ALL) {
            return true;
        }
        String value = contentKind == null ? "" : contentKind;
        return this == MOD_MANUAL ? "mod_manual".equals(value) : "wiki".equals(value);
    }
}
