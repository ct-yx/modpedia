package io.ctyx.modpedia.knowledge;

/** 一个可导入的知识来源集合。 */
public record KnowledgeSourceDescriptor(
        String sourceId,
        String collectionId,
        KnowledgeContentKind contentKind,
        String sourceType,
        String originType,
        String title,
        String language,
        String version,
        String originUri,
        String localRoot,
        int priority,
        String metadataJson
) {
    public KnowledgeSourceDescriptor {
        sourceId = normalize(sourceId, "local");
        collectionId = normalize(collectionId, sourceId);
        contentKind = contentKind == null ? KnowledgeContentKind.WIKI : contentKind;
        sourceType = normalize(sourceType, "wiki_markdown");
        originType = normalize(originType, "local");
        title = normalize(title, sourceId);
        language = normalize(language, "neutral");
        version = normalize(version, "unknown");
        originUri = originUri == null ? "" : originUri.strip();
        localRoot = localRoot == null ? "" : localRoot.strip();
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
