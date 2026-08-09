package io.ctyx.modpedia.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 验证基准装载器的语言选择、资源去重和依赖型 JAR 统计。 */
public final class KnowledgeBenchmarkFixtureSelfTest {
    private KnowledgeBenchmarkFixtureSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("modpedia-benchmark-fixture-");
        try {
            Path guideJar = temporary.resolve("fixture-guide.jar");
            Path dependencyJar = temporary.resolve("fixture-library.jar");
            writeGuideJar(guideJar);
            writeDependencyJar(dependencyJar);

            JarCorpusLoader.LoadedCorpus chinese = JarCorpusLoader.load(
                    List.of(guideJar, dependencyJar),
                    List.of(),
                    JarCorpusLoader.LanguageMode.ZH_CN
            );
            JarCorpusLoader.LoadedCorpus english = JarCorpusLoader.load(
                    List.of(guideJar, dependencyJar),
                    List.of(),
                    JarCorpusLoader.LanguageMode.EN_US
            );

            check(chinese.resources().size() == 2, "中文语料应包含 1 个 Patchouli 页面和 1 个 Markdown 页面");
            check(english.resources().size() == 2, "英文语料应包含 1 个 Patchouli 页面和 1 个 Markdown 页面");
            check(chinese.resources().stream().anyMatch(resource -> resource.path().contains("/zh_cn/")),
                    "中文模式应选择 zh_cn 页面");
            check(english.resources().stream().anyMatch(resource -> resource.path().contains("/en_us/")),
                    "英文模式应选择 en_us 页面");
            check("压力板".equals(chinese.resources().stream()
                    .filter(resource -> resource.sourceType().equals("patchouli_json"))
                    .findFirst().orElseThrow().translations().get("item.fixture.pressure")),
                    "中文模式应使用中文语言表");
            check("Pressure Plate".equals(english.resources().stream()
                    .filter(resource -> resource.sourceType().equals("patchouli_json"))
                    .findFirst().orElseThrow().translations().get("item.fixture.pressure")),
                    "英文模式应使用英文语言表");
            check(chinese.stats().skippedLocalePageCount() == 2, "其他 Patchouli 语言页面应被排除");
            check(chinese.stats().dependencyOnlyContainers().contains("fixture-library.jar"),
                    "没有手册资源的前置 JAR 应被记录为依赖型 JAR");
            check(chinese.stats().missingRequiredDependencies().isEmpty(),
                    "测试依赖 JAR 已提供时不应报告缺失必需依赖");
            System.out.println("ModPedia knowledge benchmark fixture self-test passed");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void writeGuideJar(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            add(zip, "META-INF/neoforge.mods.toml", """
                    [[mods]]
                    modId = "fixture"
                    version = "1.0"
                    displayName = "Fixture Guide"

                    [[dependencies.fixture]]
                    modId = "fixture_library"
                    type = "required"
                    """);
            add(zip, "assets/fixture/lang/en_us.json", "{\"item.fixture.pressure\":\"Pressure Plate\"}");
            add(zip, "assets/fixture/lang/zh_cn.json", "{\"item.fixture.pressure\":\"压力板\"}");
            add(zip, "assets/fixture/patchouli_books/book/en_us/entries/pressure.json", """
                    {"name":"item.fixture.pressure","pages":[{"type":"patchouli:text","text":"English pressure guide."}]}
                    """);
            add(zip, "assets/fixture/patchouli_books/book/zh_cn/entries/pressure.json", """
                    {"name":"item.fixture.pressure","pages":[{"type":"patchouli:text","text":"中文压力指南。"}]}
                    """);
            add(zip, "assets/fixture/patchouli_books/book/fr_fr/entries/pressure.json", """
                    {"name":"French pressure","pages":[{"type":"patchouli:text","text":"French guide."}]}
                    """);
            add(zip, "assets/fixture/guides/common.md", "# Common Guide\n\nA locale-independent GuideME page.\n");
        }
    }

    private static void writeDependencyJar(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            add(zip, "META-INF/neoforge.mods.toml", """
                    [[mods]]
                    modId = "fixture_library"
                    version = "1.0"
                    displayName = "Fixture Library"
                    """);
        }
    }

    private static void add(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
