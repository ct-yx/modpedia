package io.ctyx.modpedia.knowledge;

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

/** 将 JSON 手册页面转换成可检索的 Markdown，同时保留无法识别的原始结构。 */
public final class JsonGuideDocumentConverter implements GuideDocumentConverter {
    private static final GsonBuilder JSON = new GsonBuilder().setPrettyPrinting();

    @Override
    public List<KnowledgeDocument> convertAll(ScannedResource source) {
        return List.of(convert(source));
    }

    public KnowledgeDocument convert(ScannedResource source) {
        String title = fileTitle(source.path());
        String body;
        try {
            JsonElement root = JsonParser.parseString(source.content());
            title = firstText(root, source.translations(), "name", "title", "book_name", "category_name", "entry_name");
            body = renderRoot(root, source.translations());
        } catch (RuntimeException exception) {
            body = "解析失败，保留原始 JSON：\n\n```json\n" + source.content().trim() + "\n```";
        }

        Set<String> keywordSet = new LinkedHashSet<>(KeywordExtractor.extract(title, source.modId(), source.path(), body));
        keywordSet.addAll(LocalizedKeywordExtractor.extract(source));
        List<String> keywords = List.copyOf(keywordSet);
        String id = source.modId() + ":" + normalizeId(removeExtension(documentSourcePath(source)));
        String category = categoryOf(source.path());
        String markdown = frontMatter(id, source, title, category, keywords) + "\n" + body.trim() + "\n";
        return new KnowledgeDocument(id, source.modId(), source.sourceType(), title, category, keywords,
                source.version(), source.path(), markdown);
    }

    private String renderRoot(JsonElement root, Map<String, String> translations) {
        StringBuilder result = new StringBuilder();
        if (root.isJsonObject() && root.getAsJsonObject().has("pages")) {
            JsonArray pages = root.getAsJsonObject().getAsJsonArray("pages");
            for (int index = 0; index < pages.size(); index++) {
                result.append("## 页面 ").append(index + 1).append("\n\n");
                renderPage(pages.get(index), translations, result);
                result.append("\n\n");
            }
            return result.toString();
        }

        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (isMetadataKey(entry.getKey())) {
                    continue;
                }
                result.append("## ").append(displayKey(entry.getKey())).append("\n\n");
                renderValue(entry.getValue(), translations, result, 0);
                result.append("\n\n");
            }
        } else {
            renderValue(root, translations, result, 0);
        }
        return result.toString();
    }

    private void renderPage(JsonElement page, Map<String, String> translations, StringBuilder result) {
        if (page.isJsonPrimitive()) {
            result.append(resolve(page.getAsString(), translations));
            return;
        }
        if (!page.isJsonObject()) {
            renderValue(page, translations, result, 0);
            return;
        }

        JsonObject object = page.getAsJsonObject();
        String type = object.has("type") ? resolve(object.get("type").getAsString(), translations) : "";
        if (!type.isBlank()) {
            result.append("> 页面类型：").append(type).append("\n\n");
        }

        boolean rendered = false;
        for (String key : List.of("title", "header", "name", "text", "description", "recipe", "recipe2", "item", "items", "entity", "link")) {
            if (!object.has(key)) {
                continue;
            }
            if ("text".equals(key) || "description".equals(key)) {
                renderValue(object.get(key), translations, result, 0);
            } else {
                result.append("**").append(displayKey(key)).append("：** ");
                renderInline(object.get(key), translations, result);
                result.append("\n\n");
            }
            rendered = true;
        }

        if (!rendered) {
            result.append("```json\n").append(JSON.create().toJson(object)).append("\n```\n");
        }
    }

    private void renderValue(JsonElement value, Map<String, String> translations, StringBuilder result, int depth) {
        if (value.isJsonPrimitive()) {
            result.append(resolve(value.getAsString(), translations));
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
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                result.append("  ".repeat(Math.min(depth, 4))).append("- **")
                        .append(displayKey(entry.getKey())).append("：** ");
                renderInline(entry.getValue(), translations, result);
                result.append('\n');
            }
        }
    }

    private void renderInline(JsonElement value, Map<String, String> translations, StringBuilder result) {
        if (value.isJsonPrimitive()) {
            result.append(resolve(value.getAsString(), translations));
        } else {
            result.append(value.toString());
        }
    }

    private String firstText(JsonElement root, Map<String, String> translations, String... keys) {
        if (!root.isJsonObject()) {
            return "未命名手册页面";
        }
        JsonObject object = root.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                String value = resolve(object.get(key).getAsString(), translations).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "未命名手册页面";
    }

    private String resolve(String value, Map<String, String> translations) {
        String localized = translations.getOrDefault(value, value);
        return localized
                .replace("$(br)", "\n\n")
                .replace("$(br2)", "\n\n")
                .replace("$(li)", "- ");
    }

    private boolean isMetadataKey(String key) {
        return List.of("name", "title", "book_name", "category_name", "entry_name", "icon", "sortnum", "priority").contains(key);
    }

    private String displayKey(String key) {
        return key.replace('_', ' ');
    }

    private String frontMatter(String id, ScannedResource source, String title, String category, List<String> keywords) {
        return "---\n"
                + "id: " + quote(id) + "\n"
                + "source_mod: " + quote(source.modId()) + "\n"
                + "source_type: " + quote(source.sourceType()) + "\n"
                + "title: " + quote(title) + "\n"
                + "category: " + quote(category) + "\n"
                + "keywords: [" + String.join(", ", keywords.stream().map(this::quote).toList()) + "]\n"
                + "source_version: " + quote(source.version()) + "\n"
                + "source_path: " + quote(source.path()) + "\n"
                + "---\n";
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''").replace("\n", " ").trim() + "'";
    }

    private String documentSourcePath(ScannedResource source) {
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

    private String categoryOf(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/categories/")) {
            return "categories";
        }
        if (normalized.contains("/entries/")) {
            return "entries";
        }
        return "guide";
    }

    private String fileTitle(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return removeExtension(fileName).replace('-', ' ').replace('_', ' ');
    }

    private String removeExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String normalizeId(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "").replaceAll("[^a-zA-Z0-9_:/.-]", "_");
    }
}
