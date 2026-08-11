package io.ctyx.modpedia.search;

import java.util.List;

/** 一个文档中与查询最相关的完整 Markdown 段落。 */
public record SearchResult(
        String documentId,
        String title,
        String sourceMod,
        String sourceType,
        String category,
        String sourceVersion,
        String sourcePath,
        String headingPath,
        String segmentMarkdown,
        int score,
        List<String> matchedTerms,
        String contentKind,
        String sourceId,
        String collectionId
) {
    public SearchResult(
            String documentId,
            String title,
            String sourceMod,
            String sourceType,
            String category,
            String sourceVersion,
            String sourcePath,
            String headingPath,
            String segmentMarkdown,
            int score,
            List<String> matchedTerms
    ) {
        this(
                documentId,
                title,
                sourceMod,
                sourceType,
                category,
                sourceVersion,
                sourcePath,
                headingPath,
                segmentMarkdown,
                score,
                matchedTerms,
                "mod_manual",
                sourceMod,
                sourceMod
        );
    }

    public SearchResult {
        documentId = documentId == null ? "" : documentId;
        title = title == null ? "" : title;
        sourceMod = sourceMod == null ? "" : sourceMod;
        sourceType = sourceType == null ? "" : sourceType;
        category = category == null ? "" : category;
        sourceVersion = sourceVersion == null ? "" : sourceVersion;
        sourcePath = sourcePath == null ? "" : sourcePath;
        headingPath = headingPath == null ? "" : headingPath;
        segmentMarkdown = segmentMarkdown == null ? "" : segmentMarkdown;
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        contentKind = contentKind == null || contentKind.isBlank() ? "mod_manual" : contentKind;
        sourceId = sourceId == null ? "" : sourceId;
        collectionId = collectionId == null ? "" : collectionId;
    }
}
