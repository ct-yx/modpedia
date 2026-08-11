package io.ctyx.modpedia.knowledge;

/** 文档的语义归属，与实际文件格式解耦。 */
public enum KnowledgeContentKind {
    MOD_MANUAL("mod_manual"),
    WIKI("wiki"),
    TASK_RUNTIME("task_runtime");

    private final String id;

    KnowledgeContentKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static KnowledgeContentKind parse(String value) {
        if (value == null || value.isBlank()) {
            return MOD_MANUAL;
        }
        for (KnowledgeContentKind kind : values()) {
            if (kind.id.equalsIgnoreCase(value.strip()) || kind.name().equalsIgnoreCase(value.strip())) {
                return kind;
            }
        }
        return MOD_MANUAL;
    }
}
