package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.KnowledgeScanResult;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

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
            Path lowPriority = custom.resolve("low-priority.md");
            write(lowPriority, customDocument(
                    "custom:low-priority",
                    "neutral",
                    "低优先级自定义文档",
                    "低优先级自定义内容。",
                    20
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

            KnowledgeCompiler compiler = new KnowledgeCompiler();
            KnowledgeCompiler.CompileResult first = compiler.compile(
                    config,
                    new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()),
                    true
            );
            Path knowledgeRoot = first.knowledgeRoot();
            Path database = KnowledgeDatabase.path(knowledgeRoot);
            check(Files.isRegularFile(database), "首次启动应生成 SQLite 数据库");
            check(KnowledgeDatabase.isUsable(database), "SQLite 数据库应可用且包含 FTS5 表");
            KnowledgeDatabase.DatabaseStats stats = KnowledgeDatabase.inspect(database);
            check("external-content".equals(stats.ftsStorage()), "当前 FTS5 应使用 external-content");
            check(stats.ftsContentBytes() == 0, "FTS 不应重复保存正文内容表");
            check(stats.ftsRowCount() == stats.segmentCount(), "FTS 与 segments 段落数应一致");
            check(KnowledgeDatabase.explainSearchPlan(
                            database,
                            SearchQuery.of("人工导入"),
                            SearchLanguage.ZH_CN,
                            Map.of()
                    ).stream().noneMatch(value -> value.contains("TEMP B-TREE")),
                    "FTS5 rank 排序不应产生排序临时表");

            RetrievalService service = new RetrievalService(knowledgeRoot);
            check(service.search("人工导入").hasResults(), "新增 custom 文档应可搜索");
            SearchResponse bodyOnlySearch = service.search("正文专有机器");
            check(bodyOnlySearch.results().stream()
                            .anyMatch(result -> result.documentId().contains("body-only")),
                    "只出现在正文中的中文实体也应可搜索");
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
            check(cached.containsKey("custom/low-priority.md"),
                    "自定义文档复用必须按 custom 来源识别，不能依赖 priority >= 100");
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
            compiler.compile(config, new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()), false);
            service.reload();
            check(service.search("修改后的").hasResults(), "修改 custom 文件后应增量更新 SQLite");

            write(note, "---\nid: custom:note\n这是未闭合的 Front Matter\n");
            compiler.compile(config, new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()), false);
            service.reload();
            check(service.search("修改后的").hasResults(), "非法修改应保留上一份有效 custom 记录");
            compiler.compile(config, new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()), true);
            service.reload();
            check(service.search("修改后的").hasResults(), "强制重建时非法 custom 修改也应保留上一份有效记录");

            Files.delete(override);
            compiler.compile(config, new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()), false);
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
            compiler.compile(config, new KnowledgeScanResult(List.of(generatedController(), generatedBodyOnly()), List.of()), false);
            service.reload();
            check(KnowledgeDatabase.isUsable(database), "损坏数据库应在启动编译时重建");
            check(service.search("数据库重建").hasResults(), "数据库重建后 custom 文档仍应可搜索");
            check(!service.search("没有 Front Matter").hasResults(), "缺少 ID 的新文档应被跳过");

            verifyTransactionRollback(temporary.resolve("transaction"));
            verifyWikiFailureKeepsPrevious(temporary.resolve("wiki-failure"));
            verifyPreviousDatabaseRecovery(temporary.resolve("database-recovery"));
            verifyCompilerReportsDatabaseFailure(temporary.resolve("compiler-failure"));
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

    private static ScannedResource generatedBodyOnly() {
        return new ScannedResource(
                "ae2",
                "Applied Energistics 2",
                "19.2.17",
                "assets/ae2/guides/body-only.md",
                "guideme_markdown",
                "# 通用手册\n\n正文专有机器只在这一段出现。",
                "generated-body-only-v1",
                Map.of()
        );
    }

    private static String customDocument(String id, String language, String title, String body) {
        return customDocument(id, language, title, body, 100);
    }

    private static String customDocument(
            String id,
            String language,
            String title,
            String body,
            int priority
    ) {
        return "---\n"
                + "id: '" + id + "'\n"
                + "source_type: 'manual_annotation'\n"
                + "custom_note: keep\n"
                + "language: '" + language + "'\n"
                + "title: '" + title + "'\n"
                + "priority: " + priority + "\n"
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

        KnowledgeDocument localized = new KnowledgeDocument(
                original.id(),
                original.sourceMod(),
                original.sourceType(),
                "事务中文文档",
                original.category(),
                List.of("事务中文文档"),
                original.sourceVersion(),
                original.sourcePath(),
                "# 事务中文文档\n\n语言切换后仍可搜索。"
        );
        KnowledgeDatabase.sync(
                knowledgeRoot,
                List.of(new KnowledgeDatabase.DocumentInput(
                        originalInput.sourceKey(),
                        "localized-fingerprint",
                        originalInput.relativePath(),
                        "zh_cn",
                        originalInput.priority(),
                        localized
                )),
                false
        );
        service.reload();
        check(service.search(new SearchQuery("语言切换", 8, SearchLanguage.ZH_CN)).hasResults(),
                "同一 source_key 切换语言后应替换旧 neutral 记录");

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
        KnowledgeDatabase.DocumentInput localizedInput = new KnowledgeDatabase.DocumentInput(
                originalInput.sourceKey(),
                "localized-fingerprint",
                originalInput.relativePath(),
                "zh_cn",
                originalInput.priority(),
                localized
        );
        try {
            KnowledgeDatabase.sync(knowledgeRoot, List.of(localizedInput, conflictingInput), false);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "冲突输入应触发事务失败");
        service.reload();
        check(service.search("语言切换").hasResults(), "事务失败后旧 SQLite 数据仍应可搜索");
        check(service.search("这次导入").status() == SearchStatus.NO_MATCH,
                "事务失败后不应暴露未提交的新记录");
    }

    private static void verifyWikiFailureKeepsPrevious(Path root) throws Exception {
        Path config = root.resolve("config");
        Path source = config.resolve("modpedia/knowledge/sources/test-wiki");
        Path documents = source.resolve("documents");
        Files.createDirectories(documents);
        write(source.resolve("source.json"), """
                {"source_id":"test-wiki","collection_id":"test","content_kind":"wiki",
                 "source_type":"wiki_markdown","origin_type":"local","title":"测试 Wiki",
                 "language":"zh_cn","version":"1","documents_root":"documents"}
                """);
        write(documents.resolve("guide.md"), "# 有效 Wiki\n\n旧版 Wiki 仍可搜索。\n");
        KnowledgeCompiler compiler = new KnowledgeCompiler();
        compiler.compile(config, new KnowledgeScanResult(List.of(), List.of()), true);
        RetrievalService service = new RetrievalService(config.resolve("modpedia/knowledge"));
        SearchResponse wikiSearch = service.search(new SearchQuery(
                "搜索", 8, SearchLanguage.ZH_CN, KnowledgeScope.WIKI));
        check(wikiSearch.hasResults(), "有效 Wiki 初次构建应可搜索");

        write(source.resolve("source.json"), "{损坏 JSON");
        boolean failed = false;
        try {
            compiler.compile(config, new KnowledgeScanResult(List.of(), List.of()), false);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "损坏 Wiki 应终止本次构建");
        service.reload();
        check(service.search(new SearchQuery("搜索", 8, SearchLanguage.ZH_CN, KnowledgeScope.WIKI)).hasResults(),
                "损坏 source.json 后应保留旧 Wiki 索引");

        // source.json 修复后，Markdown 本体即使变成非法 UTF-8，也必须走同一条
        // “本次构建失败、正式 SQLite 不替换”的保护路径。
        write(source.resolve("source.json"), """
                {"source_id":"test-wiki","collection_id":"test","content_kind":"wiki",
                 "source_type":"wiki_markdown","origin_type":"local","title":"测试 Wiki",
                 "language":"zh_cn","version":"1","documents_root":"documents"}
                """);
        Files.write(documents.resolve("guide.md"), new byte[]{(byte) 0xC3, (byte) 0x28});
        failed = false;
        try {
            compiler.compile(config, new KnowledgeScanResult(List.of(), List.of()), false);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "非法 UTF-8 Wiki 应终止本次构建");
        service.reload();
        check(service.search(new SearchQuery("搜索", 8, SearchLanguage.ZH_CN, KnowledgeScope.WIKI)).hasResults(),
                "非法 UTF-8 Wiki 后应继续保留旧索引");
    }

    private static void verifyPreviousDatabaseRecovery(Path root) throws Exception {
        Path knowledgeRoot = root.resolve("config/modpedia/knowledge");
        KnowledgeDocument old = new KnowledgeDocument(
                "recovery:old", "test", "custom_markdown", "恢复旧库", "test",
                List.of("恢复旧库"), "1", "custom/recovery.md", "# 恢复旧库\n\n恢复旧库专有内容。"
        );
        KnowledgeDatabase.DocumentInput input = new KnowledgeDatabase.DocumentInput(
                "custom:recovery", "recovery-fingerprint", "custom/recovery.md", "neutral", 100, old
        );
        KnowledgeDatabase.sync(knowledgeRoot, List.of(input), true);
        Path database = KnowledgeDatabase.path(knowledgeRoot);
        Path backup = database.resolveSibling("knowledge.db.previous");
        Files.move(database, backup);
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        check(KnowledgeDatabase.isUsable(database), "正式库缺失时应从 .previous 恢复");
        RetrievalService service = new RetrievalService(knowledgeRoot);
        check(service.search("恢复旧库专有内容").hasResults(), "恢复后的旧库内容应可搜索");

        // 模拟“旧库已备份、替换动作尚未完成”的崩溃窗口：下次启动应清理
        // 孤立 staged 文件并恢复可用旧库，而不是把旧库误判为新版本。
        Path staged = knowledgeRoot.resolve("knowledge-crash-window.db.tmp");
        Files.copy(database, staged);
        Files.writeString(
                database.resolveSibling("knowledge.db.replace-state"),
                "sidecars-cleared\nknowledge-crash-window.db.tmp\n",
                StandardCharsets.UTF_8
        );
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        check(KnowledgeDatabase.isUsable(database), "替换中断后应恢复正式库");
        check(!Files.exists(staged), "替换中断后应清理孤立 staged 文件");
        check(!Files.exists(database.resolveSibling("knowledge.db.replace-state")),
                "替换完成后应清理恢复 marker");
        check(new RetrievalService(knowledgeRoot).search("恢复旧库专有内容").hasResults(),
                "替换中断恢复后旧库内容仍应可搜索");

        // 构建在写 replace-state 之前崩溃时不会留下提交标记；这类 staged/restore
        // 临时库也必须在下一次启动清理，不能无限积累占用磁盘。
        Path orphanStaged = knowledgeRoot.resolve("knowledge-orphan.db.tmp");
        Path orphanRestore = knowledgeRoot.resolve("knowledge-restore-orphan.db.tmp");
        Files.copy(database, orphanStaged);
        Files.copy(database, orphanRestore);
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        check(!Files.exists(orphanStaged) && !Files.exists(orphanRestore),
                "无 marker 的崩溃临时数据库应在下次启动清理");

        // 模拟 staged 主文件已经移动/丢失、正式库尚未恢复的中断窗口：只剩
        // .previous 时，启动必须从可验证备份生成恢复副本。
        Files.copy(database, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(database);
        Files.writeString(
                database.resolveSibling("knowledge.db.replace-state"),
                "sidecars-cleared\nknowledge-missing-after-move.db.tmp\n",
                StandardCharsets.UTF_8
        );
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        check(KnowledgeDatabase.isUsable(database), "正式库缺失且 staged 不存在时应恢复 .previous");
        check(new RetrievalService(knowledgeRoot).search("恢复旧库专有内容").hasResults(),
                "仅备份恢复后旧库内容仍可搜索");

        // 保留一份确实包含旧内容的备份，模拟新库已安装后 .previous 清理失败；
        // 下一次启动只清理残留备份，不能把正式库回滚成旧版本。
        Path oldDatabase = knowledgeRoot.resolve("knowledge-old-copy.db");
        Files.copy(database, oldDatabase, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        KnowledgeDocument newer = new KnowledgeDocument(
                "recovery:new", "test", "custom_markdown", "新库", "test",
                List.of("新库"), "2", "custom/new.md", "# 新库\n\n新库全新专有内容。"
        );
        KnowledgeDatabase.sync(knowledgeRoot, List.of(new KnowledgeDatabase.DocumentInput(
                "custom:new", "new-fingerprint", "custom/new.md", "neutral", 100, newer
        )), true);
        Files.copy(oldDatabase, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                database.resolveSibling("knowledge.db.replace-state"),
                "validated\nknowledge-validated.db.tmp\n",
                StandardCharsets.UTF_8
        );
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        RetrievalService validated = new RetrievalService(knowledgeRoot);
        check(validated.search("新库全新专有内容").hasResults(),
                "已校验的新库不应因残留 previous 回滚");
        check(!validated.search("恢复旧库专有内容").hasResults(),
                "validated 状态下不应重新暴露旧库内容");
        validated.close();

        // installed 是 validated 之后的清理阶段；即使旧版本备份再次残留，
        // 启动恢复仍必须保留正式新库。
        Files.copy(oldDatabase, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                database.resolveSibling("knowledge.db.replace-state"),
                "installed\nknowledge-installed.db.tmp\n",
                StandardCharsets.UTF_8
        );
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        RetrievalService newest = new RetrievalService(knowledgeRoot);
        check(newest.search("新库全新专有内容").hasResults(), "已安装的新库不应因残留备份被回滚");
        check(!newest.search("恢复旧库专有内容").hasResults(), "新库保留时不应重新暴露旧库内容");

        // 当前默认 SQLite 使用 DELETE journal；恢复逻辑也必须清理残留的
        // knowledge.db.previous-journal，而不是只处理 WAL/SHM。
        Path staleJournal = knowledgeRoot.resolve("knowledge.db.previous-journal");
        Files.writeString(staleJournal, "stale journal");
        KnowledgeDatabase.ensureDatabase(knowledgeRoot);
        check(!Files.exists(staleJournal), "恢复清理必须覆盖 SQLite rollback journal");
        newest.close();
    }

    private static void verifyCompilerReportsDatabaseFailure(Path root) throws Exception {
        Path config = root.resolve("config");
        Path knowledgeRoot = config.resolve("modpedia/knowledge");
        Files.createDirectories(knowledgeRoot.resolve("knowledge.db"));

        KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                config,
                new KnowledgeScanResult(List.of(generatedController()), List.of()),
                true
        );
        check(!result.databaseSynchronized() && !result.successful(),
                "SQLite 同步失败时编译结果不能报告成功");
        check(result.report().warnings().stream()
                        .anyMatch(warning -> warning.contains("SQLite 知识库同步失败")),
                "SQLite 同步失败应写入可诊断警告");
        check(!Files.exists(knowledgeRoot.resolve("manifest.json")),
                "SQLite 未同步成功时不能提交与数据库不一致的 manifest");
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
