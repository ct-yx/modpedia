package io.ctyx.modpedia.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 基准专用的 JAR/资源目录装载器。
 *
 * <p>它刻意放在 test source set 中，不改变运行时扫描器。装载规则与
 * {@code LocalGuideScanner} 保持一致，同时允许基准分别选择中文和英文手册。</p>
 */
final class JarCorpusLoader {
    private static final long MAX_TEXT_FILE_SIZE = 8L * 1024L * 1024L;
    private static final Set<String> MANUAL_FRAMEWORK_MOD_IDS = Set.of(
            "patchouli",
            "guideme",
            "modonomicon"
    );
    private static final Pattern LANGUAGE_PATH = Pattern.compile(
            "^assets/([^/]+)/lang/(zh_cn|en_us)\\.json$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_LOCALE_PATH = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(zh_cn|en_us|[a-z]{2}_[a-z]{2})/(.+\\.(json|md))$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MOD_BLOCK = Pattern.compile(
            "(?ms)^\\s*\\[\\[mods\\]\\].*?(?=^\\s*\\[\\[|\\z)"
    );
    private static final Pattern TOML_STRING = Pattern.compile(
            "(?m)^\\s*%s\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)')"
    );

    private JarCorpusLoader() {
    }

    static LoadedCorpus load(
            List<Path> jars,
            List<ResourceRoot> resourceRoots,
            LanguageMode language
    ) throws IOException {
        List<ContainerResult> containers = new ArrayList<>();
        List<ScannedResource> resources = new ArrayList<>();

        for (Path jar : jars.stream().map(JarCorpusLoader::normalize).distinct().sorted().toList()) {
            ContainerResult result = loadJar(jar, language);
            containers.add(result);
            resources.addAll(result.resources());
        }
        for (ResourceRoot root : resourceRoots) {
            ContainerResult result = loadDirectory(root, language);
            containers.add(result);
            resources.addAll(result.resources());
        }

        resources.sort(Comparator.comparing(ScannedResource::modId).thenComparing(ScannedResource::path));
        Set<String> sourceKeys = new HashSet<>();
        int duplicateSourceKeys = 0;
        for (ScannedResource resource : resources) {
            if (!sourceKeys.add(sourceKey(resource))) {
                duplicateSourceKeys++;
            }
        }

        Set<String> modIds = new TreeSet<>();
        for (ContainerResult container : containers) {
            modIds.addAll(container.modIds());
            for (ScannedResource resource : container.resources()) {
                modIds.add(resource.modId());
            }
        }
        Set<String> guideModIds = new HashSet<>();
        for (ScannedResource resource : resources) {
            guideModIds.add(resource.modId());
        }

        List<String> dependencyOnlyContainers = containers.stream()
                .filter(container -> container.jar() && container.resources().isEmpty())
                .map(ContainerResult::name)
                .sorted()
                .toList();
        List<String> dependencyOnlyMods = containers.stream()
                .filter(container -> container.jar() && container.resources().isEmpty())
                .flatMap(container -> container.modIds().stream())
                .filter(modId -> !guideModIds.contains(modId))
                .distinct()
                .sorted()
                .toList();

        Set<String> availableMods = new HashSet<>(modIds);
        availableMods.addAll(Set.of("minecraft", "neoforge"));
        List<String> missingRequiredDependencies = containers.stream()
                .flatMap(container -> container.dependencies().stream())
                .filter(Dependency::required)
                .filter(dependency -> !availableMods.contains(dependency.modId()))
                .map(dependency -> dependency.owner() + " -> " + dependency.modId())
                .distinct()
                .sorted()
                .toList();

        Map<String, Integer> sourceTypeCounts = new TreeMap<>();
        resources.forEach(resource -> sourceTypeCounts.merge(resource.sourceType(), 1, Integer::sum));
        int candidateCount = containers.stream().mapToInt(ContainerResult::candidateCount).sum();
        int skippedLocalePages = containers.stream().mapToInt(ContainerResult::skippedLocalePages).sum();
        int languageFileCount = containers.stream().mapToInt(ContainerResult::languageFileCount).sum();
        int guideContainerCount = (int) containers.stream()
                .filter(container -> !container.resources().isEmpty())
                .count();

        CorpusStats stats = new CorpusStats(
                jars.size(),
                resourceRoots.size(),
                containers.stream().mapToInt(container -> container.modIds().size()).sum(),
                modIds.size(),
                guideContainerCount,
                dependencyOnlyContainers.size(),
                candidateCount,
                resources.size(),
                duplicateSourceKeys,
                languageFileCount,
                skippedLocalePages,
                sourceTypeCounts,
                dependencyOnlyContainers,
                dependencyOnlyMods,
                missingRequiredDependencies,
                containers.stream().flatMap(container -> container.warnings().stream()).toList()
        );
        return new LoadedCorpus(List.copyOf(resources), stats);
    }

    static List<Path> discoverJars(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(JarCorpusLoader::normalize)
                    .sorted()
                    .toList();
        }
    }

    private static ContainerResult loadJar(Path jar, LanguageMode language) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, ModMetadata> metadata;
        List<Dependency> dependencies;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String toml = readOptionalText(zip, "META-INF/neoforge.mods.toml");
            if (toml.isBlank()) {
                toml = readOptionalText(zip, "META-INF/mods.toml");
            }
            metadata = parseMetadata(toml, jar.getFileName().toString());
            dependencies = parseDependencies(toml);

            Map<String, byte[]> entries = new TreeMap<>();
            zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .filter(JarCorpusLoader::isPotentialCandidate)
                    .forEach(path -> {
                        try {
                            ZipEntry entry = zip.getEntry(path);
                            if (entry != null) {
                                byte[] bytes = readBytes(zip.getInputStream(entry), entry.getSize(), path);
                                if (bytes != null) {
                                    entries.put(path, bytes);
                                }
                            }
                        } catch (IOException exception) {
                            warnings.add("读取 JAR 资源失败：" + jar.getFileName() + "!" + path);
                        }
                    });
            return buildContainerResult(
                    jar.getFileName().toString(),
                    true,
                    entries,
                    metadata,
                    dependencies,
                    language,
                    warnings
            );
        }
    }

    private static ContainerResult loadDirectory(ResourceRoot root, LanguageMode language) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, byte[]> entries = new TreeMap<>();
        try (var paths = Files.walk(root.path())) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.path().relativize(path).toString().replace('\\', '/');
                if (!isPotentialCandidate(relative)) {
                    continue;
                }
                byte[] bytes = readBytes(Files.readAllBytes(path), relative, warnings);
                if (bytes != null) {
                    entries.put(relative, bytes);
                }
            }
        }
        Map<String, ModMetadata> metadata = Map.of(
                root.modId(), new ModMetadata(root.modId(), root.modName(), root.version())
        );
        return buildContainerResult(
                root.path().toString(),
                false,
                entries,
                metadata,
                List.of(),
                language,
                warnings
        );
    }

    private static ContainerResult buildContainerResult(
            String name,
            boolean jar,
            Map<String, byte[]> entries,
            Map<String, ModMetadata> metadata,
            List<Dependency> dependencies,
            LanguageMode language,
            List<String> warnings
    ) {
        Map<String, Set<String>> patchouliLocales = new HashMap<>();
        int candidateCount = 0;
        int skippedLocalePages = 0;
        int languageFileCount = 0;
        for (String path : entries.keySet()) {
            if (LANGUAGE_PATH.matcher(path).matches()) {
                languageFileCount++;
            }
            if (isFrameworkNamespace(namespaceOf(path), metadata)
                    || sourceTypeOf(path, entries.get(path)) == null) {
                continue;
            }
            candidateCount++;
            Matcher matcher = PATCHOULI_LOCALE_PATH.matcher(path);
            if (matcher.matches()) {
                String key = patchouliBookKey(matcher);
                patchouliLocales.computeIfAbsent(key, ignored -> new HashSet<>())
                        .add(matcher.group(4).toLowerCase(Locale.ROOT));
            }
        }

        Map<String, Map<String, Map<String, String>>> translations = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Matcher matcher = LANGUAGE_PATH.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            String namespace = matcher.group(1);
            String locale = matcher.group(2).toLowerCase(Locale.ROOT);
            try {
                JsonElement parsed = JsonParser.parseString(text(entry.getValue()));
                if (!parsed.isJsonObject()) {
                    continue;
                }
                Map<String, String> flattened = new LinkedHashMap<>();
                flattenTranslations(parsed.getAsJsonObject(), "", flattened);
                translations.computeIfAbsent(namespace, ignored -> new HashMap<>()).put(locale, flattened);
            } catch (RuntimeException exception) {
                warnings.add("解析语言文件失败：" + name + "!" + entry.getKey());
            }
        }

        List<ScannedResource> resources = new ArrayList<>();
        Set<String> modIds = new LinkedHashSet<>(metadata.keySet());
        for (String path : entries.keySet()) {
            String namespace = namespaceOf(path);
            if (isFrameworkNamespace(namespace, metadata)) {
                continue;
            }
            String sourceType = sourceTypeOf(path, entries.get(path));
            if (sourceType == null) {
                continue;
            }
            Matcher localeMatcher = PATCHOULI_LOCALE_PATH.matcher(path);
            if (localeMatcher.matches()) {
                String preferred = language.preferredLocale();
                Set<String> available = patchouliLocales.getOrDefault(patchouliBookKey(localeMatcher), Set.of());
                String selectedLocale = available.contains(preferred)
                        ? preferred
                        : available.contains(language.fallbackLocale()) ? language.fallbackLocale() : "";
                if (!localeMatcher.group(4).equalsIgnoreCase(selectedLocale)) {
                    skippedLocalePages++;
                    continue;
                }
            }

            ModMetadata mod = metadata.getOrDefault(
                    namespace,
                    new ModMetadata(namespace, namespace, "unknown")
            );
            modIds.add(namespace);
            Map<String, String> mergedTranslations = mergeTranslations(
                    translations.getOrDefault(namespace, Map.of()),
                    language
            );
            byte[] bytes = entries.get(path);
            resources.add(new ScannedResource(
                    namespace,
                    mod.name(),
                    mod.version(),
                    path,
                    sourceType,
                    text(bytes),
                    sha256(bytes),
                    mergedTranslations
            ));
        }

        return new ContainerResult(
                name,
                jar,
                List.copyOf(resources),
                Set.copyOf(modIds),
                List.copyOf(dependencies),
                candidateCount,
                languageFileCount,
                skippedLocalePages,
                List.copyOf(warnings)
        );
    }

    private static Map<String, String> mergeTranslations(
            Map<String, Map<String, String>> byLocale,
            LanguageMode language
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(byLocale.getOrDefault(language.fallbackLocale(), Map.of()));
        result.putAll(byLocale.getOrDefault(language.preferredLocale(), Map.of()));
        return Map.copyOf(result);
    }

    private static boolean isPotentialCandidate(String path) {
        if (!path.startsWith("assets/") && !path.startsWith("data/")) {
            return false;
        }
        if (LANGUAGE_PATH.matcher(path).matches()) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return (lower.contains("/patchouli_books/")
                || lower.contains("/guideme_guides/")
                || lower.contains("/guides/")
                || lower.contains("/ae2guide/")
                || potentialAppPath(lower))
                && (lower.endsWith(".json") || lower.endsWith(".md"));
    }

    private static String sourceTypeOf(String path, byte[] content) {
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
        if (potentialAppPath(lower) && lower.endsWith(".json")) {
            return "app_json";
        }
        return null;
    }

    private static boolean potentialAppPath(String lower) {
        return lower.contains("/modonomicon/books/");
    }

    private static boolean isFrameworkNamespace(String namespace, Map<String, ModMetadata> metadata) {
        return MANUAL_FRAMEWORK_MOD_IDS.contains(namespace.toLowerCase(Locale.ROOT))
                && metadata.containsKey(namespace);
    }

    private static String namespaceOf(String path) {
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    private static String patchouliBookKey(Matcher matcher) {
        return matcher.group(1).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(2).toLowerCase(Locale.ROOT) + "/"
                + matcher.group(3).toLowerCase(Locale.ROOT);
    }

    private static byte[] readBytes(InputStream stream, long declaredSize, String path) throws IOException {
        try (stream) {
            if (declaredSize > MAX_TEXT_FILE_SIZE) {
                return null;
            }
            byte[] bytes = stream.readAllBytes();
            return bytes.length > MAX_TEXT_FILE_SIZE ? null : bytes;
        }
    }

    private static byte[] readBytes(byte[] bytes, String path, List<String> warnings) {
        if (bytes.length > MAX_TEXT_FILE_SIZE) {
            warnings.add("跳过过大的知识文件：" + path);
            return null;
        }
        return bytes;
    }

    private static String readOptionalText(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return "";
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, ModMetadata> parseMetadata(String toml, String fallbackName) {
        Map<String, ModMetadata> result = new LinkedHashMap<>();
        Matcher matcher = MOD_BLOCK.matcher(toml == null ? "" : toml);
        while (matcher.find()) {
            String block = matcher.group();
            String id = tomlString(block, "modId");
            if (id.isBlank()) {
                continue;
            }
            result.put(id, new ModMetadata(
                    id,
                    firstNonBlank(tomlString(block, "displayName"), id),
                    firstNonBlank(tomlString(block, "version"), "unknown")
            ));
        }
        if (result.isEmpty()) {
            String fallback = fallbackName.replaceFirst("(?i)\\.jar$", "");
            result.put(fallback, new ModMetadata(fallback, fallback, "unknown"));
        }
        return result;
    }

    private static List<Dependency> parseDependencies(String toml) {
        if (toml == null || toml.isBlank()) {
            return List.of();
        }
        List<Dependency> result = new ArrayList<>();
        String owner = "";
        String modId = "";
        String type = "required";
        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("[[dependencies.") && line.endsWith("]]")) {
                if (!owner.isBlank() && !modId.isBlank()) {
                    result.add(new Dependency(owner, modId, type));
                }
                owner = line.substring("[[dependencies.".length(), line.length() - 2)
                        .replace("\"", "")
                        .trim();
                modId = "";
                type = "required";
                continue;
            }
            if (line.startsWith("[[") || line.startsWith("[")) {
                if (!owner.isBlank() && !modId.isBlank()) {
                    result.add(new Dependency(owner, modId, type));
                }
                owner = "";
                modId = "";
                type = "required";
                continue;
            }
            if (owner.isBlank()) {
                continue;
            }
            String value = tomlString(line, "modId");
            if (!value.isBlank()) {
                modId = value;
                continue;
            }
            value = tomlString(line, "type");
            if (!value.isBlank()) {
                type = value;
            }
        }
        if (!owner.isBlank() && !modId.isBlank()) {
            result.add(new Dependency(owner, modId, type));
        }
        return List.copyOf(result);
    }

    private static String tomlString(String block, String key) {
        String expression = String.format(Locale.ROOT, TOML_STRING.pattern(), Pattern.quote(key));
        Matcher matcher = Pattern.compile(expression).matcher(block);
        if (matcher.find()) {
            return firstNonBlank(matcher.group(1), matcher.group(2));
        }
        return "";
    }

    private static void flattenTranslations(JsonObject object, String prefix, Map<String, String> target) {
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

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String sourceKey(ScannedResource resource) {
        return resource.modId() + ":" + resource.path();
    }

    private static String sha256(byte[] bytes) {
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

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    enum LanguageMode {
        ZH_CN("zh_cn", "en_us"),
        EN_US("en_us", "zh_cn");

        private final String preferredLocale;
        private final String fallbackLocale;

        LanguageMode(String preferredLocale, String fallbackLocale) {
            this.preferredLocale = preferredLocale;
            this.fallbackLocale = fallbackLocale;
        }

        String preferredLocale() {
            return preferredLocale;
        }

        String fallbackLocale() {
            return fallbackLocale;
        }
    }

    record ResourceRoot(Path path, String modId, String modName, String version) {
        ResourceRoot {
            path = normalize(path);
            modId = modId == null ? "" : modId;
            modName = modName == null ? modId : modName;
            version = version == null ? "unknown" : version;
        }
    }

    record LoadedCorpus(List<ScannedResource> resources, CorpusStats stats) {
        LoadedCorpus {
            resources = List.copyOf(resources);
        }
    }

    record CorpusStats(
            int jarCount,
            int resourceRootCount,
            int declaredModCount,
            int uniqueModCount,
            int guideContainerCount,
            int dependencyOnlyContainerCount,
            int candidateCount,
            int sourceCount,
            int duplicateSourceKeyCount,
            int languageFileCount,
            int skippedLocalePageCount,
            Map<String, Integer> sourceTypeCounts,
            List<String> dependencyOnlyContainers,
            List<String> dependencyOnlyMods,
            List<String> missingRequiredDependencies,
            List<String> warnings
    ) {
        CorpusStats {
            sourceTypeCounts = Map.copyOf(sourceTypeCounts);
            dependencyOnlyContainers = List.copyOf(dependencyOnlyContainers);
            dependencyOnlyMods = List.copyOf(dependencyOnlyMods);
            missingRequiredDependencies = List.copyOf(missingRequiredDependencies);
            warnings = List.copyOf(warnings);
        }
    }

    private record ContainerResult(
            String name,
            boolean jar,
            List<ScannedResource> resources,
            Set<String> modIds,
            List<Dependency> dependencies,
            int candidateCount,
            int languageFileCount,
            int skippedLocalePages,
            List<String> warnings
    ) {
    }

    private record ModMetadata(String modId, String name, String version) {
    }

    private record Dependency(String owner, String modId, String type) {
        boolean required() {
            return type == null || type.isBlank() || "required".equalsIgnoreCase(type);
        }
    }
}
