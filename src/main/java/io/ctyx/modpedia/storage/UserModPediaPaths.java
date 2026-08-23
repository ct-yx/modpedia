package io.ctyx.modpedia.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 1.12.2 适配层使用的用户级 Worker/配置路径。 */
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

    public Path conversations() {
        return userRoot.resolve("conversations");
    }

    public Path workerLibraryRoot() {
        return userRoot.resolve("worker").resolve("lib").resolve("worker-baseline-3");
    }

    public Path workerJar() {
        return workerLibraryRoot().resolve("modpedia-worker.jar");
    }

    public Path instanceRuntimeRoot() {
        Path parent = instanceConfig.getParent() == null ? instanceConfig : instanceConfig.getParent();
        return parent.resolve("runtime");
    }

    public Path instanceWorkerRoot() {
        return instanceRuntimeRoot().resolve("worker");
    }

    public Path workerPayloadRoot() {
        return instanceWorkerRoot().resolve("payload");
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
