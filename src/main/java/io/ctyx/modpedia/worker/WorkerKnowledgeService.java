package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.KnowledgeScanResult;
import io.ctyx.modpedia.task.TaskKnowledgeStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Worker 端知识库构建入口；游戏进程不再直接打开 knowledge.db。 */
public final class WorkerKnowledgeService {
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");
    private final Path configDirectory;
    private final Path knowledgeRoot;
    private final Path contentRoot;

    public WorkerKnowledgeService(Path configDirectory, Path knowledgeRoot) {
        this(configDirectory, knowledgeRoot, knowledgeRoot);
    }

    public WorkerKnowledgeService(Path configDirectory, Path knowledgeRoot, Path contentRoot) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.contentRoot = contentRoot.toAbsolutePath().normalize();
    }

    public BuildResult rebuild(Path modsDirectory, boolean forceRebuild) throws IOException {
        Path resolvedModsDirectory = resolveModsDirectory(modsDirectory, configDirectory);
        if (resolvedModsDirectory == null || !Files.isDirectory(resolvedModsDirectory)) {
            throw new IOException("Worker mods 目录不存在：" + resolvedModsDirectory);
        }
        int archiveCount = WorkerGuideScanner.archiveFiles(resolvedModsDirectory).size();
        if (archiveCount == 0 && hasGeneratedDocuments()) {
            // 不允许一次错误的 gameDir/mods 路径把上一份可用知识库清空。
            throw new IOException("Worker mods 目录没有 JAR/ZIP，已保留上一份知识库："
                    + resolvedModsDirectory);
        }
        LOG.info(() -> "knowledge.rebuild mods_requested=" + normalize(modsDirectory)
                + " mods_resolved=" + resolvedModsDirectory
                + " archives=" + archiveCount
                + " force=" + forceRebuild);
        List<String> scanWarnings = new ArrayList<>();
        try {
            new WorkerTaskWikiService(contentRoot).prepareLocal();
        } catch (IOException exception) {
            scanWarnings.add("任务 Wiki 本地副本准备失败");
        }
        WorkerGuideScanner.ScanResult scan = new WorkerGuideScanner()
                .scan(resolvedModsDirectory, contentRoot);
        scanWarnings.addAll(scan.warnings());
        LOG.info(() -> "knowledge.scan completed mods=" + resolvedModsDirectory
                + " archives=" + archiveCount
                + " resources=" + scan.resources().size()
                + " warnings=" + scan.warnings().size());
        KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                contentRoot,
                knowledgeRoot,
                new KnowledgeScanResult(
                        scan.resources(),
                        scanWarnings
                ),
                forceRebuild
        );
        if (!result.databaseSynchronized()) {
            String failure = result.report().warnings().stream()
                    .filter(warning -> warning.contains("SQLite 知识库同步失败"))
                    .findFirst()
                    .orElse("SQLite 知识库同步失败，已保留上一版本");
            BuildResult failed = new BuildResult(
                    result.report().sourceCount(),
                    result.report().documentCount(),
                    result.report().warnings().size(),
                    resolvedModsDirectory.toString(),
                    archiveCount,
                    scan.resources().size(),
                    false,
                    failure
            );
            LOG.warning(() -> "knowledge.rebuild failed database_synchronized=false reason=" + failure);
            return failed;
        }
        TaskKnowledgeStore taskStore = new TaskKnowledgeStore(knowledgeRoot);
        List<String> taskWarnings = new ArrayList<>();
        boolean taskSynchronized = true;
        String taskFailure = "";
        // 旧版客户端适配器曾把 ftbquests:<world> 运行时快照写入同一库。
        // 即使当前整合包没有可导入的任务目录，也必须在 Worker 重建时清掉
        // 这些遗留行；实时进度现在只在 search_tasks 查询期间临时读取。
        try {
            taskStore.cleanupLegacyRuntimeSnapshots();
        } catch (IOException exception) {
            taskWarnings.add("旧版 FTBQ 运行时快照清理失败，未发布本次构建");
            taskSynchronized = false;
            taskFailure = "旧版 FTBQ 运行时快照清理失败，已保留上一版本任务状态";
            LOG.warning("旧版 FTBQ 运行时快照清理失败，未发布本次构建");
        }
        WorkerTaskStaticImporter.ImportResult taskImport = new WorkerTaskStaticImporter()
                .importDirectory(configDirectory.resolve("ftbquests").resolve("quests"));
        taskWarnings.addAll(taskImport.warnings());
        taskSynchronized = taskSynchronized && taskImport.complete();
        if (!taskImport.complete() && taskFailure.isBlank()) {
            taskFailure = "静态任务定义导入不完整，已保留上一版本";
        }
        if (!taskImport.complete()) {
            taskWarnings.add(taskFailure);
        } else {
            try {
                // 来源目录消失代表当前实例已经移除了 FTB Quests 内容；同步空集合
                // 以清理旧的静态任务定义。解析不完整时 complete=false，保留上一版
                // 快照，避免一次损坏输入把可用任务库删空。
                taskStore.syncStaticSnapshots(taskImport.snapshots());
            } catch (IOException exception) {
                taskWarnings.add("静态任务定义同步失败，保留上一版本");
                taskSynchronized = false;
                taskFailure = "静态任务定义同步失败，已保留上一版本";
            }
        }
        if (!taskSynchronized) {
            LOG.warning("knowledge.rebuild task synchronization incomplete reason=" + taskFailure);
        }
        BuildResult buildResult = new BuildResult(
                result.report().sourceCount(),
                result.report().documentCount(),
                result.report().warnings().size() + taskWarnings.size(),
                resolvedModsDirectory.toString(),
                archiveCount,
                scan.resources().size(),
                taskSynchronized,
                taskFailure
        );
        LOG.info(() -> "knowledge.rebuild completed sources=" + buildResult.sourceCount()
                + " documents=" + buildResult.documentCount()
                + " warnings=" + buildResult.warningCount()
                + " successful=" + buildResult.successful());
        return buildResult;
    }

    /**
     * 选择实际包含模组压缩包的目录。
     *
     * <p>开发运行、启动器实例和 profile 启动方式对 {@code FMLPaths.GAMEDIR}
     * 的组织方式可能不同。客户端优先传入的目录；当它为空时，再检查 Worker
     * 配置目录的父目录以及子 JVM 当前工作目录，避免把“空 mods 目录”当成成功
     * 扫描。</p>
     */
    static Path resolveModsDirectory(Path requested, Path configDirectory) {
        Path requestedPath = normalize(requested);
        Set<Path> candidates = new LinkedHashSet<>();
        if (requestedPath != null) {
            candidates.add(requestedPath);
            try {
                if (Files.isDirectory(requestedPath)
                        && !WorkerGuideScanner.archiveFiles(requestedPath).isEmpty()) {
                    return requestedPath;
                }
            } catch (IOException ignored) {
                // 继续尝试由配置目录推导出的实例路径。
            }
        }
        Path config = normalize(configDirectory);
        if (config != null && config.getParent() != null) {
            candidates.add(config.getParent().resolve("mods").normalize());
        }
        Path working = normalize(Path.of(System.getProperty("user.dir", ".")));
        if (working != null) {
            candidates.add(working.resolve("mods").normalize());
        }

        Path best = null;
        int bestCount = -1;
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            int count;
            try {
                count = WorkerGuideScanner.archiveFiles(candidate).size();
            } catch (IOException exception) {
                count = 0;
            }
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best != null ? best : requestedPath;
    }

    private boolean hasGeneratedDocuments() throws IOException {
        Path generated = knowledgeRoot.resolve("generated");
        if (!Files.isDirectory(generated)) {
            return false;
        }
        try (var paths = Files.walk(generated, 2)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".md"));
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    public record BuildResult(
            int sourceCount,
            int documentCount,
            int warningCount,
            String modsDirectory,
            int archiveCount,
            int resourceCount,
            boolean successful,
            String failureMessage
    ) {
        public BuildResult {
            failureMessage = failureMessage == null ? "" : failureMessage;
        }
    }
}
