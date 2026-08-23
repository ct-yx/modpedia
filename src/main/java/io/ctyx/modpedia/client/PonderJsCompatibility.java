package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 只做 PonderJS 的软兼容检查，不修改整合包脚本或其他模组文件。
 *
 * <p>PonderJS 的脚本属于整合包内容，ModPedia 只能指出已知不兼容调用，不能
 * 在启动时擅自改写脚本。检查放在后台线程，避免阻塞客户端加载线程。</p>
 */
public final class PonderJsCompatibility {
    private static final Pattern ROTATION_INDICATOR_CALL = Pattern.compile(
            "(?m)^(?!\\s*//).*rotationIndicator\\s*\\("
    );
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-ponder-compatibility");
        thread.setDaemon(true);
        return thread;
    });

    private PonderJsCompatibility() {
    }

    /** 在客户端完成加载后执行一次检测。 */
    public static void inspectAsync() {
        if (!ModList.get().isLoaded("ponderjs") || !SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Path scriptRoot = FMLPaths.GAMEDIR.get()
                .toAbsolutePath()
                .normalize()
                .resolve("kubejs/client_scripts/ponder");
        EXECUTOR.execute(() -> {
            Inspection inspection = inspect(scriptRoot);
            if (inspection.incompatibleFiles().isEmpty()) {
                ModPedia.LOGGER.info(
                        "检测到 PonderJS；未发现已知的 rotationIndicator 脚本调用，保持只读兼容模式"
                );
                return;
            }
            ModPedia.LOGGER.warn(
                    "检测到 PonderJS 与整合包脚本的潜在兼容问题：{} 个脚本调用 rotationIndicator。"
                            + " ModPedia 不会自动修改脚本，请更新 PonderJS 或由整合包作者调整脚本。文件：{}",
                    inspection.incompatibleFiles().size(),
                    inspection.incompatibleFiles()
            );
        });
    }

    /** 供纯 Java 自测试使用的只读脚本扫描。 */
    public static Inspection inspect(Path scriptRoot) {
        if (scriptRoot == null || !Files.isDirectory(scriptRoot)) {
            return new Inspection(false, List.of());
        }
        List<Path> incompatible = new ArrayList<>();
        try (var paths = Files.walk(scriptRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".js"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> {
                        try {
                            if (ROTATION_INDICATOR_CALL.matcher(Files.readString(path)).find()) {
                                incompatible.add(path.toAbsolutePath().normalize());
                            }
                        } catch (IOException ignored) {
                            // 单个脚本不可读时不阻塞游戏启动，也不扩大兼容检查范围。
                        }
                    });
        } catch (IOException ignored) {
            return new Inspection(true, List.of());
        }
        return new Inspection(true, List.copyOf(incompatible));
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public record Inspection(boolean scriptDirectoryPresent, List<Path> incompatibleFiles) {
        public Inspection {
            incompatibleFiles = incompatibleFiles == null ? List.of() : List.copyOf(incompatibleFiles);
        }
    }
}
