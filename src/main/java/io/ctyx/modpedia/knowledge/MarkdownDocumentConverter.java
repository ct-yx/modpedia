package io.ctyx.modpedia.knowledge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将 Markdown 来源整理为 ModPedia 的统一文档格式。 */
public final class MarkdownDocumentConverter {
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
                KeywordExtractor.extract(title, category, source.modId(), source.path())
        );
        String id = firstNonBlank(frontMatter.value("id"), documentId(source));

        return new KnowledgeDocument(
                id,
                source.modId(),
                source.sourceType(),
                title,
                category,
                keywords,
                source.version(),
                source.path(),
                frontMatter.toMarkdown(id, source, title, category, keywords)
        );
    }

    public KnowledgeDocument convertCustom(Path relativePath, String content) {
        FrontMatter frontMatter = FrontMatter.parse(content);
        String title = firstNonBlank(frontMatter.value("title"), firstHeading(frontMatter.body()), fileTitle(relativePath.toString()));
        String category = firstNonBlank(frontMatter.value("category"), "custom");
        List<String> keywords = mergeKeywords(
                frontMatter.keywords(),
                KeywordExtractor.extract(title, category, relativePath.toString())
        );
        String id = firstNonBlank(frontMatter.value("id"), "custom:" + normalizeId(relativePath.toString()));
        ScannedResource source = new ScannedResource(
                "custom",
                "ModPedia custom",
                "local",
                relativePath.toString().replace('\\', '/'),
                "custom_markdown",
                content,
                "local",
                Map.of()
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
                frontMatter.toMarkdown(id, source, title, category, keywords)
        );
    }

    private static List<String> mergeKeywords(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    private static String documentId(ScannedResource source) {
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

    private record FrontMatter(Map<String, String> values, List<String> keywords, String body) {
        static FrontMatter parse(String content) {
            String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
            if (!normalized.startsWith("---\n")) {
                return new FrontMatter(Map.of(), List.of(), normalized);
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
                return new FrontMatter(Map.of(), List.of(), normalized);
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
            return new FrontMatter(values, keywords, body.toString().trim());
        }

        String value(String key) {
            return values.getOrDefault(key, "");
        }

        String toMarkdown(String id, ScannedResource source, String title, String category, List<String> keywords) {
            StringBuilder result = new StringBuilder();
            result.append("---\n");
            result.append("id: ").append(escape(id)).append('\n');
            result.append("source_mod: ").append(escape(source.modId())).append('\n');
            result.append("source_type: ").append(escape(source.sourceType())).append('\n');
            result.append("title: ").append(escape(title)).append('\n');
            result.append("category: ").append(escape(category)).append('\n');
            result.append("keywords: [").append(String.join(", ", keywords.stream().map(FrontMatter::escape).toList())).append("]\n");
            result.append("source_version: ").append(escape(source.version())).append('\n');
            result.append("source_path: ").append(escape(source.path())).append('\n');
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
