package io.ctyx.modpedia.knowledge;

import java.util.Map;

/** 一个从已安装模组资源中读取的手册来源。 */
public record ScannedResource(
        String modId,
        String modName,
        String version,
        String path,
        String sourceType,
        String content,
        String fingerprint,
        Map<String, String> translations,
        KnowledgeContentKind contentKind,
        String sourceId,
        String collectionId,
        int priority,
        String originType,
        String metadataJson
) {
    public ScannedResource(
            String modId,
            String modName,
            String version,
            String path,
            String sourceType,
            String content,
            String fingerprint,
            Map<String, String> translations
    ) {
        this(
                modId,
                modName,
                version,
                path,
                sourceType,
                content,
                fingerprint,
                translations,
                KnowledgeContentKind.MOD_MANUAL,
                modId,
                modId,
                0,
                "jar",
                "{}"
        );
    }

    public ScannedResource {
        translations = translations == null ? Map.of() : Map.copyOf(translations);
        contentKind = contentKind == null ? KnowledgeContentKind.MOD_MANUAL : contentKind;
        modId = modId == null ? "" : modId.strip();
        modName = modName == null ? modId : modName.strip();
        version = version == null ? "unknown" : version.strip();
        path = path == null ? "" : path.replace('\\', '/');
        sourceType = sourceType == null ? "" : sourceType.strip();
        content = content == null ? "" : content;
        fingerprint = fingerprint == null ? "" : fingerprint;
        sourceId = sourceId == null || sourceId.isBlank() ? modId : sourceId.strip();
        collectionId = collectionId == null || collectionId.isBlank() ? sourceId : collectionId.strip();
        originType = originType == null || originType.isBlank() ? "jar" : originType.strip();
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    }
}
