package io.ctyx.modpedia.knowledge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将 Markdown 来源整理为 ModPedia 的统一文档格式。 */
public final class MarkdownDocumentConverter implements GuideDocumentConverter {
    /** 自定义 Markdown 的轻量元数据，供启动同步在解析前判断是否可复用。 */
    public record CustomMetadata(
            boolean validFrontMatter,
            String id,
            String language,
            String sourceType,
            KnowledgeContentKind contentKind,
            String sourceId,
            String collectionId,
            int priority
    ) {
        public CustomMetadata(boolean validFrontMatter, String id, String language, String sourceType) {
            this(validFrontMatter, id, language, sourceType, KnowledgeContentKind.WIKI, "", "", 100);
        }

        public CustomMetadata {
            id = id == null ? "" : id.trim();
            language = language == null || language.isBlank() ? "neutral" : language.trim();
            sourceType = sourceType == null || sourceType.isBlank() ? "custom_markdown" : sourceType.trim();
            contentKind = contentKind == null ? KnowledgeContentKind.WIKI : contentKind;
            sourceId = sourceId == null ? "" : sourceId.trim();
            collectionId = collectionId == null ? "" : collectionId.trim();
            priority = Math.max(0, Math.min(1000, priority));
        }
    }

    public CustomMetadata inspectCustom(String content) {
        FrontMatter frontMatter = FrontMatter.parse(content);
        return new CustomMetadata(
                frontMatter.valid(),
                frontMatter.value("id"),
                frontMatter.value("language"),
                frontMatter.value("source_type"),
                KnowledgeContentKind.parse(frontMatter.value("content_kind")),
                frontMatter.value("source_id"),
                frontMatter.value("collection_id"),
                frontMatter.intValue("priority", 100)
        );
    }

    @Override
    public List<KnowledgeDocument> convertAll(ScannedResource source) {
        return List.of(convert(source));
    }

    public KnowledgeDocument convert(ScannedResource source) {
        FrontMatter frontMatter = FrontMatter.parse(source.content());
        String title = firstNonBlank(
                frontMatter.value("title"),
                firstHeading(frontMatter.body()),
                fileTitle(source.path())
        );
        String category = firstNonBlank(frontMatter.value("category"), categoryOf(source.path()), "guide");
        List<String> keywords = mergeKeywords(
                frontMatter.keywords(),
                KeywordExtractor.extract(title, category, source.modId(), source.path()),
                LocalizedKeywordExtractor.extract(source)
        );
        String id = firstNonBlank(frontMatter.value("id"), documentId(source));
        String sourceMod = firstNonBlank(frontMatter.value("source_mod"), source.modId());
        String sourceType = firstNonBlank(frontMatter.value("source_type"), source.sourceType());
        String sourceVersion = firstNonBlank(frontMatter.value("source_version"), source.version());
        String sourcePath = firstNonBlank(frontMatter.value("source_path"), source.path());
        KnowledgeContentKind contentKind = frontMatter.value("content_kind").isBlank()
                ? source.contentKind()
                : KnowledgeContentKind.parse(frontMatter.value("content_kind"));
        String sourceId = firstNonBlank(frontMatter.value("source_id"), source.sourceId());
        String collectionId = firstNonBlank(frontMatter.value("collection_id"), source.collectionId());
        String originType = firstNonBlank(frontMatter.value("origin_type"), source.originType());
        String metadataJson = firstNonBlank(frontMatter.value("metadata_json"), source.metadataJson());
        ScannedResource normalizedSource = new ScannedResource(
                sourceMod,
                source.modName(),
                sourceVersion,
                sourcePath,
                sourceType,
                source.content(),
                source.fingerprint(),
                source.translations(),
                contentKind,
                sourceId,
                collectionId,
                source.priority(),
                originType,
                metadataJson
        );

        return new KnowledgeDocument(
                id,
                sourceMod,
                sourceType,
                title,
                category,
                keywords,
                sourceVersion,
                sourcePath,
                frontMatter.toMarkdown(id, normalizedSource, title, category, keywords),
                contentKind,
                sourceId,
                collectionId,
                originType,
                metadataJson
        );
    }

    public KnowledgeDocument convertCustom(Path relativePath, String content) {
        FrontMatter frontMatter = FrontMatter.parse(content);
        String title = firstNonBlank(frontMatter.value("title"), firstHeading(frontMatter.body()), fileTitle(relativePath.toString()));
        String category = firstNonBlank(frontMatter.value("category"), "custom");
        List<String> keywords = mergeKeywords(
                frontMatter.keywords(),
                KeywordExtractor.extract(title, category, relativePath.toString()),
                List.of()
        );
        String id = frontMatter.value("id").trim();
        String sourceType = firstNonBlank(frontMatter.value("source_type"), "custom_markdown");
        KnowledgeContentKind contentKind = KnowledgeContentKind.parse(frontMatter.value("content_kind"));
        String sourceId = firstNonBlank(frontMatter.value("source_id"), "custom");
        String collectionId = firstNonBlank(frontMatter.value("collection_id"), "local-custom");
        int priority = frontMatter.intValue("priority", 100);
        ScannedResource source = new ScannedResource(
                "custom",
                "ModPedia custom",
                "local",
                relativePath.toString().replace('\\', '/'),
                sourceType,
                content,
                "local",
                Map.of(),
                contentKind,
                sourceId,
                collectionId,
                priority,
                "local_file",
                "{}"
        );
        return new KnowledgeDocument(
                id,
                source.modId(),
                source.sourceType(),
                title,
                category,
                keywords,
                source.version(),
                source.path(),
                // custom Markdown 是人工维护的事实源；保留原文（包括自定义
                // Front Matter 字段和 Markdown 格式），不要为了入库再次改写。
                content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n'),
                contentKind,
                source.sourceId(),
                source.collectionId(),
                source.originType(),
                source.metadataJson()
        );
    }

    private static List<String> mergeKeywords(
            List<String> first,
            List<String> second,
            List<String> third
    ) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(first);
        merged.addAll(second);
        merged.addAll(third);
        return List.copyOf(merged);
    }

    private static String documentId(ScannedResource source) {
        if (source.contentKind() == KnowledgeContentKind.WIKI) {
            String path = source.path().replace('\\', '/');
            int documentsMarker = path.indexOf("documents/");
            if (documentsMarker >= 0) {
                path = path.substring(documentsMarker + "documents/".length());
            }
            return "wiki:" + normalizeId(source.sourceId()) + "/" + normalizeId(removeExtension(path));
        }
        return source.modId() + ":" + normalizeId(removeExtension(documentSourcePath(source)));
    }

    private static String normalizeId(String value) {
        return value.replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("[^a-zA-Z0-9_:/.-]", "_");
    }

    private static String documentSourcePath(ScannedResource source) {
        String path = source.path().replace('\\', '/');
        String assetsPrefix = "assets/" + source.modId() + "/";
        String dataPrefix = "data/" + source.modId() + "/";
        if (path.startsWith(assetsPrefix)) {
            return path.substring(assetsPrefix.length());
        }
        if (path.startsWith(dataPrefix)) {
            return path.substring(dataPrefix.length());
        }
        return path;
    }

    private static String categoryOf(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/categories/")) {
            return "categories";
        }
        if (normalized.contains("/entries/")) {
            return "entries";
        }
        return "guide";
    }

    private static String firstHeading(String body) {
        for (String line : body.split("\\R")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "";
    }

    private static String fileTitle(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return removeExtension(fileName).replace('-', ' ').replace('_', ' ');
    }

    private static String removeExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "未命名文档";
    }

    private record FrontMatter(Map<String, String> values, List<String> keywords, String body, boolean valid) {
        static FrontMatter parse(String content) {
            String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
            if (!normalized.startsWith("---\n")) {
                return new FrontMatter(Map.of(), List.of(), normalized, false);
            }

            String[] lines = normalized.split("\n", -1);
            int end = -1;
            for (int index = 1; index < lines.length; index++) {
                if ("---".equals(lines[index].trim())) {
                    end = index;
                    break;
                }
            }
            if (end < 0) {
                return new FrontMatter(Map.of(), List.of(), normalized, false);
            }

            Map<String, String> values = new java.util.LinkedHashMap<>();
            List<String> keywords = new ArrayList<>();
            String listKey = null;
            for (int index = 1; index < end; index++) {
                String line = lines[index].trim();
                if (line.startsWith("- ") && "keywords".equals(listKey)) {
                    keywords.add(unquote(line.substring(2).trim()));
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = unquote(line.substring(colon + 1).trim());
                listKey = "keywords".equals(key) ? "keywords" : null;
                if ("keywords".equals(key)) {
                    if (value.startsWith("[") && value.endsWith("]")) {
                        for (String item : value.substring(1, value.length() - 1).split(",")) {
                            if (!item.isBlank()) {
                                keywords.add(unquote(item.trim()));
                            }
                        }
                    }
                } else {
                    values.put(key, value);
                }
            }
            StringBuilder body = new StringBuilder();
            for (int index = end + 1; index < lines.length; index++) {
                body.append(lines[index]);
                if (index + 1 < lines.length) {
                    body.append('\n');
                }
            }
            return new FrontMatter(values, keywords, body.toString().trim(), true);
        }

        String value(String key) {
            return values.getOrDefault(key, "");
        }

        int intValue(String key, int fallback) {
            try {
                return Integer.parseInt(value(key));
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }

        String toMarkdown(String id, ScannedResource source, String title, String category, List<String> keywords) {
            return toMarkdown(id, source, title, category, keywords, "");
        }

        String toMarkdown(
                String id,
                ScannedResource source,
                String title,
                String category,
                List<String> keywords,
                String language
        ) {
            StringBuilder result = new StringBuilder();
            result.append("---\n");
            result.append("id: ").append(escape(id)).append('\n');
            result.append("source_mod: ").append(escape(source.modId())).append('\n');
            result.append("source_type: ").append(escape(source.sourceType())).append('\n');
            result.append("title: ").append(escape(title)).append('\n');
            result.append("category: ").append(escape(category)).append('\n');
            result.append("keywords: [").append(String.join(", ", keywords.stream().map(FrontMatter::escape).toList())).append("]\n");
            if (language != null && !language.isBlank()) {
                result.append("language: ").append(escape(language)).append('\n');
            }
            result.append("source_version: ").append(escape(source.version())).append('\n');
            result.append("source_path: ").append(escape(source.path())).append('\n');
            result.append("content_kind: ").append(escape(source.contentKind().id())).append('\n');
            result.append("source_id: ").append(escape(source.sourceId())).append('\n');
            result.append("collection_id: ").append(escape(source.collectionId())).append('\n');
            result.append("origin_type: ").append(escape(source.originType())).append('\n');
            result.append("priority: ").append(source.priority()).append('\n');
            result.append("---\n\n");
            String bodyText = body();
            if (bodyText.isBlank()) {
                result.append("# ").append(title).append('\n');
            } else if (!bodyText.startsWith("# ")) {
                result.append("# ").append(title).append("\n\n").append(bodyText).append('\n');
            } else {
                result.append(bodyText).append('\n');
            }
            return result.toString();
        }

        private static String unquote(String value) {
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private static String escape(String value) {
            return "'" + value.replace("'", "''").replace("\n", " ").trim() + "'";
        }
    }
}
