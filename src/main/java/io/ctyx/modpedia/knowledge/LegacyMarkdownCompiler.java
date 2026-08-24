package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 1.12.2 的来源资源转换成 Worker 可直接导入的 Markdown 来源。
 *
 * <p>转换产物是 {@code knowledge/sources/modpedia-legacy-*} 下的派生内容，
 * 原始 JAR、整合包作者的 Markdown 和 source.json 不会被删除。未知 JSON/XML
 * 节点同时保留可读字段和原始代码块，避免旧版页面类型被静默丢弃。</p>
 */
public final class LegacyMarkdownCompiler {
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<String>();
    private static final Pattern LANGUAGE_DIRECTORY = Pattern.compile("(?i)/(zh_cn|en_us|[a-z]{2}_[a-z]{2})/");

    static {
        Collections.addAll(TEXT_EXTENSIONS, ".json", ".md", ".markdown", ".txt", ".xml",
                ".lang", ".properties", ".cfg");
    }

    public CompileResult compile(Path instanceRoot, Path knowledgeRoot, ManualScanResult scanResult)
            throws IOException {
        if (instanceRoot == null || knowledgeRoot == null || scanResult == null) {
            return new CompileResult(0, 0, Collections.singletonList("Markdown 编译输入为空"));
        }
        Path sourcesRoot = knowledgeRoot.resolve("sources");
        Files.createDirectories(sourcesRoot);
        List<String> warnings = new ArrayList<String>();
        Set<String> currentDirectories = new HashSet<String>();
        int sourceCount = 0;
        int documentCount = 0;
        for (ManualSourceDescriptor source : scanResult.getSources()) {
            String directoryName = "modpedia-legacy-" + shortHash(
                    source.getSourceId() + "|" + source.getSourcePath()
            );
            currentDirectories.add(directoryName);
            try {
                List<RawResource> resources = readResources(instanceRoot, source, warnings);
                Path sourceDirectory = sourcesRoot.resolve(directoryName);
                writeSource(sourceDirectory, source, resources);
                sourceCount++;
                documentCount += Math.max(1, resources.size());
            } catch (IOException | RuntimeException exception) {
                warnings.add("Markdown 转换失败：" + source.getSourceId());
            }
        }
        removeObsoleteGeneratedSources(sourcesRoot, currentDirectories, warnings);
        writeState(knowledgeRoot.resolve("generated").resolve("legacy-markdown-state.json"),
                sourceCount, documentCount, warnings);
        return new CompileResult(sourceCount, documentCount, warnings);
    }

    private List<RawResource> readResources(
            Path instanceRoot,
            ManualSourceDescriptor source,
            List<String> warnings
    ) throws IOException {
        String sourcePath = source.getSourcePath().replace('\\', '/');
        int separator = sourcePath.indexOf("!/");
        if (separator >= 0) {
            Path archive = instanceRoot.resolve(sourcePath.substring(0, separator)).normalize();
            String root = sourcePath.substring(separator + 2);
            return readArchive(archive, root, source.getLanguage(), warnings);
        }
        Path root = instanceRoot.resolve(sourcePath).normalize();
        return readDirectory(root, source.getLanguage(), warnings);
    }

    private List<RawResource> readDirectory(
            Path root,
            String language,
            List<String> warnings
    ) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("来源目录不存在：" + root);
        }
        List<RawResource> result = new ArrayList<RawResource>();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.filter(Files::isRegularFile)
                    .filter(this::isText)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path path : paths) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!includeLanguage(relative, language)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length > 8L * 1024L * 1024L) {
                    warnings.add("跳过过大的手册文件：" + relative);
                    continue;
                }
                result.add(new RawResource(relative, new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    private List<RawResource> readArchive(
            Path archive,
            String root,
            String language,
            List<String> warnings
    ) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("来源 JAR 不存在：" + archive);
        }
        String normalizedRoot = root.replace('\\', '/').replaceAll("/+$", "");
        List<RawResource> result = new ArrayList<RawResource>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(normalizedRoot + "/")
                        || !isText(entry.getName())) {
                    continue;
                }
                String relative = entry.getName().substring(normalizedRoot.length() + 1);
                if (!includeLanguage(entry.getName(), language)) {
                    continue;
                }
                if (entry.getSize() > 8L * 1024L * 1024L) {
                    warnings.add("跳过过大的手册文件：" + entry.getName());
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    result.add(new RawResource(relative, readText(input, 8L * 1024L * 1024L)));
                }
            }
        }
        Collections.sort(result, new Comparator<RawResource>() {
            @Override
            public int compare(RawResource left, RawResource right) {
                return left.relativePath.compareTo(right.relativePath);
            }
        });
        return result;
    }

    private boolean includeLanguage(String path, String selectedLanguage) {
        String normalized = path.replace('\\', '/');
        java.util.regex.Matcher matcher = LANGUAGE_DIRECTORY.matcher("/" + normalized + "/");
        if (!matcher.find()) {
            return true;
        }
        String found = matcher.group(1).toLowerCase(Locale.ROOT);
        String selected = selectedLanguage == null ? "neutral" : selectedLanguage.toLowerCase(Locale.ROOT);
        return selected.equals("neutral") || found.equals(selected);
    }

    private void writeSource(
            Path sourceDirectory,
            ManualSourceDescriptor source,
            List<RawResource> resources
    ) throws IOException {
        Path documents = sourceDirectory.resolve("documents");
        Files.createDirectories(documents);
        writeJson(sourceDirectory.resolve("source.json"), sourceJson(source));
        if (resources.isEmpty()) {
            Path output = documents.resolve("index.md");
            Files.write(output, markdown(source, "index.json", "# " + source.getTitle() + "\n\n来源没有可读取的文本资源。")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        Set<String> used = new HashSet<String>();
        for (RawResource resource : resources) {
            String name = safePath(resource.relativePath);
            if (name.length() == 0) {
                name = "document";
            }
            if (!name.endsWith(".md")) {
                name = name + ".md";
            }
            String unique = name;
            int suffix = 2;
            while (!used.add(unique)) {
                unique = name.substring(0, name.length() - 3) + "-" + suffix++ + ".md";
            }
            Path output = documents.resolve(unique);
            Files.createDirectories(output.getParent());
            String body = convert(resource.relativePath, resource.content, source.getTitle());
            Files.write(output, markdown(source, resource.relativePath, body).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String sourceJson(ManualSourceDescriptor source) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"source_id\": \"").append(escape(source.getSourceId())).append("\",\n")
                .append("  \"collection_id\": \"").append(escape(source.getCollectionId())).append("\",\n")
                .append("  \"content_kind\": \"").append(source.getContentKind().getId()).append("\",\n")
                .append("  \"source_type\": \"wiki_markdown\",\n")
                .append("  \"origin_type\": \"").append(escape(source.getOriginType())).append("\",\n")
                .append("  \"title\": \"").append(escape(source.getTitle())).append("\",\n")
                .append("  \"language\": \"").append(escape(source.getLanguage())).append("\",\n")
                .append("  \"version\": \"").append(escape(source.getVersion())).append("\",\n")
                .append("  \"origin_uri\": \"").append(escape(source.getSourcePath())).append("\",\n")
                .append("  \"documents_root\": \"documents\",\n")
                .append("  \"priority\": 40,\n")
                .append("  \"metadata\": {\"legacy_source_type\": \"")
                .append(escape(source.getSourceType().getId())).append("\"}\n")
                .append("}\n");
        return json.toString();
    }

    private String convert(String path, String content, String sourceTitle) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return normalizeMarkdown(content, path);
        }
        if (lower.endsWith(".json")) {
            try {
                JsonElement element = new JsonParser().parse(content);
                return jsonMarkdown(path, element, sourceTitle);
            } catch (RuntimeException ignored) {
                return "# " + titleOf(path) + "\n\n```json\n" + content + "\n```";
            }
        }
        if (lower.endsWith(".xml")) {
            return "# " + titleOf(path) + "\n\n" + stripXml(content)
                    + "\n\n```xml\n" + content + "\n```";
        }
        return "# " + titleOf(path) + "\n\n```text\n" + content + "\n```";
    }

    private String jsonMarkdown(String path, JsonElement element, String sourceTitle) {
        if (!element.isJsonObject()) {
            return "# " + titleOf(path) + "\n\n```json\n" + element.toString() + "\n```";
        }
        JsonObject object = element.getAsJsonObject();
        String title = firstString(object, "name", "title", "text", titleOf(path));
        StringBuilder result = new StringBuilder("# ").append(clean(title)).append("\n\n");
        if (object.has("category")) {
            result.append("- 分类：").append(readable(object.get("category"))).append("\n");
        }
        if (object.has("icon")) {
            result.append("- 图标：").append(readable(object.get("icon"))).append("\n");
        }
        if (object.has("description")) {
            result.append("\n").append(readable(object.get("description"))).append("\n");
        }
        if (object.has("landing_text")) {
            result.append("\n").append(readable(object.get("landing_text"))).append("\n");
        }
        JsonElement pages = object.get("pages");
        if (pages != null && pages.isJsonArray()) {
            int page = 1;
            for (JsonElement pageElement : pages.getAsJsonArray()) {
                result.append("\n## 页面 ").append(page++);
                if (pageElement.isJsonObject()) {
                    JsonObject pageObject = pageElement.getAsJsonObject();
                    String type = firstString(pageObject, "type", "page");
                    result.append(" · ").append(type).append("\n\n");
                    appendPageFields(result, pageObject);
                } else {
                    result.append("\n\n").append(readable(pageElement)).append('\n');
                }
            }
        }
        appendKnownFields(result, object, "pages", "description", "landing_text", "name", "title", "category", "icon");
        result.append("\n<details><summary>原始节点</summary>\n\n```json\n")
                .append(element.toString()).append("\n```\n\n</details>\n");
        return result.toString();
    }

    private void appendPageFields(StringBuilder result, JsonObject page) {
        String[] common = {"text", "title", "link_text", "url", "recipe", "recipe2", "item", "entity", "anchor", "border", "path"};
        boolean wrote = false;
        for (String key : common) {
            if (page.has(key)) {
                result.append("**").append(key).append("**：");
                if ("recipe".equals(key) || "recipe2".equals(key)) {
                    result.append(recipeMarkup(page.get(key)));
                } else {
                    result.append(readable(page.get(key)));
                }
                result.append("\n\n");
                wrote = true;
            }
        }
        if (!wrote) {
            result.append("```json\n").append(page.toString()).append("\n```\n");
        }
    }

    /**
     * Patchouli/Guide 页面中的 recipe 是配方注册 ID，不是物品 ID。
     * 保留原显示文本，同时写入客户端可以识别的交互协议；这样不需要在
     * Worker 编译阶段加载 Minecraft 或 JEI，也能在客户端按需打开配方。
     */
    private String recipeMarkup(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            String value = clean(element.getAsString());
            if (!value.isEmpty() && value.indexOf(':') > 0) {
                return "[[recipe:" + value + "|" + value + "]]";
            }
        }
        return readable(element);
    }

    private void appendKnownFields(StringBuilder result, JsonObject object, String... ignored) {
        Set<String> excluded = new HashSet<String>();
        Collections.addAll(excluded, ignored);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!excluded.contains(entry.getKey())) {
                result.append("- ").append(entry.getKey()).append("：")
                        .append(readable(entry.getValue())).append('\n');
            }
        }
    }

    private String markdown(ManualSourceDescriptor source, String sourcePath, String body) {
        String id = "legacy:" + source.getSourceId() + ":" + sourcePath;
        StringBuilder result = new StringBuilder();
        result.append("---\n")
                .append("id: '").append(escapeYaml(id)).append("'\n")
                .append("title: '").append(escapeYaml(titleOf(sourcePath))).append("'\n")
                .append("source_mod: '").append(escapeYaml(source.getSourceId())).append("'\n")
                .append("source_type: 'wiki_markdown'\n")
                .append("source_path: '").append(escapeYaml(source.getSourcePath() + "!/" + sourcePath)).append("'\n")
                .append("source_version: '").append(escapeYaml(source.getVersion())).append("'\n")
                .append("language: '").append(escapeYaml(source.getLanguage())).append("'\n")
                .append("content_kind: '").append(source.getContentKind().getId()).append("'\n")
                .append("source_id: '").append(escapeYaml(source.getSourceId())).append("'\n")
                .append("collection_id: '").append(escapeYaml(source.getCollectionId())).append("'\n")
                .append("origin_type: '").append(escapeYaml(source.getOriginType())).append("'\n")
                .append("priority: 40\n---\n\n")
                .append(body == null ? "" : body.trim()).append('\n');
        return result.toString();
    }

    private String normalizeMarkdown(String content, String path) {
        String value = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
        return value.startsWith("#") ? value : "# " + titleOf(path) + "\n\n" + value;
    }

    private String readable(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return clean(element.getAsString());
        }
        if (element.isJsonArray()) {
            List<String> values = new ArrayList<String>();
            for (JsonElement child : element.getAsJsonArray()) {
                values.add(readable(child));
            }
            return String.join("、", values);
        }
        return element.toString();
    }

    private String stripXml(String content) {
        return content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String firstString(JsonObject object, String first, String second, String third, String fallback) {
        String[] keys = {first, second, third};
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                String value = object.get(key).getAsString();
                if (!value.trim().isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private String firstString(JsonObject object, String first, String second) {
        return firstString(object, first, second, "", "");
    }

    private String titleOf(String path) {
        String normalized = path == null ? "document" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ').replace('-', ' ');
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("§.", "").trim();
    }

    private boolean isText(Path path) {
        return isText(path.getFileName().toString());
    }

    private boolean isText(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : TEXT_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String safePath(String path) {
        String normalized = path == null ? "document" : path.replace('\\', '/');
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '.' || current == '_' || current == '-' || current == '/') {
                result.append(current);
            } else {
                result.append('_');
            }
        }
        return result.toString().replaceAll("^/+", "");
    }

    private String escapeYaml(String value) {
        return (value == null ? "" : value).replace("'", "''").replace("\n", " ").replace("\r", " ");
    }

    private String escape(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void writeJson(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private void removeObsoleteGeneratedSources(Path root, Set<String> current, List<String> warnings) {
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> directories = stream.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path directory : directories) {
                String name = directory.getFileName().toString();
                if (name.startsWith("modpedia-legacy-") && !current.contains(name)) {
                    deleteTree(directory);
                }
            }
        } catch (IOException exception) {
            warnings.add("清理旧 Markdown 来源失败");
        }
    }

    private void writeState(Path path, int sources, int documents, List<String> warnings) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder json = new StringBuilder("{\n  \"schema\": 1,\n  \"sources\": ")
                .append(sources).append(",\n  \"documents\": ").append(documents)
                .append(",\n  \"warnings\": ").append(warnings.size()).append("\n}\n");
        Files.write(path, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("文本资源超过大小限制");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String shortHash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static final class RawResource {
        private final String relativePath;
        private final String content;

        private RawResource(String relativePath, String content) {
            this.relativePath = relativePath;
            this.content = content;
        }
    }

    public static final class CompileResult {
        private final int sourceCount;
        private final int documentCount;
        private final List<String> warnings;

        public CompileResult(int sourceCount, int documentCount, List<String> warnings) {
            this.sourceCount = sourceCount;
            this.documentCount = documentCount;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        }

        public int getSourceCount() {
            return sourceCount;
        }

        public int getDocumentCount() {
            return documentCount;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
