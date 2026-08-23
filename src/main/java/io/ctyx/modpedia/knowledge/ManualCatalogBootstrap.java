package io.ctyx.modpedia.knowledge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** 在客户端预初始化阶段启动一次轻量来源索引，不阻塞 Minecraft 主线程。 */
public final class ManualCatalogBootstrap {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ModPedia-1.12-manual-index");
            thread.setDaemon(true);
            return thread;
        }
    });

    private ManualCatalogBootstrap() {
    }

    public static void schedule(final File configDirectory) {
        schedule(configDirectory, null);
    }

    /**
     * 在来源转换和旧版任务归一化完成后通知调用方。
     *
     * <p>通知只表示派生输入已经准备好，不在这里启动 Worker；这样客户端可以
     * 等待“扫描/转换完成”和“Worker 握手完成”两个条件同时满足后再重建 SQLite。</p>
     */
    public static void schedule(final File configDirectory, final Runnable completion) {
        if (configDirectory == null) {
            if (completion != null) {
                completion.run();
            }
            return;
        }
        final File instance = configDirectory.getAbsoluteFile().getParentFile();
        if (instance == null) {
            if (completion != null) {
                completion.run();
            }
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Path instanceRoot = instance.toPath().toAbsolutePath().normalize();
                    ManualScanResult result = new ManualCatalogScanner().scan(
                            instanceRoot,
                            preferredLanguage()
                    );
                    ManualCatalogWriter.writeManifest(
                            configDirectory.toPath().resolve("modpedia").resolve("knowledge"),
                            result
                    );
                    Path knowledgeRoot = configDirectory.toPath().resolve("modpedia").resolve("knowledge");
                    // 1.12.2 的旧手册格式由客户端适配层先转换成统一 Markdown
                    // 来源；真正的 SQLite/FTS 写入仍由独立 Worker 完成。
                    new LegacyMarkdownCompiler().compile(instanceRoot, knowledgeRoot, result);
                    // 1.12.2 FTBQ 是“一文件一任务”，Worker 的静态任务导入器
                    // 需要“一文件一章节”。这里只写派生归一化文件，绝不写玩家进度。
                    new LegacyFtbQuestNormalizer().normalize(instanceRoot, knowledgeRoot);
                } catch (IOException | RuntimeException ignored) {
                    // 索引是派生缓存；扫描失败不能阻止游戏启动，也不打印来源正文。
                } finally {
                    if (completion != null) {
                        completion.run();
                    }
                }
            }
        });
    }

    private static String preferredLanguage() {
        String language = System.getProperty("user.language", "zh");
        String country = System.getProperty("user.country", "CN");
        String value = (language + "_" + country).toLowerCase(Locale.ROOT);
        if (value.startsWith("en_")) {
            return "en_us";
        }
        if (value.startsWith("zh_")) {
            return "zh_cn";
        }
        return "zh_cn";
    }
}
