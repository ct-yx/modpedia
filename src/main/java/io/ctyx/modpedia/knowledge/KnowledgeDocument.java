package io.ctyx.modpedia.knowledge;

import java.util.List;

/** 统一知识文档的运行时表示。 */
public record KnowledgeDocument(
        String id,
        String sourceMod,
        String sourceType,
        String title,
        String category,
        List<String> keywords,
        String sourceVersion,
        String sourcePath,
        String body,
        KnowledgeContentKind contentKind,
        String sourceId,
        String collectionId,
        String originType,
        String metadataJson
) {
    public KnowledgeDocument(
            String id,
            String sourceMod,
            String sourceType,
            String title,
            String category,
            List<String> keywords,
            String sourceVersion,
            String sourcePath,
            String body
    ) {
        this(
                id,
                sourceMod,
                sourceType,
                title,
                category,
                keywords,
                sourceVersion,
                sourcePath,
                body,
                KnowledgeContentKind.MOD_MANUAL,
                sourceMod,
                sourceMod,
                "jar",
                "{}"
        );
    }

    public KnowledgeDocument {
        id = id == null ? "" : id.strip();
        sourceMod = sourceMod == null ? "" : sourceMod.strip();
        sourceType = sourceType == null ? "" : sourceType.strip();
        title = title == null ? "" : title.strip();
        category = category == null ? "" : category.strip();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        sourceVersion = sourceVersion == null ? "unknown" : sourceVersion.strip();
        sourcePath = sourcePath == null ? "" : sourcePath.strip();
        body = body == null ? "" : body;
        contentKind = contentKind == null ? KnowledgeContentKind.MOD_MANUAL : contentKind;
        sourceId = sourceId == null || sourceId.isBlank() ? sourceMod : sourceId.strip();
        collectionId = collectionId == null || collectionId.isBlank() ? sourceId : collectionId.strip();
        originType = originType == null || originType.isBlank() ? "jar" : originType.strip();
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    }
}
