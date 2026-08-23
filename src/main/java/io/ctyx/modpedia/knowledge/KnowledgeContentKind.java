package io.ctyx.modpedia.knowledge;

/** 1.12.2 知识来源的语义归属，与文件格式分开保存。 */
public enum KnowledgeContentKind {
    MOD_MANUAL("mod_manual"),
    WIKI("wiki");

    private final String id;

    KnowledgeContentKind(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
