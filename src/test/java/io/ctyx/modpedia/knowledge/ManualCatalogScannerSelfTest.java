package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 不需要启动 Minecraft 的第一版来源扫描回归测试。 */
public final class ManualCatalogScannerSelfTest {
    private ManualCatalogScannerSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path instance = Files.createTempDirectory("modpedia-112-catalog");
        try {
            createExternalBook(instance);
            createArchive(instance.resolve("mods").resolve("example.jar"));

            ManualScanResult result = new ManualCatalogScanner().scan(instance, "fr_fr");
            assertEquals(1, result.getArchiveCount(), "archive count");
            assertHas(result.getSources(), "greedycraft-guide", KnowledgeContentKind.WIKI,
                    ManualSourceType.PATCHOULI_1_12_JSON, "zh_cn");
            assertHas(result.getSources(), "example:guide", KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.PATCHOULI_1_12_JSON, "zh_cn");
            assertHas(result.getSources(), "example:book:tconstruct", KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.MANTLE_BOOK_1_12, "en_us");
            assertHas(result.getSources(), "example:book:logisticspipes", KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.LOGISTICS_PIPES_BOOK_1_12, "en_us");
            assertHas(result.getSources(), "example:guide_api:chisel_guide", KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.GUIDE_API_1_12, "en_us");
            assertHas(result.getSources(), "example:books:bloodmagic", KnowledgeContentKind.MOD_MANUAL,
                    ManualSourceType.GUIDE_API_1_12, "neutral");
            assertNotHas(result.getSources(), "disabled");

            Path manifest = ManualCatalogWriter.writeManifest(
                    instance.resolve("config/modpedia/knowledge"), result
            );
            String json = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"content_kind\":\"wiki\""), "manifest content kind");
            assertTrue(json.contains("assets/example/patchouli_books/guide"), "jar source path");
            System.out.println("ManualCatalogScannerSelfTest: OK");
        } finally {
            deleteTree(instance);
        }
    }

    private static void createExternalBook(Path instance) throws IOException {
        Path root = instance.resolve("patchouli_books/greedycraft_guide_book");
        Files.createDirectories(root.resolve("zh_cn/categories"));
        Files.createDirectories(root.resolve("en_us/categories"));
        write(root.resolve("book.json"), "{\"name\":\"\u00a7c\u8d2a\u5a6b\u6307\u5357\"}");
        write(root.resolve("zh_cn/categories/intro.json"), "{}");
        write(root.resolve("en_us/categories/intro.json"), "{}");
    }

    private static void createArchive(Path archive) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "mcmod.info", "[{\"modid\":\"example\",\"name\":\"Example\",\"version\":\"1.0\"}]");
            entry(zip, "assets/example/patchouli_books/guide/book.json", "{\"name\":\"Example Guide\"}");
            entry(zip, "assets/example/patchouli_books/guide/zh_cn/entries/intro.json", "{}");
            entry(zip, "assets/example/patchouli_books/guide/en_us/entries/intro.json", "{}");
            entry(zip, "assets/example/patchouli_books_disabled/old/book.json", "{\"name\":\"Old\"}");
            entry(zip, "assets/tconstruct/book/en_us/index.json", "{}");
            entry(zip, "assets/logisticspipes/book/en_us/main_menu.md", "# Logistics Pipes");
            entry(zip, "assets/chisel_guide/guide.json", "{}");
            entry(zip, "assets/chisel_guide/guide/index.json", "{}");
            entry(zip, "assets/chisel_guide/lang/en_US.lang", "guide.title=Guide");
            entry(zip, "assets/bloodmagic/books/architect.xml", "<book/>");
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertHas(
            List<ManualSourceDescriptor> sources,
            String sourceId,
            KnowledgeContentKind kind,
            ManualSourceType type,
            String language
    ) {
        for (ManualSourceDescriptor source : sources) {
            if (sourceId.equals(source.getSourceId())
                    && kind == source.getContentKind()
                    && type == source.getSourceType()
                    && language.equals(source.getLanguage())) {
                return;
            }
        }
        throw new AssertionError("missing source: " + sourceId);
    }

    private static void assertNotHas(List<ManualSourceDescriptor> sources, String text) {
        for (ManualSourceDescriptor source : sources) {
            if (source.getSourceId().contains(text) || source.getSourcePath().contains(text)) {
                throw new AssertionError("unexpected source: " + text);
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
