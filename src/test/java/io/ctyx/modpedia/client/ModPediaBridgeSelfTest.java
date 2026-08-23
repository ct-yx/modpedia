package io.ctyx.modpedia.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Worker 发布 JAR 定位回归：兼容 NeoForge 联合类加载器下不可用的 code source。 */
public final class ModPediaBridgeSelfTest {
    private static final String WORKER_ENTRY = "io/ctyx/modpedia/worker/WorkerMain.class";

    private ModPediaBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-worker-archive-");
        try {
            Path mods = root.resolve("mods");
            Files.createDirectories(mods);
            writeArchive(mods.resolve("unrelated.jar"), "other/Main.class");
            check(ModPediaBridge.findWorkerArchive(mods) == null,
                    "没有 WorkerMain 时不得误选其他模组");

            Path worker = mods.resolve("modpedia-0.2.0.jar");
            writeArchive(worker, WORKER_ENTRY);
            check(worker.equals(ModPediaBridge.findWorkerArchive(mods)),
                    "应从当前实例 mods 目录找到包含 WorkerMain 的发布 JAR");

            Path second = mods.resolve("modpedia-copy.jar");
            writeArchive(second, WORKER_ENTRY);
            check(ModPediaBridge.findWorkerArchive(mods) != null,
                    "多个候选 JAR 时仍应返回有效 Worker JAR");
            System.out.println("ModPedia Worker archive self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void writeArchive(Path archive, String entryName) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(0);
            output.closeEntry();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
