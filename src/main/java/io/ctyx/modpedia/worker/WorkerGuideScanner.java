package io.ctyx.modpedia.worker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.KnowledgeContentKind;
import io.ctyx.modpedia.knowledge.GuideLocaleSelector;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Worker 专用的纯 JDK JAR 资源扫描器。
 *
 * <p>它不加载 NeoForge/ Minecraft 类，因此可以在独立 Worker JVM 中扫描已安装
 * mods 目录；知识转换、Markdown 和 SQLite 也随之留在 Worker。</p>
 */
public final class WorkerGuideScanner {
    private static final long MAX_TEXT_FILE_SIZE = 8L * 1024L * 1024L;
    private static final int MAX_MODS_SCAN_DEPTH = 3;
    private static final Pattern LANGUAGE_PATH = Pattern.compile(
            "^assets/([^/]+)/lang/(zh_cn|en_us)\\.json$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_LOCALE_PATH = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(zh_cn|en_us|[a-z]{2}_[a-z]{2})/(.+\\.(json|md))$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_PATH = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(.+\\.(json|md))$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MOD_ID = Pattern.compile("(?m)\\bmodId\\s*=\\s*\\\"([^\"]+)\\\"");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)\\bdisplayName\\s*=\\s*\\\"([^\"]+)\\\"");
    private static final Pattern VERSION = Pattern.compile("(?m)\\bversion\\s*=\\s*\\\"([^\"]+)\\\"");
    private static final Set<String> FRAMEWORK_IDS = Set.of("patchouli", "guideme", "modonomicon");

    public ScanResult scan(Path modsDirectory, Path knowledgeRoot) throws IOException {
        List<RawResource> raw = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, SourceClassification> overrides = loadOverrides(knowledgeRoot, warnings);
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            return new ScanResult(List.of(), List.of("Worker mods 目录不存在：" + modsDirectory));
        }
        List<Path> archives = archiveFiles(modsDirectory);
        if (archives.isEmpty()) {
            warnings.add("Worker mods 目录中没有可扫描的 JAR/ZIP：" + modsDirectory);
        }
        for (Path file : archives) {
            scanArchive(file, overrides, raw, warnings);
        }
        Map<String, Map<String, String>> translations = loadTranslations(raw, warnings);
        List<ScannedResource> result = raw.stream()
                .filter(resource -> resource.sourceType() != null && !resource.sourceType().isBlank())
                .map(resource -> new ScannedResource(
                        resource.namespace(),
                        resource.metadata().name(),
                        resource.metadata().version(),
                        resource.path(),
                        resource.sourceType(),
                        resource.content(),
                        resource.fingerprint(),
                        translations.getOrDefault(resource.namespace(), Map.of()),
                        resource.classification().contentKind(),
                        resource.classification().sourceId(),
                        resource.classification().collectionId(),
                        resource.classification().priority(),
                        resource.classification().originType(),
                        resource.classification().metadataJson()
                ))
                .sorted(Comparator.comparing(ScannedResource::modId).thenComparing(ScannedResource::path))
                .toList();
        return new ScanResult(result, warnings);
    }

    /**
     * 返回实例 mods 目录中的模组压缩包。
     *
     * <p>常规实例把 JAR 直接放在 {@code mods/}，但部分启动器会按版本或
     * profile 再套一层目录。Worker 不应因为这种目录布局差异静默得到空扫描，
     * 因此这里允许有限深度递归，并让路径收集与实际扫描使用同一套规则。</p>
     */
    public static List<Path> archiveFiles(Path modsDirectory) throws IOException {
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(modsDirectory, MAX_MODS_SCAN_DEPTH)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(WorkerGuideScanner::isArchive)
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private void scanArchive(
            Path archive,
            Map<String, SourceClassification> overrides,
            List<RawResource> raw,
            List<String> warnings
    ) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            SourceMetadata defaultMetadata = metadata(zip, archive.getFileName().toString());
            List<String> paths = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .filter(this::isPotentialCandidate)
                    .sorted()
                    .toList();
            Map<String, String> selectedLocales = selectPatchouliLocales(paths);
            Set<String> selectedGuidePaths = GuideLocaleSelector.select(paths);
            Map<String, SourceClassification> classifications = classifications(
                    zip, paths, defaultMetadata, overrides, warnings
            );
            for (String path : paths) {
                if (!isCandidate(path, selectedLocales, selectedGuidePaths)) {
                    continue;
                }
                readResource(zip, path, defaultMetadata, classifications, raw, warnings);
            }
        } catch (IOException | RuntimeException exception) {
            warnings.add("Worker 扫描模组 JAR 失败：" + archive.getFileName() + "（"
                    + exception.getClass().getSimpleName() + "）");
        }
    }

    private SourceMetadata metadata(ZipFile zip, String fileName) {
        for (String path : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
            String text = readText(zip, path).orElse("");
            if (text.isBlank()) {
                continue;
            }
            String id = first(MOD_ID, text, safeName(fileName));
            String display = first(DISPLAY_NAME, text, id);
            String version = first(VERSION, text, "unknown");
            return new SourceMetadata(display, version, Set.of(id));
        }
        return new SourceMetadata(safeName(fileName), "unknown", Set.of());
    }

    private String first(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && !matcher.group(1).isBlank() ? matcher.group(1).strip() : fallback;
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".jar") || name.endsWith(".zip"));
    }

    private boolean isPotentialCandidate(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("assets/") && !lower.startsWith("data/")) {
            return false;
        }
        if (LANGUAGE_PATH.matcher(normalized).matches()) {
            return true;
        }
        return (lower.contains("/patchouli_books/")
                || lower.contains("/guideme_guides/")
                || lower.contains("/guides/")
                || lower.contains("/ae2guide/")
                || lower.contains("/modonomicon/books/"))
                && (lower.endsWith(".json") || lower.endsWith(".md"));
    }

    private boolean isCandidate(
            String path,
            Map<String, String> selectedLocales,
            Set<String> selectedGuidePaths
    ) {
        if (LANGUAGE_PATH.matcher(path).matches()) {
            return true;
        }
        if (!GuideLocaleSelector.shouldInclude(path, selectedGuidePaths)) {
            return false;
        }
        Matcher matcher = PATCHOULI_LOCALE_PATH.matcher(path);
        if (matcher.matches()) {
            return matcher.group(4).equalsIgnoreCase(selectedLocales.get(patchouliBookKey(matcher)));
        }
        return true;
    }

    private Map<String, String> selectPatchouliLocales(List<String> paths) {
        Map<String, List<String>> available = new HashMap<>();
        for (String path : paths) {
            Matcher matcher = PATCHOULI_LOCALE_PATH.matcher(path);
            if (matcher.matches()) {
                available.computeIfAbsent(patchouliBookKey(matcher), ignored -> new ArrayList<>())
                        .add(matcher.group(4).toLowerCase(Locale.ROOT));
            }
        }
        Map<String, String> selected = new HashMap<>();
        available.forEach((key, locales) -> {
            if (locales.contains("zh_cn")) {
                selected.put(key, "zh_cn");
            } else if (locales.contains("en_us")) {
                selected.put(key, "en_us");
            } else if (!locales.isEmpty()) {
                selected.put(key, locales.stream().sorted().findFirst().orElse(""));
            }
        });
        return selected;
    }

    private String patchouliBookKey(Matcher matcher) {
        return matcher.group(1).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(2).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(3).toLowerCase(Locale.ROOT);
    }

    private Map<String, SourceClassification> classifications(
            ZipFile zip,
            List<String> paths,
            SourceMetadata metadata,
            Map<String, SourceClassification> overrides,
            List<String> warnings
    ) {
        Map<String, SourceClassification> result = new HashMap<>();
        for (String path : paths) {
            String key = classificationKey(path);
            if (key == null || result.containsKey(key)) {
                continue;
            }
            String namespace = namespaceOf(path);
            SourceClassification classification = SourceClassification.defaultFor(namespace);
            String sourcePath = sourceResourcePath(path);
            if (sourcePath != null) {
                classification = readClassification(zip, sourcePath, classification, namespace, warnings);
            }
            classification = merge(classification, overrides.get(key));
            String rootPath = rootResourcePath(path);
            if (rootPath != null) {
                String root = readText(zip, rootPath).orElse("");
                if (!root.isBlank()) {
                    try {
                        classification = classificationFromJson(
                                JsonParser.parseString(root), classification, namespace
                        );
                    } catch (RuntimeException exception) {
                        warnings.add("Worker 解析书籍分类失败：" + rootPath);
                    }
                }
            }
            result.put(key, classification);
        }
        return result;
    }

    private void readResource(
            ZipFile zip,
            String path,
            SourceMetadata metadata,
            Map<String, SourceClassification> classifications,
            List<RawResource> raw,
            List<String> warnings
    ) {
        String namespace = namespaceOf(path);
        if (namespace == null || isFrameworkNamespace(namespace, metadata)) {
            return;
        }
        try {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.getSize() > MAX_TEXT_FILE_SIZE) {
                warnings.add("Worker 跳过过大的知识文件：" + path);
                return;
            }
            byte[] bytes;
            try (InputStream stream = zip.getInputStream(entry)) {
                bytes = readLimited(stream, MAX_TEXT_FILE_SIZE);
            }
            if (bytes == null) {
                warnings.add("Worker 跳过过大的知识文件：" + path);
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String sourceType = sourceTypeOf(path);
            if (sourceType == null && !LANGUAGE_PATH.matcher(path).matches()) {
                return;
            }
            raw.add(new RawResource(
                    namespace,
                    path,
                    sourceType,
                    content,
                    sha256(bytes),
                    metadata,
                    classifications.getOrDefault(
                            classificationKey(path),
                            SourceClassification.defaultFor(namespace)
                    )
            ));
        } catch (IOException | RuntimeException exception) {
            warnings.add("Worker 读取知识文件失败：" + path);
        }
    }

    private Map<String, Map<String, String>> loadTranslations(
            List<RawResource> resources,
            List<String> warnings
    ) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (RawResource resource : resources) {
            Matcher matcher = LANGUAGE_PATH.matcher(resource.path());
            if (!matcher.matches()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(resource.content());
                if (!parsed.isJsonObject()) {
                    continue;
                }
                Map<String, String> values = result.computeIfAbsent(
                        matcher.group(1), ignored -> new LinkedHashMap<>()
                );
                flatten(parsed.getAsJsonObject(), "", values);
            } catch (RuntimeException exception) {
                warnings.add("Worker 解析语言文件失败：" + resource.path());
            }
        }
        return result;
    }

    private void flatten(JsonObject object, String prefix, Map<String, String> target) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                target.put(key, value.getAsString());
            } else if (value.isJsonObject()) {
                flatten(value.getAsJsonObject(), key, target);
            }
        }
    }

    private SourceClassification readClassification(
            ZipFile zip,
            String path,
            SourceClassification fallback,
            String namespace,
            List<String> warnings
    ) {
        String content = readText(zip, path).orElse("");
        if (content.isBlank()) {
            return fallback;
        }
        try {
            return classificationFromJson(JsonParser.parseString(content), fallback, namespace);
        } catch (RuntimeException exception) {
            warnings.add("Worker 解析 source.json 失败：" + path);
            return fallback;
        }
    }

    private SourceClassification classificationFromJson(
            JsonElement parsed,
            SourceClassification fallback,
            String namespace
    ) {
        if (parsed == null || !parsed.isJsonObject()) {
            return fallback;
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonElement knowledgeValue = object.get("knowledge");
        JsonObject knowledge = knowledgeValue != null && knowledgeValue.isJsonObject()
                ? knowledgeValue.getAsJsonObject() : object;
        if (knowledge.entrySet().stream().noneMatch(entry -> Set.of(
                "content_kind", "source_id", "collection_id", "title", "origin_type", "priority"
        ).contains(entry.getKey()))) {
            return fallback;
        }
        return new SourceClassification(
                KnowledgeContentKind.parse(stringValue(knowledge, "content_kind", fallback.contentKind().id())),
                stringValue(knowledge, "source_id", fallback.sourceId()),
                stringValue(knowledge, "collection_id", fallback.collectionId()),
                intValue(knowledge, "priority", fallback.priority()),
                stringValue(knowledge, "origin_type", fallback.originType()),
                knowledge.toString(),
                stringValue(knowledge, "title", fallback.title())
        );
    }

    private SourceClassification merge(SourceClassification base, SourceClassification override) {
        if (override == null) {
            return base;
        }
        return new SourceClassification(
                override.contentKind() == null ? base.contentKind() : override.contentKind(),
                override.sourceId().isBlank() ? base.sourceId() : override.sourceId(),
                override.collectionId().isBlank() ? base.collectionId() : override.collectionId(),
                override.priority() < 0 ? base.priority() : override.priority(),
                override.originType().isBlank() ? base.originType() : override.originType(),
                override.metadataJson().isBlank() ? base.metadataJson() : override.metadataJson(),
                override.title().isBlank() ? base.title() : override.title()
        );
    }

    private Map<String, SourceClassification> loadOverrides(Path knowledgeRoot, List<String> warnings) {
        if (knowledgeRoot == null) {
            return Map.of();
        }
        Path path = knowledgeRoot.resolve("source-overrides.json");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("覆盖文件必须是 JSON 对象");
            }
            Map<String, SourceClassification> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                result.put(entry.getKey(), new SourceClassification(
                        value.has("content_kind")
                                ? KnowledgeContentKind.parse(stringValue(value, "content_kind", "wiki")) : null,
                        stringValue(value, "source_id", ""),
                        stringValue(value, "collection_id", ""),
                        value.has("priority") ? intValue(value, "priority", -1) : -1,
                        stringValue(value, "origin_type", ""),
                        value.toString(),
                        stringValue(value, "title", "")
                ));
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            warnings.add("Worker 读取 source-overrides.json 失败");
            return Map.of();
        }
    }

    private String sourceTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/patchouli_books/") && lower.endsWith(".json")) {
            return "patchouli_json";
        }
        if (lower.contains("/guideme_guides/") && lower.endsWith(".json")) {
            return "guideme_json";
        }
        if ((lower.contains("/guides/") || lower.contains("/ae2guide/")) && lower.endsWith(".md")) {
            return "guideme_markdown";
        }
        if (lower.contains("/modonomicon/books/") && lower.endsWith(".json")) {
            return "app_json";
        }
        return null;
    }

    private String classificationKey(String path) {
        Matcher matcher = PATCHOULI_PATH.matcher(path);
        if (matcher.matches()) {
            return "app:" + matcher.group(2) + "/" + matcher.group(3);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("/modonomicon/books/");
        if (marker >= 0) {
            String namespace = namespaceOf(path);
            String tail = path.substring(marker + "/modonomicon/books/".length());
            return "app:" + namespace + "/" + tail.split("/")[0];
        }
        return null;
    }

    private String rootResourcePath(String path) {
        Matcher matcher = PATCHOULI_PATH.matcher(path);
        if (matcher.matches()) {
            String rest = matcher.group(4);
            String prefix = path.substring(0, path.indexOf("patchouli_books/") + "patchouli_books/".length());
            String locale = rest.startsWith("zh_cn/") || rest.startsWith("en_us/")
                    ? rest.substring(0, rest.indexOf('/')) + "/" : "";
            return prefix + matcher.group(3) + "/" + locale + "book.json";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("/modonomicon/books/");
        if (marker >= 0) {
            String prefix = path.substring(0, marker + "/modonomicon/books/".length());
            String tail = path.substring(marker + "/modonomicon/books/".length());
            int slash = tail.indexOf('/');
            String book = slash < 0 ? tail : tail.substring(0, slash);
            return prefix + book + "/book.json";
        }
        return null;
    }

    private String sourceResourcePath(String path) {
        String root = rootResourcePath(path);
        if (root == null) {
            return null;
        }
        int slash = root.lastIndexOf('/');
        return (slash < 0 ? "" : root.substring(0, slash + 1)) + "source.json";
    }

    private String namespaceOf(String path) {
        String[] parts = path.split("/");
        return parts.length >= 2 && ("assets".equals(parts[0]) || "data".equals(parts[0]))
                ? parts[1] : null;
    }

    private boolean isFrameworkNamespace(String namespace, SourceMetadata metadata) {
        return namespace != null && FRAMEWORK_IDS.contains(namespace.toLowerCase(Locale.ROOT));
    }

    private String stringValue(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private int intValue(JsonObject object, String key, int fallback) {
        try {
            return Integer.parseInt(stringValue(object, key, Integer.toString(fallback)));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private java.util.Optional<String> readText(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_TEXT_FILE_SIZE) {
            return java.util.Optional.empty();
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            byte[] bytes = readLimited(stream, MAX_TEXT_FILE_SIZE);
            return bytes == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
    }

    /** 对 ZipEntry.getSize() 未知或不可信的资源执行硬上限读取。 */
    private byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String safeName(String name) {
        String value = name == null ? "unknown" : name;
        int dot = value.lastIndexOf('.');
        return (dot > 0 ? value.substring(0, dot) : value).replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record ScanResult(List<ScannedResource> resources, List<String> warnings) {
        public ScanResult {
            resources = List.copyOf(resources == null ? List.of() : resources);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    private record RawResource(
            String namespace,
            String path,
            String sourceType,
            String content,
            String fingerprint,
            SourceMetadata metadata,
            SourceClassification classification
    ) {
    }

    private record SourceMetadata(String name, String version, Set<String> modIds) {
    }

    private record SourceClassification(
            KnowledgeContentKind contentKind,
            String sourceId,
            String collectionId,
            int priority,
            String originType,
            String metadataJson,
            String title
    ) {
        private SourceClassification {
            metadataJson = metadataJson == null ? "{}" : metadataJson;
            title = title == null ? "" : title;
        }

        static SourceClassification defaultFor(String namespace) {
            String value = namespace == null || namespace.isBlank() ? "unknown" : namespace;
            return new SourceClassification(
                    KnowledgeContentKind.MOD_MANUAL,
                    value,
                    value,
                    0,
                    "jar",
                    "{}",
                    value
            );
        }
    }
}
