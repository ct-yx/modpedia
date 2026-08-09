package io.ctyx.modpedia.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * APP 书籍 JSON 转换器。
 *
 * <p>手册框架本身只是运行时依赖，实际内容来自安装包中其他模组的资源。本转换器
 * 只处理扫描器已经判定为 {@code app_json} 的资源，并把资源中的 entries 展开成
 * 独立文档；未知页面节点以 JSON 代码块保留。</p>
 */
public final class AppGuideDocumentConverter implements GuideDocumentConverter {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> ENTRY_KEYS = List.of("entries", "entry_list", "entryList");
    private static final List<String> TITLE_KEYS = List.of(
            "title", "name", "book_name", "category_name", "entry_name", "display_name"
    );
    private static final Set<String> METADATA_KEYS = Set.of(
            "id", "book_id", "book", "category", "category_id", "entry_id", "name", "title",
            "book_name", "category_name", "entry_name", "display_name", "sortnum", "sort_index",
            "priority", "icon", "display", "parent", "entries", "entry_list", "entryList"
    );
    private static final Set<String> PAGE_CONTENT_KEYS = Set.of(
            "text", "description", "content", "recipe", "recipe2", "recipe_id", "recipe_id_1",
            "recipe_id_2", "recipe_id_3", "item", "items", "entity", "link", "title", "header",
            "multiblock_id", "multiblock_name", "images", "image", "show_visualize_button",
            "show_title_separator", "use_markdown_in_title"
    );
    private static final Set<String> PAGE_METADATA_KEYS = Set.of(
            "type", "page_type", "condition", "anchor"
    );

    @Override
    public List<KnowledgeDocument> convertAll(ScannedResource source) {
        JsonElement root;
        try {
            root = JsonParser.parseString(source.content());
        } catch (RuntimeException exception) {
            return List.of(singleDocument(
                    source,
                    null,
                    "",
                    "",
                    "解析失败，保留原始 JSON：\n\n```json\n" + source.content().trim() + "\n```"
            ));
        }

        List<LogicalEntry> entries = logicalEntries(root, source.path());
        List<KnowledgeDocument> documents = new ArrayList<>(entries.size());
        for (LogicalEntry entry : entries) {
            documents.add(convertEntry(source, root, entry));
        }
        return List.copyOf(documents);
    }

    private KnowledgeDocument convertEntry(ScannedResource source, JsonElement root, LogicalEntry entry) {
        JsonElement content = entry.content();
        String bookId = idSegment(firstText(root, source.translations(), "book_id", "book"));
        if (bookId.isBlank()) {
            bookId = pathId(source.path(), "books");
        }
        if (bookId.isBlank()) {
            bookId = fileStem(source.path());
        }

        String categoryId = idSegment(firstText(content, source.translations(), "category_id", "category"));
        if (categoryId.isBlank()) {
            categoryId = idSegment(firstText(root, source.translations(), "category_id", "category"));
        }
        if (categoryId.isBlank()) {
            categoryId = categoryPathId(source.path());
        }

        String entryId = idSegment(entry.entryId());
        if (entryId.isBlank()) {
            entryId = idSegment(firstText(content, source.translations(), "entry_id", "id"));
        }
        if (entryId.isBlank()) {
            entryId = entryPathId(source.path());
        }
        if (entryId.isBlank()) {
            entryId = fileStem(source.path());
        }

        boolean bookResource = isBookResource(source.path());
        boolean categoryResource = isCategoryResource(source.path());
        if (bookResource) {
            categoryId = "general";
            entryId = "__book";
        } else if (categoryResource) {
            entryId = "__category";
        }

        String title = firstText(content, source.translations(), TITLE_KEYS.toArray(String[]::new));
        if (title.isBlank()) {
            title = firstText(root, source.translations(), TITLE_KEYS.toArray(String[]::new));
        }
        if (title.isBlank()) {
            title = entryId.replace('_', ' ').replace('-', ' ');
        }

        String body = renderEntry(root, content, source.translations(), title);
        String documentId = source.modId() + ":app/"
                + normalizeId(bookId) + "/"
                + normalizeId(categoryId.isBlank() ? "general" : categoryId) + "/"
                + normalizeId(entryId);
        String sourcePath = navigationPath(
                source.path(),
                bookId,
                categoryId,
                bookResource || categoryResource ? "" : entryId
        );
        Set<String> keywordSet = new LinkedHashSet<>(KeywordExtractor.extract(
                title,
                source.modId(),
                sourcePath,
                body
        ));
        keywordSet.addAll(LocalizedKeywordExtractor.extract(source));
        keywordSet.add(bookId);
        if (!categoryId.isBlank()) {
            keywordSet.add(categoryId);
        }
        keywordSet.add(entryId);
        String markdown = frontMatter(documentId, source, title, categoryId, sourcePath, List.copyOf(keywordSet))
                + "\n" + body.trim() + "\n";
        return new KnowledgeDocument(
                documentId,
                source.modId(),
                source.sourceType(),
                title,
                categoryId.isBlank() ? "general" : categoryId,
                List.copyOf(keywordSet),
                source.version(),
                sourcePath,
                markdown
        );
    }

    private KnowledgeDocument singleDocument(
            ScannedResource source,
            JsonElement root,
            String bookId,
            String entryId,
            String body
    ) {
        String title = root == null ? fileStem(source.path()) : firstText(root, source.translations(), TITLE_KEYS.toArray(String[]::new));
        if (title.isBlank()) {
            title = fileStem(source.path());
        }
        if (bookId.isBlank()) {
            bookId = pathId(source.path(), "books");
        }
        if (bookId.isBlank()) {
            bookId = fileStem(source.path());
        }
        if (entryId.isBlank()) {
            entryId = entryPathId(source.path());
        }
        String sourcePath = navigationPath(source.path(), bookId, "", entryId);
        String id = source.modId() + ":app/" + normalizeId(bookId) + "/general/" + normalizeId(entryId);
        List<String> keywords = KeywordExtractor.extract(title, source.modId(), sourcePath, body);
        return new KnowledgeDocument(
                id,
                source.modId(),
                source.sourceType(),
                title,
                "general",
                keywords,
                source.version(),
                sourcePath,
                frontMatter(id, source, title, "general", sourcePath, keywords) + "\n" + body.trim() + "\n"
        );
    }

    private List<LogicalEntry> logicalEntries(JsonElement root, String sourcePath) {
        if (!root.isJsonObject()) {
            return List.of(new LogicalEntry("", root));
        }
        JsonObject object = root.getAsJsonObject();
        for (String key : ENTRY_KEYS) {
            JsonElement entries = object.get(key);
            if (entries == null) {
                continue;
            }
            if (entries.isJsonArray()) {
                List<LogicalEntry> result = new ArrayList<>();
                JsonArray array = entries.getAsJsonArray();
                for (int index = 0; index < array.size(); index++) {
                    JsonElement item = array.get(index);
                    String id = item.isJsonObject()
                            ? firstText(item, Map.of(), "entry_id", "id", "name", "title")
                            : "entry_" + (index + 1);
                    result.add(new LogicalEntry(id, item));
                }
                return result.isEmpty() ? List.of(new LogicalEntry("", root)) : result;
            }
            if (entries.isJsonObject()) {
                List<LogicalEntry> result = new ArrayList<>();
                for (Map.Entry<String, JsonElement> entry : entries.getAsJsonObject().entrySet()) {
                    result.add(new LogicalEntry(entry.getKey(), entry.getValue()));
                }
                return result.isEmpty() ? List.of(new LogicalEntry("", root)) : result;
            }
        }
        return List.of(new LogicalEntry("", root));
    }

    private String renderEntry(JsonElement root, JsonElement content, Map<String, String> translations, String title) {
        StringBuilder result = new StringBuilder("# ").append(title).append("\n\n");
        if (content != root) {
            renderObjectContent(content, translations, result, 0);
            return result.toString();
        }
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("pages")) {
                renderPages(object.get("pages"), translations, result);
            }
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                if (METADATA_KEYS.contains(field.getKey()) || "pages".equals(field.getKey())) {
                    continue;
                }
                result.append("## ").append(displayKey(field.getKey())).append("\n\n");
                renderValue(field.getValue(), translations, result, 0);
                result.append("\n");
            }
            if (result.toString().equals("# " + title + "\n\n")) {
                result.append(rawJson(root)).append('\n');
            }
        } else {
            renderValue(root, translations, result, 0);
        }
        return result.toString();
    }

    private void renderObjectContent(JsonElement content, Map<String, String> translations, StringBuilder result, int depth) {
        if (!content.isJsonObject()) {
            renderValue(content, translations, result, depth);
            return;
        }
        JsonObject object = content.getAsJsonObject();
        if (object.has("pages")) {
            renderPages(object.get("pages"), translations, result);
        }
        boolean rendered = object.has("pages");
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            if (METADATA_KEYS.contains(field.getKey()) || "pages".equals(field.getKey())) {
                continue;
            }
            result.append("## ").append(displayKey(field.getKey())).append("\n\n");
            renderValue(field.getValue(), translations, result, depth);
            result.append("\n");
            rendered = true;
        }
        if (!rendered) {
            result.append(rawJson(content)).append('\n');
        }
    }

    private void renderPages(JsonElement pages, Map<String, String> translations, StringBuilder result) {
        if (!pages.isJsonArray()) {
            result.append("## 页面\n\n");
            renderValue(pages, translations, result, 0);
            result.append('\n');
            return;
        }
        JsonArray array = pages.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            result.append("## 页面 ").append(index + 1).append("\n\n");
            JsonElement page = array.get(index);
            if (page.isJsonObject()) {
                JsonObject object = page.getAsJsonObject();
                String type = firstText(page, translations, "type", "page_type");
                if (!type.isBlank()) {
                    result.append("> 页面类型：").append(type).append("\n\n");
                }
                Set<String> renderedKeys = new LinkedHashSet<>(PAGE_METADATA_KEYS);
                boolean rendered = false;
                for (String key : List.of("text", "description", "content", "recipe", "recipe2", "item", "items", "entity", "link", "title", "header")) {
                    if (!object.has(key)) {
                        continue;
                    }
                    renderedKeys.add(key);
                    if ("text".equals(key) || "description".equals(key) || "content".equals(key)) {
                        renderValue(object.get(key), translations, result, 0);
                    } else {
                        result.append("**").append(displayKey(key)).append("：** ");
                        renderInline(object.get(key), translations, result);
                        result.append("\n\n");
                    }
                    rendered = true;
                }
                for (String key : PAGE_CONTENT_KEYS) {
                    if (!object.has(key) || renderedKeys.contains(key)) {
                        continue;
                    }
                    renderedKeys.add(key);
                    result.append("**").append(displayKey(key)).append("：** ");
                    renderInline(object.get(key), translations, result);
                    result.append("\n\n");
                    rendered = true;
                }

                JsonObject unknown = new JsonObject();
                for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                    if (!renderedKeys.contains(field.getKey())) {
                        unknown.add(field.getKey(), field.getValue());
                    }
                }
                if (unknown.size() > 0) {
                    result.append("### 原始页面字段\n\n").append(rawJson(unknown)).append('\n');
                } else if (!rendered) {
                    result.append(rawJson(page)).append('\n');
                }
            } else {
                renderValue(page, translations, result, 0);
            }
        }
    }

    private void renderValue(JsonElement value, Map<String, String> translations, StringBuilder result, int depth) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonPrimitive()) {
            result.append(resolve(value.getAsString(), translations)).append('\n');
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                result.append("- ");
                renderInline(item, translations, result);
                result.append('\n');
            }
            return;
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
                result.append("- **").append(displayKey(field.getKey())).append("：** ");
                renderInline(field.getValue(), translations, result);
                result.append('\n');
            }
            return;
        }
        result.append(rawJson(value)).append('\n');
    }

    private void renderInline(JsonElement value, Map<String, String> translations, StringBuilder result) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonPrimitive()) {
            result.append(resolve(value.getAsString(), translations));
        } else {
            result.append(rawJson(value));
        }
    }

    private String firstText(JsonElement value, Map<String, String> translations, String... keys) {
        if (value == null || !value.isJsonObject()) {
            return "";
        }
        JsonObject object = value.getAsJsonObject();
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                String resolved = resolve(element.getAsString(), translations).trim();
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        return "";
    }

    private String resolve(String value, Map<String, String> translations) {
        if (value == null) {
            return "";
        }
        String result = value;
        String translated = translations.get(result);
        if (translated != null && !translated.isBlank()) {
            result = translated;
        }
        return result.replace("$(br)", "\n\n").replace("$(br2)", "\n\n").replace("$(li)", "- ");
    }

    private String rawJson(JsonElement value) {
        return "```json\n" + JSON.toJson(value == null ? JsonParser.parseString("null") : value) + "\n```";
    }

    private String navigationPath(String path, String book, String category, String entry) {
        StringBuilder result = new StringBuilder(path).append("#book=").append(anchor(book));
        if (!category.isBlank()) {
            result.append("&category=").append(anchor(category));
        }
        if (!entry.isBlank()) {
            result.append("&entry=").append(anchor(entry));
        }
        return result.toString();
    }

    private String frontMatter(String id, ScannedResource source, String title, String category, String sourcePath, List<String> keywords) {
        return "---\n"
                + "id: " + quote(id) + "\n"
                + "source_mod: " + quote(source.modId()) + "\n"
                + "source_type: " + quote(source.sourceType()) + "\n"
                + "title: " + quote(title) + "\n"
                + "category: " + quote(category) + "\n"
                + "keywords: [" + String.join(", ", keywords.stream().map(this::quote).toList()) + "]\n"
                + "source_version: " + quote(source.version()) + "\n"
                + "source_path: " + quote(sourcePath) + "\n"
                + "---\n";
    }

    private String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''").replace("\n", " ").trim()) + "'";
    }

    private String pathId(String path, String segment) {
        String[] parts = path.replace('\\', '/').split("/");
        for (int index = 0; index + 1 < parts.length; index++) {
            if (segment.equalsIgnoreCase(parts[index])) {
                return idSegment(removeExtension(parts[index + 1]));
            }
        }
        return "";
    }

    private String categoryPathId(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        for (int index = 0; index + 1 < parts.length; index++) {
            if ("entries".equalsIgnoreCase(parts[index])) {
                return idSegment(parts[index + 1]);
            }
            if ("categories".equalsIgnoreCase(parts[index])) {
                return idSegment(removeExtension(parts[index + 1]));
            }
        }
        return "";
    }

    private String entryPathId(String path) {
        return idSegment(fileStem(path));
    }

    private boolean isBookResource(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.matches(".*?/modonomicon/books/[^/]+/book\\.json$");
    }

    private boolean isCategoryResource(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/modonomicon/books/") && normalized.contains("/categories/");
    }

    /** Resource IDs may be written as namespace:path or category/path. Keep the local leaf. */
    private String idSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(colon + 1);
        }
        return normalizeId(normalized);
    }

    private String fileStem(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return removeExtension(slash >= 0 ? normalized.substring(slash + 1) : normalized);
    }

    private String removeExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String normalizeId(String value) {
        return (value == null ? "" : value).replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("[^a-zA-Z0-9_:/.-]", "_");
    }

    private String anchor(String value) {
        return normalizeId(value).replace("&", "_").replace("#", "_").replace("=", "_");
    }

    private String displayKey(String key) {
        return key.replace('_', ' ');
    }

    private String fileTitle(String path) {
        return fileStem(path).replace('-', ' ').replace('_', ' ');
    }

    private record LogicalEntry(String entryId, JsonElement content) {
    }
}
