package io.ctyx.modpedia.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cleanroom/Forge 1.12.2 的第一版静态来源扫描器。
 *
 * <p>扫描器只读取文件和 JAR，不加载 Patchouli、Guide-API 或内容模组类，因此可以
 * 在独立线程运行，也不会把可选手册 Mod 变成硬依赖。正文转换和 SQLite 写入仍由
 * Worker 后续负责。</p>
 */
public final class ManualCatalogScanner {
    private static final long MAX_TEXT_FILE_SIZE = 8L * 1024L * 1024L;
    private static final Pattern PATCHOULI_BOOK = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/book\\.json$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_DISABLED_BOOK = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books_disabled/([^/]+)/book\\.json$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCHOULI_FILE = Pattern.compile(
            "^(assets|data)/([^/]+)/patchouli_books/([^/]+)/(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    /** Guide-API 内容并不统一位于某个 book 目录，按实际资源命名空间单独识别。 */
    private static final Pattern GUIDE_API_ROOT = Pattern.compile(
            "^assets/(chisel_guide|bloodmagicguide|bloodarsenalguide)/(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STATIC_ROOT = Pattern.compile(
            "^assets/([^/]+)/(book|books|manual|eiobook|research|wiki|text)/(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}_[a-z]{2}", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern KNOWLEDGE_KIND = Pattern.compile(
            "\\\"content_kind\\\"\\s*:\\s*\\\"(wiki|mod_manual)\\\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> FRAMEWORK_IDS = new HashSet<String>(Arrays.asList(
            "patchouli", "guideme", "modonomicon", "guideapi", "ftbguides", "akashictome", "mantle"
    ));
    private static final Set<String> MANTLE_IDS = new HashSet<String>(Arrays.asList(
            "tconstruct", "tcomplement", "conarm", "taiga", "tconevo", "toolprogression"
    ));
    private static final Set<String> BOOK_TEXT_IDS = new HashSet<String>(Arrays.asList(
            "logisticspipes"
    ));
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<String>(Arrays.asList(
            ".json", ".md", ".txt", ".xml", ".lang", ".cfg", ".properties"
    ));

    /** 按中文、英文、其他语言的稳定顺序选择一个语言目录。 */
    public ManualScanResult scan(Path instanceRoot, String requestedLanguage) {
        List<ManualSourceDescriptor> sources = new ArrayList<ManualSourceDescriptor>();
        List<String> warnings = new ArrayList<String>();
        if (instanceRoot == null) {
            warnings.add("实例目录为空");
            return new ManualScanResult(sources, warnings, 0, new Date());
        }

        Path root = instanceRoot.toAbsolutePath().normalize();
        String language = normalizeLanguage(requestedLanguage);
        scanExternalPatchouli(root, language, sources, warnings);

        Path mods = root.resolve("mods");
        List<Path> archives = archiveFiles(mods, warnings);
        for (Path archive : archives) {
            scanArchive(root, archive, language, sources, warnings);
        }

        Collections.sort(sources, new Comparator<ManualSourceDescriptor>() {
            @Override
            public int compare(ManualSourceDescriptor left, ManualSourceDescriptor right) {
                int source = left.getSourceId().compareTo(right.getSourceId());
                return source == 0 ? left.getSourcePath().compareTo(right.getSourcePath()) : source;
            }
        });
        return new ManualScanResult(sources, warnings, archives.size(), new Date());
    }

    /** 识别根目录和 memory_repo 等任意深度的嵌套 JAR/ZIP。 */
    public static List<Path> archiveFiles(Path modsDirectory, List<String> warnings) {
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            if (warnings != null) {
                warnings.add("mods 目录不存在：" + modsDirectory);
            }
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.walk(modsDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(ManualCatalogScanner::isArchive)
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException exception) {
            if (warnings != null) {
                warnings.add("扫描 mods 目录失败：" + exception.getClass().getSimpleName());
            }
            return Collections.emptyList();
        }
    }

    private void scanExternalPatchouli(
            Path instanceRoot,
            String requestedLanguage,
            List<ManualSourceDescriptor> output,
            List<String> warnings
    ) {
        Path booksRoot = instanceRoot.resolve("patchouli_books");
        if (!Files.isDirectory(booksRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.list(booksRoot)) {
            List<Path> books = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path book : books) {
                Path bookJson = book.resolve("book.json");
                if (!Files.isRegularFile(bookJson)) {
                    continue;
                }
                String bookId = book.getFileName().toString();
                String sourceId = externalSourceId(bookId);
                String title = readBookTitle(bookJson, sourceId);
                String selected = selectDirectoryLanguage(book, requestedLanguage);
                List<Path> textFiles = textFiles(book);
                output.add(new ManualSourceDescriptor(
                        sourceId,
                        "greedycraft",
                        KnowledgeContentKind.WIKI,
                        ManualSourceType.PATCHOULI_1_12_JSON,
                        "local_file",
                        title,
                        selected,
                        "local",
                        relativePath(instanceRoot, book),
                        fingerprint(textFiles, instanceRoot, warnings),
                        textFiles.size()
                ));
            }
        } catch (IOException exception) {
            warnings.add("扫描外部 Patchouli 书籍失败：" + exception.getClass().getSimpleName());
        }
    }

    private void scanArchive(
            Path instanceRoot,
            Path archive,
            String requestedLanguage,
            List<ManualSourceDescriptor> output,
            List<String> warnings
    ) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ArchiveMetadata metadata = readMetadata(zip, archive.getFileName().toString());
            Map<String, ArchiveSource> candidates = new TreeMap<String, ArchiveSource>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalizePath(entry.getName());
                Matcher patchouli = PATCHOULI_BOOK.matcher(path);
                if (patchouli.matches()) {
                    addPatchouliSource(candidates, metadata, patchouli, path, entry);
                    continue;
                }
                Matcher patchouliFile = PATCHOULI_FILE.matcher(path);
                if (patchouliFile.matches()) {
                    addPatchouliPath(candidates, metadata, patchouliFile, path, entry);
                    continue;
                }
                if (PATCHOULI_DISABLED_BOOK.matcher(path).matches()) {
                    // disabled 书籍只在报告里保留为排除事实，不进入默认来源清单。
                    continue;
                }
                Matcher guideApiRoot = GUIDE_API_ROOT.matcher(path);
                if (guideApiRoot.matches() && isTextFile(path)) {
                    addGuideApiSource(candidates, metadata, guideApiRoot, path);
                    continue;
                }
                Matcher staticRoot = STATIC_ROOT.matcher(path);
                if (staticRoot.matches() && isTextFile(path)) {
                    addStaticSource(candidates, metadata, staticRoot, path);
                }
            }

            for (ArchiveSource candidate : candidates.values()) {
                String selectedLanguage = selectArchiveLanguage(candidate.languages, requestedLanguage);
                String title = candidate.title;
                if (candidate.patchouliBookPath != null) {
                    String bookJson = readText(zip, candidate.patchouliBookPath, warnings);
                    title = readBookTitle(bookJson, candidate.sourceId);
                    KnowledgeContentKind classified = classifyBook(bookJson, candidate.contentKind);
                    candidate.contentKind = classified;
                    String overrideId = jsonField(bookJson, "source_id");
                    String overrideCollection = jsonField(bookJson, "collection_id");
                    if (!isBlank(overrideId)) {
                        candidate.sourceId = overrideId;
                    }
                    if (!isBlank(overrideCollection)) {
                        candidate.collectionId = overrideCollection;
                    }
                }
                String fingerprint = fingerprint(zip, candidate.paths, warnings);
                output.add(new ManualSourceDescriptor(
                        candidate.sourceId,
                        candidate.collectionId,
                        candidate.contentKind,
                        candidate.sourceType,
                        "jar",
                        title,
                        selectedLanguage,
                        metadata.version,
                        relativePath(instanceRoot, archive) + "!/" + candidate.rootPath,
                        fingerprint,
                        candidate.paths.size()
                ));
            }
        } catch (IOException exception) {
            warnings.add("读取手册 JAR 失败：" + archive.getFileName()
                    + "（" + exception.getClass().getSimpleName() + "）");
        }
    }

    private void addPatchouliSource(
            Map<String, ArchiveSource> candidates,
            ArchiveMetadata metadata,
            Matcher matcher,
            String path,
            ZipEntry entry
    ) {
        String namespace = matcher.group(2).toLowerCase(Locale.ROOT);
        if (isFramework(namespace, metadata.modId)) {
            return;
        }
        String book = matcher.group(3);
        String rootPath = matcher.group(1).toLowerCase(Locale.ROOT) + "/" + namespace
                + "/patchouli_books/" + book;
        String key = "patchouli|" + rootPath;
        ArchiveSource source = candidates.get(key);
        if (source == null) {
            source = new ArchiveSource(
                    metadata.modId + ":" + book,
                    metadata.modId,
                    KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.PATCHOULI_1_12_JSON,
                    rootPath,
                    metadata.displayName + " " + book
            );
            source.patchouliBookPath = path;
            candidates.put(key, source);
        }
        addArchivePath(source, path, entry);
        collectLanguage(source, rootPath, path);
    }

    private void addPatchouliPath(
            Map<String, ArchiveSource> candidates,
            ArchiveMetadata metadata,
            Matcher matcher,
            String path,
            ZipEntry entry
    ) {
        String namespace = matcher.group(2).toLowerCase(Locale.ROOT);
        if (isFramework(namespace, metadata.modId)) {
            return;
        }
        String book = matcher.group(3);
        String rootPath = matcher.group(1).toLowerCase(Locale.ROOT) + "/" + namespace
                + "/patchouli_books/" + book;
        String key = "patchouli|" + rootPath;
        ArchiveSource source = candidates.get(key);
        if (source == null) {
            source = new ArchiveSource(
                    metadata.modId + ":" + book,
                    metadata.modId,
                    KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.PATCHOULI_1_12_JSON,
                    rootPath,
                    metadata.displayName + " " + book
            );
            candidates.put(key, source);
        }
        addArchivePath(source, path, entry);
        collectLanguage(source, rootPath, path);
    }

    private void addStaticSource(
            Map<String, ArchiveSource> candidates,
            ArchiveMetadata metadata,
            Matcher matcher,
            String path
    ) {
        String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
        String rootName = matcher.group(2).toLowerCase(Locale.ROOT);
        if (isFramework(namespace, metadata.modId)) {
            return;
        }
        ManualSourceType type = sourceType(namespace, rootName);
        if (type == null) {
            return;
        }
        String rootPath = "assets/" + namespace + "/" + rootName;
        String key = type.getId() + "|" + rootPath;
        ArchiveSource source = candidates.get(key);
        if (source == null) {
            String sourceId = metadata.modId + ":" + rootName;
            if (!metadata.modId.equalsIgnoreCase(namespace)) {
                sourceId += ":" + namespace;
            }
            source = new ArchiveSource(
                    sourceId,
                    metadata.modId,
                    KnowledgeContentKind.MOD_MANUAL,
                    type,
                    rootPath,
                    metadata.displayName + " " + rootName
            );
            candidates.put(key, source);
        }
        source.paths.add(path);
        collectLanguage(source, rootPath, path);
    }

    private void addGuideApiSource(
            Map<String, ArchiveSource> candidates,
            ArchiveMetadata metadata,
            Matcher matcher,
            String path
    ) {
        String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
        String rootPath = "assets/" + namespace;
        String key = ManualSourceType.GUIDE_API_1_12.getId() + "|" + rootPath;
        ArchiveSource source = candidates.get(key);
        if (source == null) {
            String sourceId = metadata.modId + ":guide_api:" + namespace;
            source = new ArchiveSource(
                    sourceId,
                    metadata.modId,
                    KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.GUIDE_API_1_12,
                    rootPath,
                    metadata.displayName + " Guide API"
            );
            candidates.put(key, source);
        }
        if (!source.paths.contains(path)) {
            source.paths.add(path);
        }
        collectLanguage(source, rootPath, path);
    }

    private ManualSourceType sourceType(String namespace, String rootName) {
        if ("manual".equals(rootName) && "forestry".equals(namespace)) {
            return ManualSourceType.FORESTRY_MANUAL_1_12;
        }
        if ("eiobook".equals(rootName) || ("book".equals(rootName) && "enderio".equals(namespace))) {
            return ManualSourceType.ENDERIO_BOOK_1_12;
        }
        if ("research".equals(rootName)) {
            return ManualSourceType.THAUMCRAFT_RESEARCH_1_12;
        }
        if ("chisel_guide".equals(rootName)) {
            return ManualSourceType.GUIDE_API_1_12;
        }
        if ("books".equals(rootName) && "bloodmagic".equals(namespace)) {
            return ManualSourceType.GUIDE_API_1_12;
        }
        if ("wiki".equals(rootName)) {
            return ManualSourceType.WIKI_ASSET_TEXT_1_12;
        }
        if ("text".equals(rootName)) {
            return ManualSourceType.CUSTOM_TEXT_1_12;
        }
        if ("book".equals(rootName) && MANTLE_IDS.contains(namespace)) {
            return ManualSourceType.MANTLE_BOOK_1_12;
        }
        if ("book".equals(rootName) && BOOK_TEXT_IDS.contains(namespace)) {
            return ManualSourceType.CUSTOM_TEXT_1_12;
        }
        return null;
    }

    private void addArchivePath(ArchiveSource source, String path, ZipEntry entry) {
        if (isTextFile(path) && !source.paths.contains(path)) {
            source.paths.add(path);
        }
    }

    private void collectLanguage(ArchiveSource source, String rootPath, String path) {
        if (!path.startsWith(rootPath + "/")) {
            return;
        }
        String remainder = path.substring(rootPath.length() + 1);
        String[] parts = remainder.split("/");
        for (String part : parts) {
            String languagePart = part;
            int dot = languagePart.indexOf('.');
            if (dot > 0) {
                languagePart = languagePart.substring(0, dot);
            }
            if (LANGUAGE.matcher(languagePart).matches()) {
                source.languages.add(languagePart.toLowerCase(Locale.ROOT));
            }
        }
    }

    private List<Path> textFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isTextFile(path.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private String selectDirectoryLanguage(Path root, String requestedLanguage) throws IOException {
        Set<String> languages = new HashSet<String>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path child : stream.collect(Collectors.toList())) {
                if (Files.isDirectory(child) && LANGUAGE.matcher(child.getFileName().toString()).matches()) {
                    languages.add(child.getFileName().toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return selectArchiveLanguage(languages, requestedLanguage);
    }

    private String selectArchiveLanguage(Set<String> languages, String requestedLanguage) {
        if (languages.isEmpty()) {
            return "neutral";
        }
        String requested = normalizeLanguage(requestedLanguage);
        if (languages.contains(requested)) {
            return requested;
        }
        if (languages.contains("zh_cn")) {
            return "zh_cn";
        }
        if (languages.contains("en_us")) {
            return "en_us";
        }
        List<String> ordered = new ArrayList<String>(languages);
        Collections.sort(ordered);
        return ordered.get(0);
    }

    private String readBookTitle(Path bookJson, String fallback) {
        try {
            return readBookTitle(new String(Files.readAllBytes(bookJson), StandardCharsets.UTF_8), fallback);
        } catch (IOException ignored) {
            return fallback;
        }
    }

    private String readBookTitle(String json, String fallback) {
        String value = jsonField(json, "name");
        if (isBlank(value)) {
            value = jsonField(json, "title");
        }
        return isBlank(value) ? fallback : value;
    }

    private KnowledgeContentKind classifyBook(String json, KnowledgeContentKind fallback) {
        Matcher matcher = KNOWLEDGE_KIND.matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return fallback;
        }
        return "wiki".equalsIgnoreCase(matcher.group(1))
                ? KnowledgeContentKind.WIKI : KnowledgeContentKind.MOD_MANUAL;
    }

    private ArchiveMetadata readMetadata(ZipFile zip, String fileName) {
        String info = readText(zip, "mcmod.info", Collections.<String>emptyList());
        String modId = jsonField(info, "modid");
        String displayName = jsonField(info, "name");
        String version = jsonField(info, "version");
        if (isBlank(modId)) {
            modId = safeFileName(fileName);
        }
        modId = modId.trim().toLowerCase(Locale.ROOT);
        if (isBlank(displayName)) {
            displayName = modId;
        }
        return new ArchiveMetadata(modId, displayName, isBlank(version) ? "unknown" : version);
    }

    private String readText(ZipFile zip, String path, List<String> warnings) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return "";
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = readLimited(input);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            if (warnings != null) {
                warnings.add("读取 JAR 文本失败：" + path);
            }
            return "";
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_TEXT_FILE_SIZE) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String fingerprint(List<Path> paths, Path instanceRoot, List<String> warnings) {
        MessageDigest digest = sha256Digest();
        for (Path path : paths) {
            try {
                digest.update(relativePath(instanceRoot, path).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                byte[] bytes;
                try (InputStream input = Files.newInputStream(path)) {
                    bytes = readLimited(input);
                }
                if (bytes == null) {
                    warnings.add("来源文件超过大小限制：" + path.getFileName());
                    continue;
                }
                digest.update(bytes);
            } catch (IOException exception) {
                warnings.add("计算来源指纹失败：" + path.getFileName());
            }
        }
        return hex(digest.digest());
    }

    private String fingerprint(ZipFile zip, List<String> paths, List<String> warnings) {
        MessageDigest digest = sha256Digest();
        List<String> ordered = new ArrayList<String>(paths);
        Collections.sort(ordered);
        for (String path : ordered) {
            String content = readText(zip, path, warnings);
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(content.getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JRE 缺少 SHA-256", exception);
        }
    }

    private static String jsonField(String json, String field) {
        if (json == null) {
            return "";
        }
        Pattern pattern = Pattern.compile(String.format(JSON_FIELD.pattern(), Pattern.quote(field)), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescapeJson(matcher.group(1)) : "";
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static boolean isTextFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : TEXT_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFramework(String namespace, String modId) {
        return FRAMEWORK_IDS.contains(namespace.toLowerCase(Locale.ROOT))
                || FRAMEWORK_IDS.contains(modId.toLowerCase(Locale.ROOT));
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String normalizeLanguage(String language) {
        if (isBlank(language)) {
            return "zh_cn";
        }
        return language.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String externalSourceId(String bookId) {
        if ("greedycraft_guide_book".equalsIgnoreCase(bookId)) {
            return "greedycraft-guide";
        }
        if ("the_elysia_project".equalsIgnoreCase(bookId)) {
            return "the-elysia-project";
        }
        return "pack-" + bookId;
    }

    private static String relativePath(Path root, Path path) {
        try {
            return normalizePath(root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
        } catch (IllegalArgumentException exception) {
            return normalizePath(path.toAbsolutePath().normalize().toString());
        }
    }

    private static String safeFileName(String fileName) {
        String value = fileName == null ? "unknown" : fileName;
        int dot = value.indexOf('.');
        return (dot > 0 ? value.substring(0, dot) : value).toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ArchiveMetadata {
        private final String modId;
        private final String displayName;
        private final String version;

        private ArchiveMetadata(String modId, String displayName, String version) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
        }
    }

    private static final class ArchiveSource {
        private String sourceId;
        private String collectionId;
        private KnowledgeContentKind contentKind;
        private final ManualSourceType sourceType;
        private final String rootPath;
        private final String title;
        private final List<String> paths = new ArrayList<String>();
        private final Set<String> languages = new HashSet<String>();
        private String patchouliBookPath;

        private ArchiveSource(
                String sourceId,
                String collectionId,
                KnowledgeContentKind contentKind,
                ManualSourceType sourceType,
                String rootPath,
                String title
        ) {
            this.sourceId = sourceId;
            this.collectionId = collectionId;
            this.contentKind = contentKind;
            this.sourceType = sourceType;
            this.rootPath = rootPath;
            this.title = title;
        }
    }
}
