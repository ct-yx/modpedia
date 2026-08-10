package io.ctyx.modpedia.client;

public record SourceReference(
        String documentId,
        String title,
        String sourceMod,
        String sourcePath,
        String annotation
) {
    /** 兼容旧会话和内置来源：没有 AI 标注时使用原始标题。 */
    public SourceReference(String documentId, String title, String sourceMod, String sourcePath) {
        this(documentId, title, sourceMod, sourcePath, "");
    }

    public SourceReference {
        documentId = documentId == null ? "" : documentId.strip();
        title = title == null ? "" : title.strip();
        sourceMod = sourceMod == null ? "" : sourceMod.strip();
        sourcePath = sourcePath == null ? "" : sourcePath.strip();
        annotation = annotation == null ? "" : compactAnnotation(annotation);
    }

    /** 卡片上的短标题优先使用模型针对当前回答写的说明。 */
    public String displayLabel() {
        return annotation.isBlank() ? title : annotation;
    }

    public SourceReference withAnnotation(String value) {
        return new SourceReference(documentId, title, sourceMod, sourcePath, value);
    }

    private static String compactAnnotation(String value) {
        String compact = value.replaceAll("\\s+", " ").strip();
        if (compact.length() <= 120) {
            return compact;
        }
        return compact.substring(0, 117).strip() + "…";
    }
}
