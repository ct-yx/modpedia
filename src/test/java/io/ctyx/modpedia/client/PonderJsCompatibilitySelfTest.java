package io.ctyx.modpedia.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PonderJS 兼容检查的纯 Java 回归测试。 */
public final class PonderJsCompatibilitySelfTest {
    private PonderJsCompatibilitySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-ponder-compat-");
        try {
            Files.writeString(root.resolve("safe.js"), "// rotationIndicator(1)\nscene.idle(10)\n");
            Files.writeString(root.resolve("corail.js"),
                    "scene.particles.rotationIndicator(60, [1, 2, 2], 0.3, 0.3, \\\"Y\\\")\n");
            Files.writeString(root.resolve("backup.js.before-fix"),
                    "scene.particles.rotationIndicator(1)\n");

            PonderJsCompatibility.Inspection inspection = PonderJsCompatibility.inspect(root);
            check(inspection.scriptDirectoryPresent(), "应识别脚本目录");
            check(inspection.incompatibleFiles().size() == 1, "只应识别真正的 JS 调用");
            check(inspection.incompatibleFiles().get(0).getFileName().toString().equals("corail.js"),
                    "应返回实际脚本文件");

            PonderJsCompatibility.Inspection missing = PonderJsCompatibility.inspect(root.resolve("missing"));
            check(!missing.scriptDirectoryPresent(), "缺少脚本目录时应返回未发现");
            check(missing.incompatibleFiles().isEmpty(), "缺少脚本目录时不应返回文件");
            System.out.println("ModPedia PonderJS compatibility self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
