package io.ctyx.modpedia.compat;

import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.storage.ModPediaPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Worker Core 的源码边界回归：核心闭包不得直接引用 Minecraft、NeoForge 或客户端包。
 *
 * <p>AiAssistantSession 和 client.LocalGuideScanner 是客户端适配实现，不属于 Worker
 * 闭包；Worker 使用的 AI、知识库、任务和存储类仍在本测试覆盖范围内。</p>
 */
public final class WorkerCoreBoundarySelfTest {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?:net\\.minecraft\\.|net\\.neoforged\\.|io\\.ctyx\\.modpedia\\.client\\.)"
    );
    private static final List<String> PURE_PACKAGES = List.of(
            "worker",
            "protocol",
            "api",
            "compat",
            "knowledge",
            "search",
            "task",
            "recipe",
            "storage"
    );

    private WorkerCoreBoundarySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path sourceRoot = projectRoot().resolve("src/main/java/io/ctyx/modpedia");
        check(Files.isDirectory(sourceRoot), "找不到 Worker Core 源码根目录：" + sourceRoot);

        int checked = 0;
        for (String packageName : PURE_PACKAGES) {
            Path packageRoot = sourceRoot.resolve(packageName);
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(packageRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    checkPure(file);
                    checked++;
                }
            }
        }

        Path aiRoot = sourceRoot.resolve("ai");
        try (Stream<Path> files = Files.walk(aiRoot)) {
            for (Path file : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("AiAssistantSession.java"))
                    .toList()) {
                checkPure(file);
                checked++;
            }
        }

        check(WorkerProtocol.VERSION == 1, "当前 Worker 协议版本必须明确为 1");
        check(WorkerCompatibility.WORKER_LIBRARY_BASELINE.equals(
                        ModPediaPaths.WORKER_LIBRARY_BASELINE),
                "共享 Worker lib 基线必须由兼容层和路径层共同确认");
        check(checked > 0, "Worker Core 边界测试没有检查任何源码");
        System.out.println("Worker core boundary self-test passed: checked=" + checked);
    }

    private static void checkPure(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("import ") && FORBIDDEN.matcher(trimmed).find()) {
                throw new AssertionError("Worker Core 禁止依赖客户端/加载器类：" + file);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("modpedia.projectRoot", "."))
                .toAbsolutePath()
                .normalize();
    }
}
