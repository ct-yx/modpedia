package io.ctyx.modpedia.knowledge;

import java.util.List;

/** 将一个手册资源展开为一个或多个可检索文档。 */
public interface GuideDocumentConverter {
    List<KnowledgeDocument> convertAll(ScannedResource source);
}
