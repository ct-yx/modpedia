package io.ctyx.modpedia.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.GuideLocaleSelector;
import io.ctyx.modpedia.knowledge.KnowledgeContentKind;
import io.ctyx.modpedia.knowledge.KnowledgeScanResult;
import io.ctyx.modpedia.knowledge.ScannedResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描当前实例中已安装模组的本地资源。
 *
 * <p>首版只读取 JAR 内的资源，不联网下载手册。扫描器保留来源路径和哈希，供后续增量更新使用。</p>
 */
public final class LocalGuideScanner {
    private static final long MAX_TEXT_FILE_SIZE = 8L * 1024L * 1024L;
    private static final Pattern LANGUAGE_PATH = Pattern.compile("^assets/([^/]+)/lang/(zh_cn|en_us)\\.json$");
    private static final Pattern PATCHOULI_LOCALE_PATH = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(zh_cn|en_us|[a-z]{2}_[a-z]{2})/(.+\\.(json|md))$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_PATH = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(.+\\.(json|md))$",
            Pattern.CASE_INSENSITIVE
    );
    /**
     * These mods provide the book runtime only. A book resource is owned by the
     * namespace that contains the resource, not by the runtime framework.
     */
    private static final Set<String> MANUAL_FRAMEWORK_MOD_IDS = Set.of(
            "patchouli",
            "guideme",
            "modonomicon"
    );

    public ScanResult scan() {
        return scan(null);
    }

    /**
     * 扫描当前实例的手册资源，并读取可选的来源分类覆盖文件。
     *
     * <p>扫描器不依赖具体内容模组。书籍根 JSON 中的 {@code knowledge} 字段可以把
     * 同一种手册格式标记为 Wiki；外部覆盖文件用于无法修改 JAR 的整合包。</p>
     */
    public ScanResult scan(Path knowledgeRoot) {
        List<RawResource> rawResources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, SourceClassification> overrides = loadOverrides(knowledgeRoot, warnings);

        for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
            IModFile modFile = fileInfo.getFile();
            Path root;
            try {
                root = modFile.getSecureJar().getRootPath();
            } catch (RuntimeException exception) {
                warnings.add("无法访问模组文件：" + modFile.getFileName());
                continue;
            }

            Map<String, SourceMetadata> metadata = new HashMap<>();
            for (IModInfo modInfo : fileInfo.getMods()) {
                metadata.put(modInfo.getModId(), new SourceMetadata(
                        modInfo.getDisplayName(),
                        String.valueOf(modInfo.getVersion())
                ));
            }

            try (Stream<Path> paths = Files.walk(root)) {
                List<String> relativePaths = paths.filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(path -> path.toString().replace('\\', '/'))
                        .filter(this::isPotentialCandidate)
                        .sorted()
                        .toList();
                Map<String, String> preferredPatchouliLocales = selectPatchouliLocales(relativePaths);
                Set<String> selectedGuidePaths = GuideLocaleSelector.select(relativePaths);
                Map<String, SourceClassification> classifications = sourceClassifications(
                        root,
                        relativePaths,
                        metadata,
                        overrides,
                        warnings
                );
                relativePaths.stream()
                        .filter(path -> isCandidate(path, preferredPatchouliLocales, selectedGuidePaths))
                        .forEach(relativePath -> readResource(
                                root,
                                relativePath,
                                metadata,
                                classifications,
                                rawResources,
                                warnings
                        ));
            } catch (IOException | RuntimeException exception) {
                warnings.add("扫描模组文件失败：" + modFile.getFileName() + "（" + exception.getClass().getSimpleName() + "）");
            }
        }

        Map<String, Map<String, String>> translations = loadTranslations(rawResources, warnings);
        List<ScannedResource> sources = rawResources.stream()
                .filter(resource -> resource.sourceType() != null)
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

        return new ScanResult(sources, warnings);
    }

    private boolean isPotentialCandidate(String path) {
        if (!path.startsWith("assets/") && !path.startsWith("data/")) {
            return false;
        }
        if (LANGUAGE_PATH.matcher(path).matches()) {
            return true;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return (lowerPath.contains("/patchouli_books/") || lowerPath.contains("/guideme_guides/")
                || lowerPath.contains("/guides/") || lowerPath.contains("/ae2guide/")
                || potentialAppPath(lowerPath))
                && (lowerPath.endsWith(".json") || lowerPath.endsWith(".md"));
    }

    private boolean isCandidate(
            String path,
            Map<String, String> preferredPatchouliLocales,
            Set<String> selectedGuidePaths
    ) {
        if (LANGUAGE_PATH.matcher(path).matches()) {
            return true;
        }
        if (!GuideLocaleSelector.shouldInclude(path, selectedGuidePaths)) {
            return false;
        }

        Matcher patchouliLocale = PATCHOULI_LOCALE_PATH.matcher(path);
        if (patchouliLocale.matches()) {
            String bookKey = patchouliBookKey(patchouliLocale);
            return patchouliLocale.group(4).equalsIgnoreCase(preferredPatchouliLocales.get(bookKey));
        }
        return true;
    }

    private Map<String, String> selectPatchouliLocales(List<String> paths) {
        Map<String, List<String>> available = new LinkedHashMap<>();
        for (String path : paths) {
            Matcher matcher = PATCHOULI_LOCALE_PATH.matcher(path);
            if (!matcher.matches()) {
                continue;
            }
            String bookKey = patchouliBookKey(matcher);
            available.computeIfAbsent(bookKey, ignored -> new ArrayList<>())
                    .add(matcher.group(4).toLowerCase(Locale.ROOT));
        }

        Map<String, String> selected = new HashMap<>();
        available.forEach((bookKey, locales) -> {
            if (locales.contains("zh_cn")) {
                selected.put(bookKey, "zh_cn");
            } else if (locales.contains("en_us")) {
                selected.put(bookKey, "en_us");
            }
        });
        return selected;
    }

    private String patchouliBookKey(Matcher matcher) {
        return matcher.group(1).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(2).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(3).toLowerCase(Locale.ROOT);
    }

    private void readResource(
            Path root,
            String relativePath,
            Map<String, SourceMetadata> metadata,
            Map<String, SourceClassification> classifications,
            List<RawResource> rawResources,
            List<String> warnings
    ) {
        String namespace = namespaceOf(relativePath);
        if (namespace == null) {
            return;
        }

        Path absolutePath = root.resolve(relativePath);
        try {
            if (Files.size(absolutePath) > MAX_TEXT_FILE_SIZE) {
                warnings.add("跳过过大的知识文件：" + relativePath);
                return;
            }
            byte[] bytes = readLimited(absolutePath, MAX_TEXT_FILE_SIZE);
            if (bytes == null) {
                warnings.add("跳过读取过程中超过上限的知识文件：" + relativePath);
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String sourceType = sourceTypeOf(relativePath, content);
            if (sourceType == null && !LANGUAGE_PATH.matcher(relativePath).matches()) {
                return;
            }
            if (sourceType != null && isFrameworkNamespace(namespace, metadata)) {
                // 三个手册框架只提供运行时 API；手册内容属于依赖它们的模组，
                // 不把框架自身的示例或资源误当成知识来源。
                return;
            }
            SourceClassification classification = classifications.getOrDefault(
                    classificationKey(relativePath),
                    SourceClassification.defaultFor(namespace)
            );
            rawResources.add(new RawResource(
                    namespace,
                    relativePath,
                    sourceType,
                    content,
                    sha256(bytes),
                    metadata.getOrDefault(namespace, new SourceMetadata(namespace, "unknown")),
                    classification
            ));
        } catch (IOException | RuntimeException exception) {
            warnings.add("读取知识文件失败：" + relativePath + "（" + exception.getClass().getSimpleName() + "）");
        }
    }

    private byte[] readLimited(Path path, long limit) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
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
    }

    private Map<String, Map<String, String>> loadTranslations(List<RawResource> resources, List<String> warnings) {
        Map<String, Map<String, String>> english = new LinkedHashMap<>();
        Map<String, Map<String, String>> chinese = new LinkedHashMap<>();

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
                Map<String, String> target = "zh_cn".equals(matcher.group(2))
                        ? chinese.computeIfAbsent(matcher.group(1), ignored -> new LinkedHashMap<>())
                        : english.computeIfAbsent(matcher.group(1), ignored -> new LinkedHashMap<>());
                flattenTranslations(parsed.getAsJsonObject(), "", target);
            } catch (RuntimeException exception) {
                warnings.add("解析语言文件失败：" + resource.path());
            }
        }

        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        english.forEach((namespace, values) -> merged.put(namespace, new LinkedHashMap<>(values)));
        chinese.forEach((namespace, values) -> merged.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).putAll(values));
        return merged;
    }

    private void flattenTranslations(JsonObject object, String prefix, Map<String, String> target) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                target.put(key, value.getAsString());
            } else if (value.isJsonObject()) {
                flattenTranslations(value.getAsJsonObject(), key, target);
            }
        }
    }

    private String namespaceOf(String path) {
        String[] parts = path.split("/");
        return parts.length >= 2 && ("assets".equals(parts[0]) || "data".equals(parts[0])) ? parts[1] : null;
    }

    private String sourceTypeOf(String path, String content) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("/patchouli_books/") && lowerPath.endsWith(".json")) {
            return "patchouli_json";
        }
        if (lowerPath.contains("/guideme_guides/") && lowerPath.endsWith(".json")) {
            return "guideme_json";
        }
        if ((lowerPath.contains("/guides/") || lowerPath.contains("/ae2guide/"))
                && lowerPath.endsWith(".md")) {
            return "guideme_markdown";
        }
        if (potentialAppPath(lowerPath) && lowerPath.endsWith(".json")) {
            return "app_json";
        }
        return null;
    }

    private Map<String, SourceClassification> sourceClassifications(
            Path root,
            List<String> paths,
            Map<String, SourceMetadata> metadata,
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
            SourceClassification fallback = SourceClassification.defaultFor(namespace);
            // 来源目录的 source.json 是默认分类的补充；source-overrides.json 用来
            // 覆盖无法修改的 JAR；书籍 JSON 内的 knowledge 字段拥有最高优先级。
            SourceClassification classification = readSourceClassification(root, path, fallback, warnings);
            classification = mergeClassification(classification, overrides.get(key));
            String rootPath = firstExistingBookRoot(root, path);
            if (rootPath != null) {
                try {
                    JsonElement parsed = JsonParser.parseString(Files.readString(
                            root.resolve(rootPath), StandardCharsets.UTF_8
                    ));
                    classification = classificationFromJson(parsed, classification, namespace, key);
                } catch (IOException | RuntimeException exception) {
                    warnings.add("解析书籍来源分类失败：" + rootPath);
                }
            }
            result.put(key, classification);
        }
        return result;
    }

    private SourceClassification readSourceClassification(
            Path root,
            String path,
            SourceClassification fallback,
            List<String> warnings
    ) {
        String sourcePath = sourceResourcePath(path);
        if (sourcePath == null || !Files.isRegularFile(root.resolve(sourcePath))) {
            return fallback;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(
                    root.resolve(sourcePath), StandardCharsets.UTF_8
            ));
            return classificationFromJson(parsed, fallback, namespaceOf(path), sourcePath);
        } catch (IOException | RuntimeException exception) {
            warnings.add("解析来源 source.json 失败：" + sourcePath);
            return fallback;
        }
    }

    private SourceClassification mergeClassification(
            SourceClassification base,
            SourceClassification override
    ) {
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

    private SourceClassification classificationFromJson(
            JsonElement parsed,
            SourceClassification fallback,
            String namespace,
            String key
    ) {
        if (parsed == null || !parsed.isJsonObject()) {
            return fallback;
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonElement metadataElement = object.get("knowledge");
        JsonObject knowledge = metadataElement != null && metadataElement.isJsonObject()
                ? metadataElement.getAsJsonObject()
                : object;
        if (!hasClassificationFields(knowledge)) {
            return fallback;
        }
        String contentKind = stringValue(knowledge, "content_kind");
        String sourceId = stringValue(knowledge, "source_id");
        String collectionId = stringValue(knowledge, "collection_id");
        String title = stringValue(knowledge, "title");
        String originType = stringValue(knowledge, "origin_type");
        int priority = intValue(knowledge, "priority", fallback.priority());
        return new SourceClassification(
                KnowledgeContentKind.parse(contentKind.isBlank() ? fallback.contentKind().id() : contentKind),
                sourceId.isBlank() ? fallback.sourceId() : sourceId,
                collectionId.isBlank() ? fallback.collectionId() : collectionId,
                priority,
                originType.isBlank() ? fallback.originType() : originType,
                knowledge.toString(),
                title.isBlank() ? fallback.title() : title
        );
    }

    private boolean hasClassificationFields(JsonObject object) {
        return object.has("content_kind")
                || object.has("source_id")
                || object.has("collection_id")
                || object.has("title")
                || object.has("origin_type")
                || object.has("priority");
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
                String sourceId = stringValue(value, "source_id");
                String collectionId = stringValue(value, "collection_id");
                String kind = stringValue(value, "content_kind");
                result.put(
                        entry.getKey(),
                        new SourceClassification(
                                kind.isBlank() ? null : KnowledgeContentKind.parse(kind),
                                sourceId,
                                collectionId,
                                value.has("priority") ? intValue(value, "priority", -1) : -1,
                                stringValue(value, "origin_type"),
                                value.toString(),
                                stringValue(value, "title")
                        )
                );
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            warnings.add("读取 source-overrides.json 失败");
            return Map.of();
        }
    }

    private String classificationKey(String path) {
        Matcher matcher = PATCHOULI_PATH.matcher(path);
        if (matcher.matches()) {
            return "app:" + matcher.group(2) + "/" + matcher.group(3);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("/modonomicon/books/");
        if (marker >= 0) {
            String prefix = path.substring(0, marker);
            String namespace = namespaceOf(path);
            String tail = path.substring(marker + "/modonomicon/books/".length());
            String book = tail.split("/")[0];
            return "app:" + namespace + "/" + book;
        }
        return null;
    }

    private String rootResourcePath(String path) {
        Matcher matcher = PATCHOULI_PATH.matcher(path);
        if (matcher.matches()) {
            String rest = matcher.group(4);
            String prefix = path.substring(0, path.indexOf("patchouli_books/") + "patchouli_books/".length());
            String book = matcher.group(3);
            String locale = rest.startsWith("zh_cn/") || rest.startsWith("en_us/")
                    ? rest.substring(0, rest.indexOf('/')) + "/"
                    : "";
            return prefix + book + "/" + locale + "book.json";
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

    private String firstExistingBookRoot(Path root, String path) {
        String candidate = rootResourcePath(path);
        if (candidate == null) {
            return null;
        }
        if (Files.isRegularFile(root.resolve(candidate))) {
            return candidate;
        }
        // 某些 Patchouli 数据会把 book.json 放在语言目录下；优先使用标准根路径，
        // 但兼容这种资源布局，不让分类字段因目录差异失效。
        String normalized = candidate.replace("/book.json", "");
        for (String locale : List.of("zh_cn", "en_us")) {
            String localized = normalized + "/" + locale + "/book.json";
            if (Files.isRegularFile(root.resolve(localized))) {
                return localized;
            }
        }
        return null;
    }

    private String sourceResourcePath(String path) {
        String bookRoot = rootResourcePath(path);
        if (bookRoot == null) {
            return null;
        }
        int slash = bookRoot.lastIndexOf('/');
        return (slash < 0 ? "" : bookRoot.substring(0, slash + 1)) + "source.json";
    }

    private String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private int intValue(JsonObject object, String key, int fallback) {
        try {
            String value = stringValue(object, key);
            return value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private boolean potentialAppPath(String lowerPath) {
        // Do not classify every arbitrary books/entries JSON as a manual. The
        // format has a dedicated resource root; content mods own everything
        // below that root.
        return lowerPath.contains("/modonomicon/books/");
    }

    private boolean isFrameworkNamespace(String namespace, Map<String, SourceMetadata> metadata) {
        return MANUAL_FRAMEWORK_MOD_IDS.contains(namespace.toLowerCase(Locale.ROOT))
                && metadata.containsKey(namespace);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record ScanResult(List<ScannedResource> resources, List<String> warnings) {
        public ScanResult {
            resources = List.copyOf(Objects.requireNonNull(resources));
            warnings = List.copyOf(Objects.requireNonNull(warnings));
        }

        public KnowledgeScanResult toKnowledgeScanResult() {
            return new KnowledgeScanResult(resources, warnings);
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

    private record SourceClassification(
            KnowledgeContentKind contentKind,
            String sourceId,
            String collectionId,
            int priority,
            String originType,
            String metadataJson,
            String title
    ) {
        static SourceClassification defaultFor(String namespace) {
            String actual = namespace == null || namespace.isBlank() ? "unknown" : namespace;
            return new SourceClassification(
                    KnowledgeContentKind.MOD_MANUAL,
                    actual,
                    actual,
                    0,
                    "jar",
                    "{}",
                    actual
            );
        }
    }

    private record SourceMetadata(String name, String version) {
    }
}
