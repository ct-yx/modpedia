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
                    return CompletableFuture.supplyAsync(() -> shutdown
                                    ? false
                                    : ModPediaBridge.get().rebuildKnowledgeBeforeMainMenu(
                                            modsDirectory,
                                            false
                                    ), ASYNC);
                })
                .thenCompose(rebuilt -> {
                    if (!rebuilt || shutdown) {
                        ModPedia.LOGGER.warn("Knowledge Worker rebuild did not complete; item catalog sync skipped");
                        return CompletableFuture.completedFuture(false);
                    }
                    // 目录捕获必须发生在客户端线程，且必须在进入世界前完成；
                    // 这里等待它的 Worker 同步结果，而不是启动后再由 tick 补扫。
                    return ItemCatalogSyncService.syncBeforeMainMenuAsync(ModPediaBridge.get())
                            .thenApply(itemsSynced -> {
                                ModPedia.LOGGER.info(
                                        "Pre-menu knowledge bootstrap finished in {} ms, item_catalog={}",
                                        (System.nanoTime() - started) / 1_000_000L,
                                        itemsSynced
                                );
                                return itemsSynced;
                            });
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
