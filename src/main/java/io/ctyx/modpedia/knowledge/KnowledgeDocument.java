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
        String body
) {
}
