package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 验证 Worker 可以从常见实例目录布局扫描手册资源。 */
public final class WorkerGuideScannerSelfTest {
    private WorkerGuideScannerSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-worker-guide-scan-");
        try {
            Path gameDirectory = root.resolve("instance");
            Path modsDirectory = gameDirectory.resolve("mods").resolve("profile");
            Files.createDirectories(modsDirectory);
            writeFixture(modsDirectory.resolve("example-guide.jar"));

            WorkerGuideScanner scanner = new WorkerGuideScanner();
            WorkerGuideScanner.ScanResult scan = scanner.scan(
                    gameDirectory.resolve("mods"),
                    gameDirectory.resolve("config/modpedia/knowledge")
            );
            check(scan.warnings().isEmpty(), "嵌套 mods 目录不应产生扫描警告：" + scan.warnings());
            check(scan.resources().size() == 4,
                    "应扫描 Patchouli 书籍根、条目和去重后的 GuideME 资源，实际=" + scan.resources().size());
            check(scan.resources().stream()
                            .filter(resource -> resource.path().contains("patchouli_books"))
                            .allMatch(resource -> "patchouli_json".equals(resource.sourceType())),
                    "Patchouli 资源应使用 patchouli_json 来源格式");
            check(scan.resources().stream().map(ScannedResource::path).noneMatch(
                    path -> path.endsWith("unrelated.json")),
                    "普通 JSON 不应进入知识扫描");
            check(scan.resources().stream().map(ScannedResource::path).anyMatch(
                    path -> path.endsWith("ae2guide/_zh_cn/index.md")),
                    "GuideME 应优先选择 zh_cn 目录");
            check(scan.resources().stream().map(ScannedResource::path).noneMatch(
                    path -> path.endsWith("ae2guide/index.md")
                            || path.endsWith("ae2guide/_es_es/index.md")),
                    "GuideME 不应同时导入基础版和非首选地区翻译");
            check(scan.resources().stream().map(ScannedResource::path).anyMatch(
                    path -> path.endsWith("ae2guide/only-base.md")),
                    "GuideME 缺少本地化页面时应回退到基础文件");

            Path empty = root.resolve("empty-mods");
            Files.createDirectories(empty);
            WorkerGuideScanner.ScanResult emptyScan = scanner.scan(
                    empty,
                    root.resolve("knowledge")
            );
            check(emptyScan.resources().isEmpty(), "空 mods 目录不应产生虚假资源");
            check(emptyScan.warnings().stream().anyMatch(message -> message.contains("没有可扫描")),
                    "空 mods 目录必须留下可诊断警告");

            Path configuredMods = gameDirectory.resolve("mods");
            Path requestedEmpty = root.resolve("launcher-view/mods");
            Files.createDirectories(requestedEmpty);
            Path resolved = WorkerKnowledgeService.resolveModsDirectory(
                    requestedEmpty,
                    gameDirectory.resolve("config")
            );
            check(resolved.equals(configuredMods.toAbsolutePath().normalize()),
                    "Worker 应从配置目录父级回退到实际 mods 目录：" + resolved);

            System.out.println("ModPedia Worker guide scanner self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void writeFixture(Path archive) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream output = Files.newOutputStream(
                archive,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        ); ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "META-INF/neoforge.mods.toml", "modId=\"example\"\ndisplayName=\"Example\"\n");
            entry(zip, "assets/example/lang/zh_cn.json", "{\"item.example.guide\":\"指南\"}");
            entry(zip, "assets/example/patchouli_books/guide/en_us/book.json", "{\"name\":\"Example Guide\"}");
            entry(zip, "assets/example/patchouli_books/guide/en_us/entries/start.json", "{\"name\":\"Start\",\"pages\":[{\"type\":\"text\",\"text\":\"开始\"}]}");
            entry(zip, "assets/example/ae2guide/index.md", "# Base Guide");
            entry(zip, "assets/example/ae2guide/_zh_cn/index.md", "# 中文指南");
            entry(zip, "assets/example/ae2guide/_es_es/index.md", "# Guía");
            entry(zip, "assets/example/ae2guide/only-base.md", "# Base Only");
            entry(zip, "assets/example/unrelated.json", "{\"not\":\"a guide\"}");
        }
    }

    private static void entry(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
