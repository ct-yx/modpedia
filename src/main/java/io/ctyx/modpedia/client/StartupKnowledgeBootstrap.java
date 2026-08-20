package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Asynchronous startup gate; long Worker and catalog waits never run on Render thread. */
public final class StartupKnowledgeBootstrap {
    private static final ExecutorService ASYNC = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-startup-async");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean shutdown;

    private StartupKnowledgeBootstrap() {
    }

    public static CompletableFuture<Boolean> startAsync() {
        if (shutdown) {
            return CompletableFuture.completedFuture(false);
        }
        // 多个 ready 回调、FML 生命周期和重连定时器可能同时触发启动流程。
        // 由单线程 executor 串行执行实际 bootstrap，避免并发启动两个 Worker
        // 或重复捕获整批 Tooltip。
        long started = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> shutdown
                        ? false
                        : ModPediaBridge.get().startBeforeMainMenu(), ASYNC)
                .thenCompose(startedWorker -> {
                    if (!startedWorker || shutdown) {
                        ModPedia.LOGGER.warn("Knowledge Worker did not start; pre-menu import skipped");
                        return CompletableFuture.completedFuture(false);
                    }
                    Path modsDirectory = resolveModsDirectory();
                    ModPedia.LOGGER.info("Knowledge scan mods directory: {}", modsDirectory);
                    // 物品目录与手册扫描是两条独立的数据链：目录只依赖客户端注册表
                    // 和 Worker IPC，不依赖知识库重建结果。之前把目录放在 rebuild
                    // 完成之后，导致大型整合包首轮扫描耗时较长时，目录请求要么在
                    // 主菜单安全门关闭后才发出，要么在 bootstrap 返回 false 后永远
                    // 错过，最终 item_catalog 保持为空，中文显示名也就无法映射到
                    // 英文手册中的稳定物品 ID。
                    return scheduleCatalogBeforeRebuild(
                            () -> ItemCatalogSyncService.syncBeforeMainMenuAsync(ModPediaBridge.get()),
                            () -> CompletableFuture.supplyAsync(() -> shutdown
                                            ? false
                                            : ModPediaBridge.get().rebuildKnowledgeBeforeMainMenu(
                                                    modsDirectory,
                                                    false
                                            ), ASYNC)
                    );
                })
                .thenCompose(rebuilt -> {
                    if (!rebuilt || shutdown) {
                        ModPedia.LOGGER.warn("Knowledge Worker rebuild did not complete");
                    }
                    // 目录请求已经在 Worker ready 后排队。这里仅记录本次手册重建
                    // 结果；ItemCatalogSyncService 会在主菜单安全门打开时复用同一
                    // 个 in-flight Future，避免再次扫描注册表。
                    ModPedia.LOGGER.info(
                            "Pre-menu knowledge bootstrap finished in {} ms, knowledge_rebuild={}",
                            (System.nanoTime() - started) / 1_000_000L,
                            rebuilt
                    );
                    return CompletableFuture.completedFuture(rebuilt && !shutdown);
                })
                .exceptionally(failure -> {
                    ModPedia.LOGGER.warn("Asynchronous knowledge bootstrap failed", failure);
                    return false;
                });
    }

    /** Compatibility entry point: schedules work and returns immediately. */
    public static void runBeforeMainMenu() {
        startAsync();
    }

    /** 游戏退出时阻止排队的启动阶段回调继续触碰 Worker 或客户端状态。 */
    public static void shutdown() {
        shutdown = true;
        ASYNC.shutdownNow();
    }

    /**
     * Worker 握手后的固定顺序：先安排客户端物品目录捕获，再提交耗时的知识库
     * 重建。独立成纯调度函数，避免以后为节省 Token 或合并异步链时把目录重新
     * 放回 rebuild 完成之后。
     */
    static CompletableFuture<Boolean> scheduleCatalogBeforeRebuild(
            Runnable catalogSchedule,
            Supplier<CompletableFuture<Boolean>> rebuildSchedule
    ) {
        if (catalogSchedule != null) {
            catalogSchedule.run();
        }
        return rebuildSchedule == null
                ? CompletableFuture.completedFuture(false)
                : rebuildSchedule.get();
    }

    /**
     * 解析当前实例的模组目录，避免启动器把游戏目录和工作目录分离时传入空目录。
     * 游戏目录优先；只有游戏目录下没有任何压缩包时才尝试当前工作目录和显式属性。
     */
    public static Path resolveModsDirectory() {
        Set<Path> candidates = new LinkedHashSet<>();
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        candidates.add(gameDirectory.resolve("mods"));
        Path gameMods = gameDirectory.resolve("mods");
        if (archiveCount(gameMods) > 0) {
            return gameMods;
        }

        String configuredGameDirectory = System.getProperty("minecraft.gameDirectory", "").strip();
        if (!configuredGameDirectory.isBlank()) {
            candidates.add(Path.of(configuredGameDirectory).toAbsolutePath().normalize().resolve("mods"));
        }
        candidates.add(Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize().resolve("mods"));

        Path firstExisting = null;
        int bestCount = -1;
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            if (firstExisting == null) {
                firstExisting = candidate;
            }
            int count = archiveCount(candidate);
            if (count > bestCount) {
                bestCount = count;
                firstExisting = candidate;
            }
        }
        return firstExisting == null ? gameDirectory.resolve("mods") : firstExisting;
    }

    private static int archiveCount(Path directory) {
        try (var paths = Files.walk(directory, 3)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jar") || name.endsWith(".zip");
                    })
                    .count();
        } catch (IOException exception) {
            return 0;
        }
    }
}
