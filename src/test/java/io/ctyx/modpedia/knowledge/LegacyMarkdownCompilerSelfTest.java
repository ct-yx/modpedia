package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Markdown 转换和来源边界回归测试。 */
public final class LegacyMarkdownCompilerSelfTest {
    private LegacyMarkdownCompilerSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path instance = Files.createTempDirectory("modpedia-112-markdown");
        try {
            Path book = instance.resolve("patchouli_books/example");
            write(book.resolve("book.json"), "{\"name\":\"示例手册\",\"landing_text\":\"欢迎\"}");
            write(book.resolve("zh_cn/categories/start.json"), "{\"name\":\"开始\",\"description\":\"入口\"}");
            write(book.resolve("zh_cn/entries/bee.json"),
                    "{\"name\":\"蜜蜂\",\"pages\":[{\"type\":\"text\",\"text\":\"蜜蜂页面正文\"},{\"type\":\"crafting\",\"recipe\":\"example:bee\"}]}" );
            ManualScanResult scan = new ManualCatalogScanner().scan(instance, "zh_cn");
            Path knowledge = instance.resolve("config/modpedia/knowledge");
            LegacyMarkdownCompiler.CompileResult result = new LegacyMarkdownCompiler()
                    .compile(instance, knowledge, scan);
            assertEquals(1, result.getSourceCount(), "source count");
            assertTrue(result.getDocumentCount() >= 3, "document count");
            List<Path> markdown;
            try (Stream<Path> stream = Files.walk(knowledge.resolve("sources"))) {
                markdown = stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".md"))
                        .collect(Collectors.toList());
            }
            String all = "";
            for (Path path : markdown) {
                all += new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
            assertTrue(all.contains("蜜蜂页面正文"), "page text");
            assertTrue(all.contains("example:bee"), "unknown recipe field");
            System.out.println("LegacyMarkdownCompilerSelfTest: OK");
        } finally {
            deleteTree(instance);
        }
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
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
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
