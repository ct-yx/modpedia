package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.knowledge.LocalGuideScanner;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 验证 SQLite、custom Markdown 指纹导入、删除、覆盖和语言过滤。 */
public final class KnowledgeDatabaseSelfTest {
    private KnowledgeDatabaseSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("modpedia-knowledge-database-test-");
        try {
            Path config = temporary.resolve("config");
            Path custom = config.resolve("modpedia/knowledge/custom");
            Files.createDirectories(custom);

            Path note = custom.resolve("note.md");
            write(note, customDocument(
                    "custom:note",
                    "neutral",
                    "自定义笔记",
                    "人工导入的完整 Markdown 内容。"
            ));
            Path chinese = custom.resolve("bilingual-zh.md");
            write(chinese, customDocument(
                    "custom:bilingual",
                    "zh_cn",
                    "中文手册",
                    "中文自定义搜索内容。"
            ));
            Path english = custom.resolve("bilingual-en.md");
            write(english, customDocument(
                    "custom:bilingual",
                    "en_us",
                    "English Manual",
                    "English custom search content."
            ));

            Path override = custom.resolve("override.md");
            write(override, customDocument(
                    "ae2:controller",
                    "neutral",
                    "人工覆盖控制器",
                    "人工覆盖内容应优先于自动手册。"
            ));
            Path invalidNew = custom.resolve("invalid-new.md");
            write(invalidNew, "没有 Front Matter 的文件\n");

            List<ScannedResource> generated = List.of(generatedController());
            KnowledgeCompiler compiler = new KnowledgeCompiler();
            KnowledgeCompiler.CompileResult first = compiler.compile(
                    config,
                    new LocalGuideScanner.ScanResult(generated, List.of()),
                    true
            );
            Path knowledgeRoot = first.knowledgeRoot();
            Path database = KnowledgeDatabase.path(knowledgeRoot);
            check(Files.isRegularFile(database), "首次启动应生成 SQLite 数据库");
            check(KnowledgeDatabase.isUsable(database), "SQLite 数据库应可用且包含 FTS5 表");

            RetrievalService service = new RetrievalService(knowledgeRoot);
            check(service.search("人工导入").hasResults(), "新增 custom 文档应可搜索");
            check(service.search("ae2:controller").hasResults(), "稳定文档 ID 应可精确搜索");
            SearchResponse imported = service.search("人工导入");
            check(imported.results().stream().anyMatch(result -> "custom:note".equals(result.documentId())),
                    "custom 文档应返回稳定 ID，实际结果=" + imported.results());
            check(service.readMarkdown("custom:note").orElseThrow().contains("完整 Markdown"),
                    "数据库应保存完整 Markdown");
            check(service.readMarkdown("custom:note").orElseThrow().contains("custom_note: keep"),
                    "数据库应保留自定义 Front Matter 和原始 Markdown");
            check("ae2:controller".equals(service.search("控制器").results().get(0).documentId()),
                    "自定义 ID 文档应覆盖自动文档");
            check(service.search(new SearchQuery("中文自定义", 8, SearchLanguage.ZH_CN)).hasResults(),
                    "中文 custom 文档应可按中文语言过滤");
            check(service.search(new SearchQuery("English custom", 8, SearchLanguage.EN_US)).hasResults(),
                    "英文 custom 文档应可按英文语言过滤");

            Map<String, KnowledgeDatabase.CachedDocument> cached =
                    KnowledgeDatabase.readCachedCustomDocuments(database);
            KnowledgeDatabase.SyncResult reused = KnowledgeDatabase.sync(
                    knowledgeRoot,
                    cached.values().stream().map(KnowledgeDatabase.CachedDocument::input).toList(),
                    false
            );
            check(reused.updatedCount() == 0, "相同 custom 指纹不应重新写入文档");
            check(reused.reusedCount() == cached.size(), "相同 custom 指纹应复用 SQLite 记录");

            write(note, customDocument(
                    "custom:note",
                    "neutral",
                    "自定义笔记",
                    "修改后的启动自动导入内容。"
            ));
            compiler.compile(config, new LocalGuideScanner.ScanResult(generated, List.of()), false);
            service.reload();
            check(service.search("修改后的").hasResults(), "修改 custom 文件后应增量更新 SQLite");

            write(note, "---\nid: custom:note\n这是未闭合的 Front Matter\n");
            compiler.compile(config, new LocalGuideScanner.ScanResult(generated, List.of()), false);
            service.reload();
            check(service.search("修改后的").hasResults(), "非法修改应保留上一份有效 custom 记录");
            compiler.compile(config, new LocalGuideScanner.ScanResult(generated, List.of()), true);
            service.reload();
            check(service.search("修改后的").hasResults(), "强制重建时非法 custom 修改也应保留上一份有效记录");

            Files.delete(override);
            compiler.compile(config, new LocalGuideScanner.ScanResult(generated, List.of()), false);
            service.reload();
            check(service.search("人工覆盖").status() == SearchStatus.NO_MATCH,
                    "删除 custom 文件后应删除对应 SQLite 记录");
            check(service.search("自动手册").hasResults(), "删除覆盖文档后应恢复自动手册记录");

            write(note, customDocument(
                    "custom:note",
                    "neutral",
                    "自定义笔记",
                    "数据库重建时重新导入的内容。"
            ));
            Files.writeString(database, "corrupt", StandardCharsets.UTF_8);
            compiler.compile(config, new LocalGuideScanner.ScanResult(generated, List.of()), false);
            service.reload();
            check(KnowledgeDatabase.isUsable(database), "损坏数据库应在启动编译时重建");
            check(service.search("数据库重建").hasResults(), "数据库重建后 custom 文档仍应可搜索");
            check(!service.search("没有 Front Matter").hasResults(), "缺少 ID 的新文档应被跳过");

            verifyTransactionRollback(temporary.resolve("transaction"));
            System.out.println("ModPedia SQLite knowledge self-test passed");
        } finally {
            deleteTree(temporary);
        }
    }

    private static ScannedResource generatedController() {
        return new ScannedResource(
                "ae2",
                "Applied Energistics 2",
                "19.2.17",
                "assets/ae2/guides/controller.md",
                "guideme_markdown",
                "# 自动控制器\n\n自动手册内容。",
                "generated-controller-v1",
                Map.of()
        );
    }

    private static String customDocument(String id, String language, String title, String body) {
        return "---\n"
                + "id: '" + id + "'\n"
                + "source_type: 'manual_annotation'\n"
                + "custom_note: keep\n"
                + "language: '" + language + "'\n"
                + "title: '" + title + "'\n"
                + "keywords: ['" + title + "']\n"
                + "---\n\n"
                + "# " + title + "\n\n"
                + body + "\n";
    }

    private static void verifyTransactionRollback(Path root) throws Exception {
        Path knowledgeRoot = root.resolve("config/modpedia/knowledge");
        KnowledgeDocument original = new KnowledgeDocument(
                "transaction:original",
                "test",
                "custom_markdown",
                "事务旧文档",
                "custom",
                List.of("事务旧文档"),
                "test",
                "custom/transaction.md",
                "# 事务旧文档\n\n提交前仍可搜索。"
        );
        KnowledgeDatabase.DocumentInput originalInput = new KnowledgeDatabase.DocumentInput(
                "custom:transaction.md",
                "original-fingerprint",
                "custom/transaction.md",
                "neutral",
                100,
                original
        );
        KnowledgeDatabase.sync(knowledgeRoot, List.of(originalInput), true);
        RetrievalService service = new RetrievalService(knowledgeRoot);
        check(service.search("提交前").hasResults(), "事务回滚测试的初始记录应可搜索");

        KnowledgeDocument conflicting = new KnowledgeDocument(
                "transaction:conflicting",
                "test",
                "custom_markdown",
                "事务冲突文档",
                "custom",
                List.of("事务冲突文档"),
                "test",
                "custom/conflicting.md",
                "# 事务冲突文档\n\n这次导入应失败。"
        );
        KnowledgeDatabase.DocumentInput conflictingInput = new KnowledgeDatabase.DocumentInput(
                originalInput.sourceKey(),
                "conflicting-fingerprint",
                "custom/conflicting.md",
                "neutral",
                100,
                conflicting
        );
        boolean failed = false;
        try {
            KnowledgeDatabase.sync(knowledgeRoot, List.of(originalInput, conflictingInput), false);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "冲突输入应触发事务失败");
        service.reload();
        check(service.search("提交前").hasResults(), "事务失败后旧 SQLite 数据仍应可搜索");
        check(service.search("这次导入").status() == SearchStatus.NO_MATCH,
                "事务失败后不应暴露未提交的新记录");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
