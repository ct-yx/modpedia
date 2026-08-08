package io.ctyx.modpedia.knowledge;

import io.ctyx.modpedia.ModPedia;
import net.neoforged.fml.loading.FMLPaths;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理客户端首次启动时的后台知识库构建。 */
public final class KnowledgeUpdateService {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-knowledge-builder");
        thread.setDaemon(true);
        return thread;
    });

    private KnowledgeUpdateService() {
    }

    public static void startAsync() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(KnowledgeUpdateService::build);
    }

    private static void build() {
        try {
            LocalGuideScanner.ScanResult scanResult = new LocalGuideScanner().scan();
            KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                    FMLPaths.CONFIGDIR.get(),
                    scanResult
            );
            KnowledgeCompiler.BuildReport report = result.report();
            ModPedia.LOGGER.info(
                    "Knowledge build complete: {} sources, {} generated documents, {} custom documents, {} warnings",
                    report.sourceCount(), report.generatedCount(), report.customCount(), report.warnings().size()
            );
            report.warnings().forEach(warning -> ModPedia.LOGGER.warn("Knowledge build warning: {}", warning));
        } catch (Exception exception) {
            ModPedia.LOGGER.error("Knowledge build failed", exception);
        }
    }
}
