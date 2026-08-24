package io.ctyx.modpedia.client;

/** 1.12.2 客户端可绘制的来源引用，不依赖现代 Minecraft API。 */
public final class LegacySourceReference {
    private final String documentId;
    private final String title;
    private final String sourcePath;
    private final String annotation;

    public LegacySourceReference(String documentId, String title, String sourcePath,
            String annotation) {
        this.documentId = safe(documentId);
        this.title = safe(title);
        this.sourcePath = safe(sourcePath);
        this.annotation = safe(annotation);
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getTitle() {
        return title;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getAnnotation() {
        return annotation;
    }

    public String displayLabel() {
        if (!annotation.isEmpty()) {
            return annotation;
        }
        if (!title.isEmpty()) {
            return title;
        }
        return documentId;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
