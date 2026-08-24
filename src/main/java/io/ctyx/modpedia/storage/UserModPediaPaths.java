package io.ctyx.modpedia.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 1.12.2 适配层的用户级共享路径和实例级数据路径。 */
public final class UserModPediaPaths {
    private final Path userRoot;
    private final Path instanceConfig;

    private UserModPediaPaths(Path userRoot, Path instanceConfig) {
        this.userRoot = userRoot;
        this.instanceConfig = instanceConfig;
    }

    public static UserModPediaPaths resolve(Path instanceConfig) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String windowsHome = firstNonBlank(
                System.getenv("USERPROFILE"),
                join(System.getenv("HOMEDRIVE"), System.getenv("HOMEPATH")),
                System.getenv("HOME"),
                System.getProperty("user.home")
        );
        String unixHome = firstNonBlank(
                System.getenv("HOME"),
                System.getenv("USERPROFILE"),
                System.getProperty("user.home")
        );
        String home = os.contains("win") ? windowsHome : unixHome;
        if (home == null) {
            home = instanceConfig == null || instanceConfig.getParent() == null
                    ? "." : instanceConfig.getParent().toString();
        }
        return new UserModPediaPaths(
                Paths.get(home).toAbsolutePath().normalize().resolve(".modpedia"),
                instanceConfig == null ? Paths.get(".").toAbsolutePath().normalize()
                        : instanceConfig.toAbsolutePath().normalize()
        );
    }

    public Path userRoot() {
        return userRoot;
    }

    public Path aiSettings() {
        return userRoot.resolve("ai.json");
    }

    /**
     * 当前游戏实例的持久化会话目录。
     *
     * <p>会话包含整合包专属的物品 ID、来源和知识库上下文，因此不与其他实例共享。</p>
     */
    public Path instanceConversations() {
        return instanceRuntimeRoot().resolve("conversations");
    }

    public Path workerLibraryRoot() {
        return userRoot.resolve("worker").resolve("lib").resolve("worker-baseline-3");
    }

    public Path workerJar() {
        return workerLibraryRoot().resolve("modpedia-worker.jar");
    }

    public Path instanceRuntimeRoot() {
        // Worker 以 config/modpedia/runtime 作为实例级运行时根目录。
        // 客户端必须复用同一目录，否则物品载荷会被 Worker 的路径校验拒绝。
        return instanceConfig.resolve("modpedia").resolve("runtime");
    }

    public Path instanceWorkerRoot() {
        return instanceRuntimeRoot().resolve("worker");
    }

    public Path workerPayloadRoot() {
        // Worker 侧以 payloads 作为批量载荷目录名。两侧必须共享同一
        // 物理路径，否则客户端虽然成功写入 JSONL，Worker 会在路径校验
        // 阶段拒绝读取，最终 item_catalog 保持为空。
        return instanceWorkerRoot().resolve("payloads");
    }

    /** 客户端名称缓存与 Worker 的实例知识库共用目录，但不进入正式 SQLite。 */
    public Path itemCatalogCacheRoot() {
        return instanceRuntimeRoot().resolve("knowledge");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String join(String first, String second) {
        if (first == null || first.trim().isEmpty() || second == null || second.trim().isEmpty()) {
            return null;
        }
        return first + second;
    }
}
