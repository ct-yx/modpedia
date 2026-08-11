package io.ctyx.modpedia.knowledge;

import io.ctyx.modpedia.ModPedia;
import net.neoforged.fml.loading.FMLPaths;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 管理客户端启动和手动触发的后台知识库更新。 */
public final class KnowledgeUpdateService {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicReference<KnowledgeStatus> STATUS = new AtomicReference<>(
            KnowledgeStatus.initial()
    );
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-knowledge-builder");
        thread.setDaemon(true);
        return thread;
    });

    private KnowledgeUpdateService() {
    }

    /** 启动时按来源指纹执行增量更新。 */
    public static void startAsync() {
        requestAsync(false, "startup");
    }

    /** 手动执行完整来源转换，并重建索引。 */
    public static boolean rebuildAsync() {
        return requestAsync(true, "manual");
    }

    public static KnowledgeStatus status() {
        return STATUS.get();
    }

    private static boolean requestAsync(boolean forceRebuild, String reason) {
        if (!RUNNING.compareAndSet(false, true)) {
            ModPedia.LOGGER.info("Knowledge build already running; ignoring {} request", reason);
            return false;
        }
        KnowledgeStatus previous = STATUS.get();
        STATUS.set(new KnowledgeStatus(
                true,
                previous.sourceCount(),
                previous.documentCount(),
                previous.lastUpdated(),
                ""
        ));
        EXECUTOR.execute(() -> build(forceRebuild, reason));
        return true;
    }

    private static void build(boolean forceRebuild, String reason) {
        try {
            java.nio.file.Path configDirectory = FMLPaths.CONFIGDIR.get();
            LocalGuideScanner.ScanResult scanResult = new LocalGuideScanner().scan(
                    configDirectory.resolve("modpedia").resolve("knowledge")
            );
            KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                    configDirectory,
                    scanResult,
                    forceRebuild
            );
            KnowledgeCompiler.BuildReport report = result.report();
            STATUS.set(new KnowledgeStatus(
                    false,
                    report.sourceCount(),
                    report.documentCount(),
                    report.generatedAt(),
                    ""
            ));
            ModPedia.LOGGER.info(
                    "Knowledge build complete ({}): {} sources, {} generated documents, {} updated, {} reused, {} removed, {} custom documents, {} warnings",
                    reason,
                    report.sourceCount(),
                    report.generatedCount(),
                    report.updatedCount(),
                    report.reusedCount(),
                    report.removedCount(),
                    report.customCount(),
                    report.warnings().size()
            );
            report.warnings().forEach(warning -> ModPedia.LOGGER.warn("Knowledge build warning: {}", warning));
        } catch (Exception exception) {
            KnowledgeStatus previous = STATUS.get();
            STATUS.set(new KnowledgeStatus(
                    false,
                    previous.sourceCount(),
                    previous.documentCount(),
                    previous.lastUpdated(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            ));
            ModPedia.LOGGER.error("Knowledge build failed", exception);
        } finally {
            RUNNING.set(false);
        }
    }
}
