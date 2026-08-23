package io.ctyx.modpedia.client;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** 验证 1.12.2 发布 JAR 自带可启动的独立 Worker 包。 */
public final class LegacyWorkerPackagingSelfTest {
    private static final String WORKER_PREFIX = "META-INF/modpedia-worker/";
    private static final String MAIN_ENTRY = "io/ctyx/modpedia/worker/WorkerMain.class";

    private LegacyWorkerPackagingSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        String value = System.getProperty("modpedia.worker.jar", "").trim();
        assertTrue(!value.isEmpty(), "缺少 modpedia.worker.jar");
        Path archive = Paths.get(value);
        assertTrue(Files.isRegularFile(archive), "发布 JAR 不存在：" + archive);

        String slf4jVersion = System.getProperty("modpedia.slf4j.version", "2.0.9");
        boolean workerFound = false;
        boolean slf4jFound = false;
        try (ZipFile outer = new ZipFile(archive.toFile())) {
            String slf4jName = WORKER_PREFIX + "slf4j-api-" + slf4jVersion + ".jar";
            ZipEntry slf4j = outer.getEntry(slf4jName);
            assertTrue(slf4j != null, "发布 JAR 缺少 Worker 专用 SLF4J API：" + slf4jName);
            assertTrue(containsClass(outer, slf4j, "org/slf4j/LoggerFactory.class"),
                    "SLF4J API 缺少 org/slf4j/LoggerFactory.class");
            Enumeration<? extends ZipEntry> entries = outer.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/jarjar/slf4j-api-")) {
                    throw new AssertionError("SLF4J API 不得进入 META-INF/jarjar");
                }
                if (entry.isDirectory() || !entry.getName().startsWith(WORKER_PREFIX)
                        || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                if (containsWorkerMain(outer, entry)) {
                    workerFound = true;
                }
                if (entry.getName().equals(slf4jName)) {
                    slf4jFound = true;
                }
            }
        }
        assertTrue(workerFound, "发布 JAR 未找到包含 WorkerMain 的 Worker 包");
        assertTrue(slf4jFound, "发布 JAR 未找到预期版本的 SLF4J API");

        Path temporaryRoot = Files.createTempDirectory("modpedia-worker-package");
        try {
            Path installed = temporaryRoot.resolve("modpedia-1.12.2.jar");
            Files.copy(archive, installed, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(installed.equals(LegacyWorkerBridge.findWorkerArchive(temporaryRoot)),
                    "适配层未识别包含 Worker 包的 Mod JAR");

            Path extracted = temporaryRoot.resolve("lib");
            Files.createDirectories(extracted);
            Method method = LegacyWorkerBridge.class.getDeclaredMethod(
                    "extractNested", Path.class, Path.class
            );
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) method.invoke(
                    LegacyWorkerBridge.get(), archive, extracted
            );
            assertTrue(!files.isEmpty(), "适配层未提取 Worker 包");
            assertTrue(Files.isRegularFile(extracted.resolve("modpedia-worker.jar")),
                    "适配层未提取 Worker 主 JAR");
            assertTrue(Files.isRegularFile(extracted.resolve("gson-2.11.0.jar")),
                    "适配层未递归提取 Worker 专用 Gson");
            assertTrue(Files.isRegularFile(extracted.resolve("langchain4j-1.18.1.jar")),
                    "适配层未递归提取 Worker 依赖");
        } finally {
            Files.walk(temporaryRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // 自测清理失败不影响发布包断言结果。
                        }
                    });
        }
        System.out.println("LegacyWorkerPackagingSelfTest: OK");
    }

    private static boolean containsWorkerMain(ZipFile outer, ZipEntry nested) throws Exception {
        return containsClass(outer, nested, MAIN_ENTRY);
    }

    private static boolean containsClass(ZipFile outer, ZipEntry nested, String expected)
            throws Exception {
        InputStream input = outer.getInputStream(nested);
        try {
            ZipInputStream jar = new ZipInputStream(input);
            try {
                ZipEntry entry;
                while ((entry = jar.getNextEntry()) != null) {
                    if (expected.equals(entry.getName())) {
                        return true;
                    }
                }
            } finally {
                jar.close();
            }
        } finally {
            input.close();
        }
        return false;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
