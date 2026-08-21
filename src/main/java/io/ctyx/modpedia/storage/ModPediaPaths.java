package io.ctyx.modpedia.storage;

import io.ctyx.modpedia.compat.WorkerCompatibility;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ModPedia 的实际文件布局。
 *
 * <p>{@code knowledge/} 只保存整合包作者提供的事实源，{@code runtime/} 保存
 * Worker 状态和可从事实源重建的派生文件；跨实例的 AI 设置和 Worker 依赖缓存保存在当前
 * OS 用户目录。这样发布整合包时不会把 API Key、会话、本地索引或共享运行库一起带走。</p>
 */
public final class ModPediaPaths {
    /**
     * Worker 依赖的固定兼容基线。只要 Worker 运行库不变，不同 ModPedia 版本和
     * 不同游戏实例都复用这一套 lib；增删或升级嵌入依赖时必须递增该编号。
     */
    public static final String WORKER_LIBRARY_BASELINE = WorkerCompatibility.WORKER_LIBRARY_BASELINE;

    private final Path configDirectory;
    private final Path root;
    private final Path userRoot;
    private final Path runtimeRoot;
    private final Path contentRoot;
    private final Path runtimeKnowledgeRoot;

    private ModPediaPaths(Path configDirectory) {
        this(configDirectory, defaultUserHome(configDirectory));
    }

    private ModPediaPaths(Path configDirectory, Path userHome) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.root = this.configDirectory.resolve("modpedia");
        this.userRoot = userHome.toAbsolutePath().normalize().resolve(".modpedia");
        this.runtimeRoot = root.resolve("runtime");
        this.contentRoot = root.resolve("knowledge");
        this.runtimeKnowledgeRoot = runtimeRoot.resolve("knowledge");
    }

    public static ModPediaPaths forConfig(Path configDirectory) {
        if (configDirectory == null) {
            throw new IllegalArgumentException("configDirectory 不能为空");
        }
        return new ModPediaPaths(configDirectory);
    }

    /** 供自测试使用的可注入用户目录版本；生产代码使用当前 OS 用户目录。 */
    public static ModPediaPaths forConfig(Path configDirectory, Path userHome) {
        if (configDirectory == null) {
            throw new IllegalArgumentException("configDirectory 不能为空");
        }
        if (userHome == null) {
            throw new IllegalArgumentException("userHome 不能为空");
        }
        return new ModPediaPaths(configDirectory, userHome);
    }

    /**
     * 创建新布局并迁移早期版本散落在 config/modpedia 根目录的运行时文件。
     * AI 设置迁移到当前 OS 用户目录的共享文件，旧的实例内副本在共享文件
     * 已确认存在后清理；整合包作者的 custom/sources 原始文件始终保留。
     */
    public MigrationResult migrateLegacy() throws IOException {
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(runtimeKnowledgeRoot);
        Files.createDirectories(contentRoot);

        List<String> moved = new ArrayList<>();
        migrateAiSettings(moved);
        moveIfPresent(root.resolve("conversations"), conversationsRoot(), moved);
        moveIfPresent(root.resolve("diagnostics"), diagnosticsRoot(), moved);
        moveIfPresent(root.resolve("worker"), workerRoot(), moved);
        migrateWorkerLibraries(moved);
        moveIfPresent(root.resolve("assistant-window.json"), assistantWindow(), moved);
        moveIfPresent(root.resolve("assistant-glass.json"), assistantGlass(), moved);
        moveIfPresent(root.resolve("search-synonyms.json"), searchSynonyms(), moved);

        // knowledge/ 是新的内容目录。仅迁移其中可重建的运行时文件，custom、sources
        // 和 source-overrides.json 始终留在原路径，保证整合包作者的原始文件不被搬走。
        moveIfPresent(contentRoot.resolve("generated"), runtimeKnowledgeRoot.resolve("generated"), moved);
        moveIfPresent(contentRoot.resolve("cache"), runtimeKnowledgeRoot.resolve("cache"), moved);
        moveIfPresent(contentRoot.resolve("manifest.json"), runtimeKnowledgeRoot.resolve("manifest.json"), moved);
        moveIfPresent(contentRoot.resolve("keyword-index.json"), runtimeKnowledgeRoot.resolve("keyword-index.json"), moved);
        moveIfPresent(contentRoot.resolve("state.json"), runtimeKnowledgeRoot.resolve("state.json"), moved);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(contentRoot, "knowledge.db*")) {
            for (Path entry : entries) {
                moveIfPresent(entry, runtimeKnowledgeRoot.resolve(entry.getFileName().toString()), moved);
            }
        }
        return new MigrationResult(List.copyOf(moved));
    }

    /**
     * 把旧的实例级 Worker lib 合并到用户级固定基线目录。日志和 payload 仍留在
     * 当前实例；只有可由 ModPedia JAR 重新提取的依赖库跨实例共享。
     */
    private void migrateWorkerLibraries(List<String> moved) throws IOException {
        Path legacy = workerRoot().resolve("lib");
        if (!Files.isDirectory(legacy)) {
            return;
        }
        Path shared = workerLibraryRoot();
        copyDirectoryContents(legacy, shared);
        deleteTree(legacy);
        moved.add(legacy + " -> " + shared);
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path relative = source.relativize(entry);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(entry)) {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private void migrateAiSettings(List<String> moved) throws IOException {
        Path shared = aiSettings();
        Path rootLegacy = root.resolve("ai.json");
        Path runtimeLegacy = runtimeRoot.resolve("ai.json");
        Path source = Files.exists(runtimeLegacy) ? runtimeLegacy
                : Files.exists(rootLegacy) ? rootLegacy : null;

        if (source != null && !Files.exists(shared)) {
            Files.createDirectories(shared.getParent());
            movePath(source, shared);
            restrictSharedSettings(shared);
            moved.add(source + " -> " + shared);
        }

        if (Files.exists(shared)) {
            restrictSharedSettings(shared);
            for (Path legacy : new Path[]{runtimeLegacy, rootLegacy}) {
                if (Files.exists(legacy)) {
                    Files.deleteIfExists(legacy);
                    moved.add(legacy + " -> removed after shared settings migration");
                }
            }
        }
    }

    /** 启动入口使用的容错迁移；迁移失败由后续服务报告具体读写错误。 */
    public MigrationResult migrateLegacyQuietly() {
        try {
            return migrateLegacy();
        } catch (IOException exception) {
            return new MigrationResult(List.of());
        }
    }

    private void moveIfPresent(Path source, Path target, List<String> moved) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (Files.exists(target)) {
            if (Files.isDirectory(source) && Files.isDirectory(target)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                    for (Path child : children) {
                        moveIfPresent(child, target.resolve(child.getFileName().toString()), moved);
                    }
                }
                deleteIfEmpty(source);
            }
            return;
        }
        Files.createDirectories(target.getParent());
        movePath(source, target);
        moved.add(root.relativize(source).toString().replace('\\', '/')
                + " -> " + describeTarget(target));
    }

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private String describeTarget(Path target) {
        if (target.startsWith(root)) {
            return root.relativize(target).toString().replace('\\', '/');
        }
        return target.toString();
    }

    private void restrictSharedSettings(Path settings) {
        try {
            Files.setPosixFilePermissions(userRoot, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
            Files.setPosixFilePermissions(settings, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows 和不支持 POSIX 权限的文件系统使用系统默认用户权限。
        }
    }

    private void deleteIfEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            if (!children.iterator().hasNext()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    public Path configDirectory() {
        return configDirectory;
    }

    public Path root() {
        return root;
    }

    /** 玩家配置、会话、诊断和 Worker 临时文件目录。 */
    public Path runtimeRoot() {
        return runtimeRoot;
    }

    /** 当前 OS 用户共享的 ModPedia 配置目录，跨游戏实例复用。 */
    public Path userRoot() {
        return userRoot;
    }

    /** 整合包作者随包分发的 Markdown、Wiki 和来源描述目录。 */
    public Path contentRoot() {
        return contentRoot;
    }

    /** SQLite、生成 Markdown、索引和构建状态目录。 */
    public Path runtimeKnowledgeRoot() {
        return runtimeKnowledgeRoot;
    }

    public Path aiSettings() {
        return userRoot.resolve("ai.json");
    }

    /** 无法读取系统标识时使用的共享安装级回退标识。 */
    public Path installationId() {
        return userRoot.resolve("installation-id");
    }

    public Path conversationsRoot() {
        return runtimeRoot.resolve("conversations");
    }

    public Path diagnosticsRoot() {
        return runtimeRoot.resolve("diagnostics");
    }

    public Path workerRoot() {
        return runtimeRoot.resolve("worker");
    }

    /** 不随整合包分发、供同一 Worker 基线的实例共享的依赖缓存目录。 */
    public Path workerLibraryRoot() {
        return userRoot.resolve("worker")
                .resolve("lib")
                .resolve(WORKER_LIBRARY_BASELINE);
    }

    public Path workerPayloadRoot() {
        return workerRoot().resolve("payloads");
    }

    public Path assistantWindow() {
        return runtimeRoot.resolve("assistant-window.json");
    }

    public Path assistantGlass() {
        return runtimeRoot.resolve("assistant-glass.json");
    }

    public Path searchSynonyms() {
        return contentRoot.resolve("search-synonyms.json");
    }

    public record MigrationResult(List<String> moved) {
        public MigrationResult {
            moved = List.copyOf(moved == null ? List.of() : moved);
        }

        public boolean changed() {
            return !moved.isEmpty();
        }
    }

    private static Path defaultUserHome(Path configDirectory) {
        String value = System.getProperty("user.home", "").strip();
        if (!value.isBlank()) {
            return Path.of(value);
        }
        Path absoluteConfig = configDirectory.toAbsolutePath().normalize();
        return absoluteConfig.getParent() == null ? Path.of(".") : absoluteConfig.getParent();
    }
}
