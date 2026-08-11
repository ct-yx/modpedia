package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 将一个来源集合转换为统一知识文档的扩展点。 */
public interface KnowledgeSourceImporter {
    boolean supports(KnowledgeSourceDescriptor source);

    List<ImportedKnowledgeDocument> importDocuments(
            Path sourceRoot,
            KnowledgeSourceDescriptor source
    ) throws IOException;

    record ImportedKnowledgeDocument(
            KnowledgeDocument document,
            String relativePath,
            String fingerprint,
            int priority,
            String language
    ) {
    }
}
