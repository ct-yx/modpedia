package io.ctyx.modpedia.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * ModPedia 的实际文件布局。
 *
 * <p>{@code knowledge/} 只保存整合包作者提供的事实源，{@code runtime/} 保存
 * 玩家配置、Worker 状态和可从事实源重建的派生文件。这样发布整合包时只需
 * 保留一个目录，不会把 API Key、会话或本地索引一起带走。</p>
 */
public final class ModPediaPaths {
    private final Path configDirectory;
    private final Path root;
    private final Path runtimeRoot;
    private final Path contentRoot;
    private final Path runtimeKnowledgeRoot;

    private ModPediaPaths(Path configDirectory) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.root = this.configDirectory.resolve("modpedia");
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

    /**
     * 创建新布局并迁移早期版本散落在 config/modpedia 根目录的运行时文件。
     * 迁移只移动文件，不删除任何整合包作者的 custom/sources 原始文件。
     */
    public MigrationResult migrateLegacy() throws IOException {
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(runtimeKnowledgeRoot);
        Files.createDirectories(contentRoot);

        List<String> moved = new ArrayList<>();
        moveIfPresent(root.resolve("ai.json"), aiSettings(), moved);
        moveIfPresent(root.resolve("conversations"), conversationsRoot(), moved);
        moveIfPresent(root.resolve("diagnostics"), diagnosticsRoot(), moved);
        moveIfPresent(root.resolve("worker"), workerRoot(), moved);
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
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
        moved.add(root.relativize(source).toString().replace('\\', '/')
                + " -> " + root.relativize(target).toString().replace('\\', '/'));
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

    /** 整合包作者随包分发的 Markdown、Wiki 和来源描述目录。 */
    public Path contentRoot() {
        return contentRoot;
    }

    /** SQLite、生成 Markdown、索引和构建状态目录。 */
    public Path runtimeKnowledgeRoot() {
        return runtimeKnowledgeRoot;
    }

    public Path aiSettings() {
        return runtimeRoot.resolve("ai.json");
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
}
