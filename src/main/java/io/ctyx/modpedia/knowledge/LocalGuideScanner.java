package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
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

    public ScanResult scan() {
        List<RawResource> rawResources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

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
                relativePaths.stream()
                        .filter(path -> isCandidate(path, preferredPatchouliLocales))
                        .forEach(relativePath -> readResource(root, relativePath, metadata, rawResources, warnings));
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
                        translations.getOrDefault(resource.namespace(), Map.of())
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
                || lowerPath.contains("/guides/") || lowerPath.contains("/ae2guide/"))
                && (lowerPath.endsWith(".json") || lowerPath.endsWith(".md"));
    }

    private boolean isCandidate(String path, Map<String, String> preferredPatchouliLocales) {
        if (LANGUAGE_PATH.matcher(path).matches()) {
            return true;
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
            byte[] bytes = Files.readAllBytes(absolutePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            rawResources.add(new RawResource(
                    namespace,
                    relativePath,
                    sourceTypeOf(relativePath),
                    content,
                    sha256(bytes),
                    metadata.getOrDefault(namespace, new SourceMetadata(namespace, "unknown"))
            ));
        } catch (IOException | RuntimeException exception) {
            warnings.add("读取知识文件失败：" + relativePath + "（" + exception.getClass().getSimpleName() + "）");
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

    private String sourceTypeOf(String path) {
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
        return null;
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
    }

    private record RawResource(
            String namespace,
            String path,
            String sourceType,
            String content,
            String fingerprint,
            SourceMetadata metadata
    ) {
    }

    private record SourceMetadata(String name, String version) {
    }
}
