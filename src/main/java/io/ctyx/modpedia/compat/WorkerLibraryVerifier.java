package io.ctyx.modpedia.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 校验并原子维护用户级 Worker 依赖库。
 *
 * <p>基线目录可以被多个游戏实例共享，因此不能只判断文件是否存在：旧版本、截断文件
 * 或手工替换的 JAR 都必须按当前 ModPedia JAR 中的 SHA-256 重新安装。清单只保存文件名、
 * 大小和摘要，不保存绝对路径或敏感配置。</p>
 */
public final class WorkerLibraryVerifier {
    public static final String MANIFEST_FILE = "manifest.sha256";
    private static final String MANIFEST_FORMAT = "modpedia-worker-lib-v1";
    private static final String JAR_PREFIX = "META-INF/jarjar/";

    private WorkerLibraryVerifier() {
    }

    /**
     * 从发布 JAR 同步当前基线依赖，并返回应加入 Worker classpath 的文件。
     */
    public static SyncResult synchronize(Path workerArchive, Path libraryDirectory) throws IOException {
        if (workerArchive == null || !Files.isRegularFile(workerArchive)) {
            throw new IOException("Worker 发布 JAR 不存在：" + workerArchive);
        }
        libraryDirectory = libraryDirectory.toAbsolutePath().normalize();
        List<LibraryEntry> expected = readArchiveEntries(workerArchive);
        Files.createDirectories(libraryDirectory);
        Path lockPath = libraryDirectory.resolve(".lock");
        List<String> repaired = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            Map<String, LibraryEntry> previous = readManifestEntries(libraryDirectory.resolve(MANIFEST_FILE));
            Set<String> expectedNames = new HashSet<>();
            for (LibraryEntry entry : expected) {
                expectedNames.add(entry.fileName());
                Path target = safeTarget(libraryDirectory, entry.fileName());
                if (!matches(target, entry)) {
                    install(workerArchive, entry, target);
                    repaired.add(entry.fileName());
                }
            }
            for (String staleName : previous.keySet()) {
                if (!expectedNames.contains(staleName)) {
                    Path stale = safeTarget(libraryDirectory, staleName);
                    if (Files.deleteIfExists(stale)) {
                        repaired.add("removed:" + staleName);
                    }
                }
            }
            writeManifest(libraryDirectory.resolve(MANIFEST_FILE), expected);
        }

        Verification verification = verifyManifest(
                libraryDirectory,
                WorkerCompatibility.WORKER_LIBRARY_BASELINE
        );
        if (!verification.valid()) {
            throw new IOException("Worker 共享 lib 校验失败：" + verification.summary());
        }
        List<Path> classpath = new ArrayList<>();
        for (LibraryEntry entry : expected) {
            classpath.add(libraryDirectory.resolve(entry.fileName()));
        }
        return new SyncResult(
                WorkerCompatibility.WORKER_LIBRARY_BASELINE,
                classpath,
                List.copyOf(repaired),
                libraryDirectory.resolve(MANIFEST_FILE)
        );
    }

    /** 校验共享目录中的清单、文件大小和 SHA-256。 */
    public static Verification verifyManifest(Path libraryDirectory, String expectedBaseline)
            throws IOException {
        libraryDirectory = libraryDirectory.toAbsolutePath().normalize();
        Path manifest = libraryDirectory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            return Verification.invalid("缺少 " + MANIFEST_FILE);
        }
        List<String> lines = Files.readAllLines(manifest);
        if (lines.size() < 2 || !MANIFEST_FORMAT.equals(lines.get(0))) {
            return Verification.invalid("清单格式不匹配");
        }
        String baselinePrefix = "baseline=";
        if (!lines.get(1).startsWith(baselinePrefix)
                || !expectedBaseline.equals(lines.get(1).substring(baselinePrefix.length()))) {
            return Verification.invalid("清单基线不匹配");
        }

        Map<String, LibraryEntry> entries = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (int index = 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) {
                problems.add("清单行格式错误:" + (index + 1));
                continue;
            }
            try {
                LibraryEntry entry = new LibraryEntry(
                        fields[0],
                        fields[1],
                        Long.parseLong(fields[2])
                );
                safeTarget(libraryDirectory, entry.fileName());
                if (entries.put(entry.fileName(), entry) != null) {
                    problems.add("重复文件:" + entry.fileName());
                }
            } catch (RuntimeException exception) {
                problems.add("清单行无效:" + (index + 1));
            }
        }
        for (LibraryEntry entry : entries.values()) {
            Path target = libraryDirectory.resolve(entry.fileName());
            if (!matches(target, entry)) {
                problems.add("摘要不匹配:" + entry.fileName());
            }
        }
        return new Verification(problems.isEmpty(), List.copyOf(problems));
    }

    private static List<LibraryEntry> readArchiveEntries(Path archive) throws IOException {
        Map<String, LibraryEntry> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> nested = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith(JAR_PREFIX))
                    .filter(entry -> entry.getName().endsWith(".jar"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry nestedJar : nested) {
                String fileName = Path.of(nestedJar.getName()).getFileName().toString();
                if (entries.containsKey(fileName)) {
                    throw new IOException("Worker 依赖文件名冲突：" + fileName);
                }
                try (InputStream input = zip.getInputStream(nestedJar)) {
                    Digest digest = digest(input);
                    entries.put(fileName, new LibraryEntry(fileName, digest.sha256(), digest.size()));
                }
            }
        }
        return List.copyOf(entries.values());
    }

    private static void install(Path archive, LibraryEntry entry, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry nestedJar = zip.getEntry(JAR_PREFIX + entry.fileName());
            if (nestedJar == null) {
                nestedJar = zip.stream()
                        .filter(candidate -> !candidate.isDirectory())
                        .filter(candidate -> candidate.getName().startsWith(JAR_PREFIX))
                        .filter(candidate -> candidate.getName().endsWith(".jar"))
                        .filter(candidate -> Path.of(candidate.getName()).getFileName()
                                .toString().equals(entry.fileName()))
                        .findFirst()
                        .orElse(null);
            }
            if (nestedJar == null) {
                throw new IOException("Worker 依赖不在发布 JAR 中：" + entry.fileName());
            }
            try (InputStream input = zip.getInputStream(nestedJar)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!matches(temporary, entry)) {
                throw new IOException("Worker 依赖提取后摘要不匹配：" + entry.fileName());
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeManifest(Path manifest, Collection<LibraryEntry> entries) throws IOException {
        Path temporary = manifest.resolveSibling(manifest.getFileName() + ".tmp-" + UUID.randomUUID());
        List<String> lines = new ArrayList<>();
        lines.add(MANIFEST_FORMAT);
        lines.add("baseline=" + WorkerCompatibility.WORKER_LIBRARY_BASELINE);
        entries.stream()
                .sorted(Comparator.comparing(LibraryEntry::fileName))
                .forEach(entry -> lines.add(
                        entry.fileName() + "\t" + entry.sha256() + "\t" + entry.size()
                ));
        try {
            Files.write(
                    temporary,
                    lines,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        manifest,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, LibraryEntry> readManifestEntries(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            return Map.of();
        }
        List<String> lines = Files.readAllLines(manifest);
        Map<String, LibraryEntry> result = new HashMap<>();
        for (int index = 2; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            if (fields.length != 3) {
                continue;
            }
            try {
                result.put(fields[0], new LibraryEntry(fields[0], fields[1], Long.parseLong(fields[2])));
            } catch (NumberFormatException ignored) {
                // 损坏的旧清单不会阻止按当前发布 JAR 重建；最终清单会被覆盖。
            }
        }
        return result;
    }

    private static Path safeTarget(Path directory, String fileName) {
        Path target = directory.resolve(fileName).normalize();
        if (!target.getParent().equals(directory.toAbsolutePath().normalize())
                || fileName.isBlank()
                || fileName.contains("/")
                || fileName.contains("\\")
                || !fileName.endsWith(".jar")) {
            throw new IllegalArgumentException("非法 Worker 依赖文件名：" + fileName);
        }
        return target;
    }

    private static boolean matches(Path path, LibraryEntry expected) throws IOException {
        return Files.isRegularFile(path)
                && Files.size(path) == expected.size()
                && expected.sha256().equals(sha256(path));
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return digest(input).sha256();
        }
    }

    private static Digest digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long size = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
            return new Digest(HexFormat.of().formatHex(digest.digest()), size);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("JVM 缺少 SHA-256", exception);
        }
    }

    public record LibraryEntry(String fileName, String sha256, long size) {
        public LibraryEntry {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("Worker 依赖文件名不能为空");
            }
            if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Worker 依赖摘要必须是 SHA-256");
            }
            if (size < 0L) {
                throw new IllegalArgumentException("Worker 依赖大小不能为负数");
            }
            sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record SyncResult(
            String baseline,
            List<Path> classpath,
            List<String> repairedFiles,
            Path manifest
    ) {
        public SyncResult {
            classpath = List.copyOf(classpath);
            repairedFiles = List.copyOf(repairedFiles);
        }

        public boolean changed() {
            return !repairedFiles.isEmpty();
        }
    }

    public record Verification(boolean valid, List<String> problems) {
        public Verification {
            problems = List.copyOf(problems == null ? List.of() : problems);
        }

        private static Verification invalid(String problem) {
            return new Verification(false, List.of(problem));
        }

        public String summary() {
            return String.join(", ", problems);
        }
    }

    private record Digest(String sha256, long size) {
    }
}
