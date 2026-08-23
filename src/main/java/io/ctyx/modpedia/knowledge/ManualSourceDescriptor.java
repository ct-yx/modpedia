package io.ctyx.modpedia.knowledge;

import java.util.Locale;

/**
 * 一个可导入的 1.12.2 手册来源集合。
 *
 * <p>对象只保存来源索引，不把正文复制到客户端内存或主线程；后续 Worker
 * 可以根据 sourcePath/sourceType 再读取并转换为 Markdown。</p>
 */
public final class ManualSourceDescriptor {
    private final String sourceId;
    private final String collectionId;
    private final KnowledgeContentKind contentKind;
    private final ManualSourceType sourceType;
    private final String originType;
    private final String title;
    private final String language;
    private final String version;
    private final String sourcePath;
    private final String fingerprint;
    private final int fileCount;

    public ManualSourceDescriptor(
            String sourceId,
            String collectionId,
            KnowledgeContentKind contentKind,
            ManualSourceType sourceType,
            String originType,
            String title,
            String language,
            String version,
            String sourcePath,
            String fingerprint,
            int fileCount
    ) {
        this.sourceId = value(sourceId, "unknown");
        this.collectionId = value(collectionId, this.sourceId);
        this.contentKind = contentKind == null ? KnowledgeContentKind.MOD_MANUAL : contentKind;
        this.sourceType = sourceType == null ? ManualSourceType.CUSTOM_TEXT_1_12 : sourceType;
        this.originType = value(originType, "jar");
        this.title = value(title, this.sourceId);
        this.language = value(language, "neutral").toLowerCase(Locale.ROOT);
        this.version = value(version, "unknown");
        this.sourcePath = value(sourcePath, "");
        this.fingerprint = value(fingerprint, "");
        this.fileCount = Math.max(0, fileCount);
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getCollectionId() {
        return collectionId;
    }

    public KnowledgeContentKind getContentKind() {
        return contentKind;
    }

    public ManualSourceType getSourceType() {
        return sourceType;
    }

    public String getOriginType() {
        return originType;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguage() {
        return language;
    }

    public String getVersion() {
        return version;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFileCount() {
        return fileCount;
    }

    private static String value(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }
}
