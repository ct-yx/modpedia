package io.ctyx.modpedia.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Worker 共享 lib 的内容指纹、损坏修复和清单校验回归。 */
public final class WorkerLibraryVerifierSelfTest {
    private WorkerLibraryVerifierSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-worker-lib-");
        try {
            Path archive = root.resolve("modpedia.jar");
            Path libraries = root.resolve("user/.modpedia/worker/lib/worker-baseline-1");
            createArchive(archive, "META-INF/jarjar/fixture.jar", "fixture-v1");

            WorkerLibraryVerifier.SyncResult first = WorkerLibraryVerifier.synchronize(archive, libraries);
            check(first.changed(), "首次同步应安装 Worker 依赖");
            check(first.classpath().size() == 1, "应返回全部嵌入依赖");
            check(WorkerLibraryVerifier.verifyManifest(
                    libraries,
                    WorkerCompatibility.WORKER_LIBRARY_BASELINE
            ).valid(), "首次同步后的清单应通过校验");

            WorkerLibraryVerifier.SyncResult second = WorkerLibraryVerifier.synchronize(archive, libraries);
            check(!second.changed(), "内容未变时不应重复替换依赖");

            Files.writeString(libraries.resolve("fixture.jar"), "corrupted", StandardCharsets.UTF_8);
            check(!WorkerLibraryVerifier.verifyManifest(
                    libraries,
                    WorkerCompatibility.WORKER_LIBRARY_BASELINE
            ).valid(), "损坏依赖应被发现");
            WorkerLibraryVerifier.SyncResult repaired = WorkerLibraryVerifier.synchronize(archive, libraries);
            check(repaired.changed(), "损坏依赖应被修复");
            check("fixture-v1".equals(Files.readString(
                    libraries.resolve("fixture.jar"),
                    StandardCharsets.UTF_8
            )), "修复后应恢复发布 JAR 中的内容");

            createArchive(archive, "META-INF/jarjar/fixture.jar", "fixture-v2");
            WorkerLibraryVerifier.SyncResult upgraded = WorkerLibraryVerifier.synchronize(archive, libraries);
            check(upgraded.changed(), "嵌入依赖变化时应替换共享库");
            check("fixture-v2".equals(Files.readString(
                    libraries.resolve("fixture.jar"),
                    StandardCharsets.UTF_8
            )), "依赖升级后应使用新内容");

            Files.writeString(
                    libraries.resolve(WorkerLibraryVerifier.MANIFEST_FILE),
                    "broken\n",
                    StandardCharsets.UTF_8
            );
            check(!WorkerLibraryVerifier.verifyManifest(
                    libraries,
                    WorkerCompatibility.WORKER_LIBRARY_BASELINE
            ).valid(), "损坏清单应被发现");
            WorkerLibraryVerifier.synchronize(archive, libraries);
            check(WorkerLibraryVerifier.verifyManifest(
                    libraries,
                    WorkerCompatibility.WORKER_LIBRARY_BASELINE
            ).valid(), "重新同步后清单应恢复");
            System.out.println("Worker library verifier self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void createArchive(Path archive, String entryName, String content) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
