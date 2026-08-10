package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.AppGuideDocumentConverter;
import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.knowledge.LocalGuideScanner;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** APP 手册资源、内容模组归属和 SQLite 增量导入回归。 */
public final class AppGuideAdapterSelfTest {
    private AppGuideAdapterSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("modpedia-app-adapter-");
        try {
            testCorpusOwnership(temporary);
            testConversionAndIncrementalImport(temporary);
            System.out.println("ModPedia APP guide adapter self-test passed");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testCorpusOwnership(Path temporary) throws Exception {
        Path contentJar = temporary.resolve("content.jar");
        Path patchouliJar = temporary.resolve("patchouli-framework.jar");
        Path guideMeJar = temporary.resolve("guideme-framework.jar");
        Path modonomiconJar = temporary.resolve("modonomicon-framework.jar");
        writeContentJar(contentJar);
        writeFrameworkJar(patchouliJar, "patchouli");
        writeFrameworkJar(guideMeJar, "guideme");
        writeFrameworkJar(modonomiconJar, "modonomicon");

        JarCorpusLoader.LoadedCorpus corpus = JarCorpusLoader.load(
                List.of(contentJar, patchouliJar, guideMeJar, modonomiconJar),
                List.of(),
                JarCorpusLoader.LanguageMode.ZH_CN
        );
        check(corpus.resources().stream().allMatch(resource -> "content".equals(resource.modId())),
                "APP 手册来源必须归属实际内容模组，不能归属前置框架");
        check(corpus.resources().stream().anyMatch(resource -> "app_json".equals(resource.sourceType())),
                "应识别 APP JSON 手册资源");
        check(corpus.resources().stream().anyMatch(resource -> resource.path().contains("/book/book.json")),
                "应识别书籍资源");
        check(corpus.resources().stream().anyMatch(resource -> resource.path().contains("/categories/basics.json")),
                "应识别分类资源");
        check(corpus.stats().dependencyOnlyContainers().containsAll(List.of(
                        "patchouli-framework.jar",
                        "guideme-framework.jar",
                        "modonomicon-framework.jar"
                )),
                "无手册本体的三个前置框架 JAR 应记录为依赖型 JAR");
    }

    private static void testConversionAndIncrementalImport(Path temporary) throws Exception {
        Path config = temporary.resolve("config");
        String json = """
                {
                  "book_id": "book",
                  "name": "book.content.title",
                  "entries": [
                    {
                      "id": "pressure",
                      "name": "entry.pressure",
                      "category": "basics",
                      "pages": [
                        {"type":"text","text":"item.pressure $(br)\n\n```text\npressure\n```"},
                        {"type":"recipe","recipe":{"item":"content:pressure","count":1}}
                      ]
                    },
                    {
                      "id": "unknown",
                      "title": "entry.unknown",
                      "pages": [{"type":"new_page","unhandled":{"value":"kept"}}]
                    }
                  ]
                }
                """;
        ScannedResource source = new ScannedResource(
                "content",
                "Content Mod",
                "1.0.0",
                "data/content/modonomicon/books/book/entries/pressure.json",
                "app_json",
                json,
                "fingerprint-1",
                Map.of(
                        "book.content.title", "内容手册",
                        "entry.pressure", "压力容器",
                        "entry.unknown", "未知条目",
                        "item.pressure", "压力"
                )
        );

        AppGuideDocumentConverter converter = new AppGuideDocumentConverter();
        List<KnowledgeDocument> documents = converter.convertAll(source);
        check(documents.size() == 2, "一个 APP 资源中的多个 entries 应展开为多个文档");
        KnowledgeDocument pressure = documents.stream()
                .filter(document -> document.id().endsWith("/pressure"))
                .findFirst()
                .orElseThrow();
        check("压力容器".equals(pressure.title()), "应解析内容模组语言表中的条目名称");
        check(pressure.sourcePath().contains("#book=book"), "应保留书籍页级导航锚点");
        check(pressure.body().contains("```text"), "正文代码块必须保留");
        check(pressure.body().contains("content:pressure"), "配方/物品结构必须保留");
        KnowledgeDocument unknown = documents.stream()
                .filter(document -> document.id().endsWith("/unknown"))
                .findFirst()
                .orElseThrow();
        check(unknown.body().contains("unhandled"), "未知页面节点必须保留为可读 JSON");

        ScannedResource actualShape = new ScannedResource(
                "content",
                "Content Mod",
                "1.0.0",
                "data/content/modonomicon/books/book/entries/basics/pressure_v2.json",
                "app_json",
                """
                        {
                          "category": "content:basics",
                          "name": "entry.pressure",
                          "pages": [
                            {"type":"modonomicon:spotlight","item":{"item":"content:pressure"},"text":"item.pressure"},
                            {"type":"modonomicon:crafting_recipe","recipe_id_1":"content:pressure_recipe","unknown_node":{"kept":true}}
                          ]
                        }
                        """,
                "fingerprint-shape",
                Map.of("entry.pressure", "压力容器", "item.pressure", "压力")
        );
        KnowledgeDocument actualDocument = converter.convertAll(actualShape).getFirst();
        check("content:app/book/basics/pressure_v2".equals(actualDocument.id()),
                "实际书籍路径应生成稳定的书籍/分类/条目 ID");
        check(actualDocument.sourcePath().contains("entry=pressure_v2"),
                "实际书籍路径应保留条目锚点");
        check(actualDocument.body().contains("content:pressure_recipe"),
                "配方字段应进入 Markdown");
        check(actualDocument.body().contains("unknown_node"),
                "未知页面字段应以 JSON 保留");

        KnowledgeDocument bookDocument = converter.convertAll(new ScannedResource(
                "content", "Content Mod", "1.0.0",
                "data/content/modonomicon/books/book/book.json", "app_json",
                "{\"name\":\"book.content.title\"}", "fingerprint-book",
                Map.of("book.content.title", "内容手册")
        )).getFirst();
        check(bookDocument.id().endsWith("/general/__book"), "书籍资源应生成书籍概览文档");
        check(!bookDocument.sourcePath().contains("entry="), "书籍概览只能跳转到书籍根页面");

        KnowledgeDocument categoryDocument = converter.convertAll(new ScannedResource(
                "content", "Content Mod", "1.0.0",
                "data/content/modonomicon/books/book/categories/basics.json", "app_json",
                "{\"name\":\"category.basics\"}", "fingerprint-category",
                Map.of("category.basics", "基础")
        )).getFirst();
        check(categoryDocument.id().endsWith("/basics/__category"), "分类资源应生成分类概览文档");
        check(!categoryDocument.sourcePath().contains("entry="), "分类概览不能伪造条目跳转");

        KnowledgeCompiler compiler = new KnowledgeCompiler();
        KnowledgeCompiler.CompileResult first = compiler.compile(
                config,
                new LocalGuideScanner.ScanResult(List.of(source), List.of())
        );
        check(first.report().documentCount() == 2, "编译结果应包含两个 APP 文档");
        RetrievalService retrieval = new RetrievalService(first.knowledgeRoot());
        SearchResult result = retrieval.search("压力").results().stream().findFirst().orElseThrow();
        check("app_json".equals(result.sourceType()), "SQLite 搜索结果应保留 APP sourceType");
        check(result.sourcePath().contains("entry=pressure"), "搜索结果应保留页级来源路径");

        KnowledgeCompiler.CompileResult reused = compiler.compile(
                config,
                new LocalGuideScanner.ScanResult(List.of(source), List.of())
        );
        check(reused.report().reusedCount() == 2, "内容和指纹未变化时应复用两个 APP 文档");

        ScannedResource changed = new ScannedResource(
                source.modId(), source.modName(), source.version(), source.path(), source.sourceType(),
                json, "fingerprint-2", Map.of(
                        "book.content.title", "内容手册",
                        "entry.pressure", "新的压力容器",
                        "entry.unknown", "未知条目",
                        "item.pressure", "压力"
                )
        );
        KnowledgeCompiler.CompileResult updated = compiler.compile(
                config,
                new LocalGuideScanner.ScanResult(List.of(changed), List.of())
        );
        check(updated.report().updatedCount() == 2, "APP 内容变化后应重新生成逻辑文档");
        SearchResponse changedSearch = new RetrievalService(updated.knowledgeRoot()).search("新的压力");
        check(changedSearch.results().stream()
                        .anyMatch(item -> item.title().contains("压力容器")),
                "修改后的 APP 文档应可被 SQLite 检索");
    }

    private static void writeContentJar(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            add(zip, "META-INF/neoforge.mods.toml", """
                    [[mods]]
                    modId = "content"
                    version = "1.0"
                    displayName = "Content Mod"
                    """);
            add(zip, "assets/content/lang/zh_cn.json", """
                    {"book.content.title":"内容手册","category.basics":"基础","entry.pressure":"压力容器","item.pressure":"压力"}
                    """);
            add(zip, "data/content/modonomicon/books/book/book.json", """
                    {"name":"book.content.title"}
                    """);
            add(zip, "data/content/modonomicon/books/book/categories/basics.json", """
                    {"name":"category.basics"}
                    """);
            add(zip, "data/content/modonomicon/books/book/entries/pressure.json", """
                    {"book_id":"book","name":"book.content.title","entries":[{"id":"pressure","name":"entry.pressure","pages":[{"type":"text","text":"item.pressure"}]}]}
                    """);
        }
    }

    private static void writeFrameworkJar(Path path, String modId) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            add(zip, "META-INF/neoforge.mods.toml", """
                    [[mods]]
                    modId = "%s"
                    version = "1.0"
                    displayName = "Manual Framework"
                    """.formatted(modId));
            add(zip, "data/" + modId + "/modonomicon/books/framework/book.json", """
                    {"book_id":"framework","pages":[{"type":"text","text":"framework implementation detail"}]}
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
