package io.ctyx.modpedia.search;

import com.google.gson.JsonParser;
import io.ctyx.modpedia.ai.SearchKnowledgeTool;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 物品目录 Schema、增量同步、语言替换、查询上下文和旧库重建回归。 */
public final class ItemCatalogSelfTest {
    private ItemCatalogSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-item-catalog-test-");
        try {
            testParser();
            testDatabase(root.resolve("database"));
            testBulkSync(root.resolve("bulk"));
            testOldSchemaReset(root.resolve("old-schema"));
            System.out.println("ModPedia item catalog self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void testParser() {
        ItemQueryParser.Parsed parsed = ItemQueryParser.parse(
                "如何使用 [[item:Example:Machine|示例机器]] 和 example:gear？"
        );
        check(parsed.itemIds().equals(List.of("example:machine", "example:gear")),
                "物品令牌和裸 ID 应按出现顺序去重提取");
        check(parsed.searchableText().contains("示例机器"), "物品令牌应转换为可搜索名称");
        check(!parsed.searchableText().contains("[[item:"), "手册查询文本不应带协议括号");
    }

    private static void testDatabase(Path root) throws Exception {
        Files.createDirectories(root);
        KnowledgeDatabase.ensureDatabase(root);
        check(KnowledgeDatabase.isUsable(KnowledgeDatabase.path(root)), "Schema v7 数据库应可用");

        KnowledgeDatabase.ItemCatalogSyncResult first = KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                List.of(
                        entry("example:machine", "zh_cn", "示例机器", "- 需要能源", "example", "machine-zh-v1"),
                        entry("example:gear", "zh_cn", "齿轮", "- 用于传动", "example", "gear-zh-v1")
                )
        );
        check(first.updatedCount() == 2 && first.itemCount() == 2, "首次同步应写入两个物品");
        try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(KnowledgeDatabase.path(root))) {
            check(reader.lookupItems(List.of("example:machine"), SearchLanguage.ZH_CN)
                            .get(0).descriptionMarkdown().contains("需要能源"),
                    "物品目录应保存完整 Tooltip Markdown");
            check(reader.lookupItemsByDisplayName(List.of("示例机器"), SearchLanguage.ZH_CN).size() == 1,
                    "物品目录应支持按当前语言显示名称精确查询");
        }

        KnowledgeDatabase.ItemCatalogSyncResult reused = KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                List.of(
                        entry("example:machine", "zh_cn", "示例机器", "- 需要能源", "example", "machine-zh-v1"),
                        entry("example:gear", "zh_cn", "齿轮", "- 用于传动", "example", "gear-zh-v1")
                )
        );
        check(reused.updatedCount() == 0 && reused.reusedCount() == 2, "相同指纹应复用物品目录");

        KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                List.of(entry("example:machine", "zh_cn", "示例机器", "- 修改后的简介", "example", "machine-zh-v2"))
        );
        try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(KnowledgeDatabase.path(root))) {
            List<ItemCatalogEntry> changed = reader.lookupItems(
                    List.of("example:machine", "example:gear"), SearchLanguage.ZH_CN);
            check(changed.size() == 1, "当前语言同步应清理已经移除的物品");
            check(changed.get(0).descriptionMarkdown().contains("修改后的简介"), "修改指纹后应更新简介");
        }

        KnowledgeDatabase.syncItemCatalog(
                root,
                "en_us",
                List.of(
                        entry("example:machine", "en_us", "Example Machine", "- Requires power", "example", "machine-en-v1"),
                        entry("other:machine", "en_us", "Example Machine", "- Another machine", "other", "machine-en-v1")
                )
        );
        try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(KnowledgeDatabase.path(root))) {
            check(reader.lookupItems(List.of("example:machine"), SearchLanguage.EN_US).get(0)
                            .displayName().equals("Example Machine"),
                    "切换语言后应读取当前语言目录");
            check(reader.lookupItemsByDisplayName(List.of("Example Machine"), SearchLanguage.EN_US).size() == 2,
                    "同名显示名称应返回全部候选物品 ID");
            check(reader.lookupItems(List.of("example:machine"), SearchLanguage.ZH_CN).isEmpty(),
                    "当前语言策略应清理旧语言目录");
        }

        KnowledgeDatabase.DocumentInput document = new KnowledgeDatabase.DocumentInput(
                "manual:machine",
                "manual-v1",
                "generated/machine.md",
                "en_us",
                10,
                new KnowledgeDocument(
                        "manual:machine",
                        "example",
                        "patchouli_json",
                        "Example Machine Manual",
                        "guide",
                        List.of("machine"),
                        "1.0",
                        "assets/example/book/entries/machine.json",
                        "# Example Machine\n\nManual instructions remain searchable."
                )
        );
        KnowledgeDatabase.sync(root, List.of(document), true);
        KnowledgeDatabase.syncItemCatalog(
                root,
                "en_us",
                List.of(
                        entry("example:machine", "en_us", "Example Machine", "- Requires power", "example", "machine-en-v1"),
                        entry("other:machine", "en_us", "Example Machine", "- Another machine", "other", "machine-en-v1")
                )
        );
        RetrievalService retrieval = new RetrievalService(root);
        retrieval.setLanguage(SearchLanguage.EN_US);
        check(retrieval.search("Manual instructions").hasResults(), "物品同步不应污染手册 FTS");
        check(retrieval.lookupItemContext("[[item:example:machine|Example Machine]]", SearchLanguage.EN_US).size() == 1,
                "检索服务应先读取物品上下文");
        SearchKnowledgeTool tool = new SearchKnowledgeTool(
                retrieval,
                SearchLanguage.EN_US,
                8,
                8_000,
                1,
                ignored -> { }
        );
        var toolOutput = JsonParser.parseString(tool.search(
                "[[item:example:machine|Example Machine]] 的手册用法",
                "en_us",
                8,
                "steps",
                List.of()
        )).getAsJsonObject();
        check(toolOutput.get("item_context_count").getAsInt() == 1,
                "AI 搜索工具应返回已确认物品上下文");
        check(toolOutput.get("item_context").getAsJsonArray().get(0).getAsJsonObject()
                        .get("description_markdown").getAsString().contains("Requires power"),
                "AI 工具应返回物品 Tooltip 简介");
        var nameOutput = JsonParser.parseString(tool.search(
                "Example Machine 的手册用法",
                "en_us",
                8,
                "steps",
                List.of()
        )).getAsJsonObject();
        check(nameOutput.get("item_context_count").getAsInt() == 2,
                "AI 搜索工具应支持按显示名称读取同名候选");
        check(nameOutput.get("item_context").getAsJsonArray().get(0).getAsJsonObject()
                        .get("ambiguous").getAsBoolean(),
                "同名物品上下文必须标记为歧义，不能擅自选择一个 ID");
        var repeatedOutput = JsonParser.parseString(tool.search(
                "[[item:example:machine|Example Machine]] 的手册用法",
                "en_us",
                8,
                "steps",
                List.of()
        )).getAsJsonObject();
        check(repeatedOutput.get("item_context_count").getAsInt() == 1,
                "重复查询提示也应保留已确认物品上下文");

        try {
            KnowledgeDatabase.syncItemCatalog(
                    root,
                    "en_us",
                    List.of(new ItemCatalogEntry("example:machine", "en_us", "", "", "example", ""))
            );
            throw new AssertionError("非法物品指纹应触发同步失败");
        } catch (IllegalArgumentException expected) {
            try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(KnowledgeDatabase.path(root))) {
                check(reader.lookupItems(List.of("example:machine"), SearchLanguage.EN_US).get(0)
                                .descriptionMarkdown().contains("Requires power"),
                        "同步输入失败后应保留旧物品目录");
            }
        }
    }

    private static void testOldSchemaReset(Path root) throws Exception {
        Files.createDirectories(root);
        Path database = KnowledgeDatabase.path(root);
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 5");
        }
        KnowledgeDatabase.ensureDatabase(root);
        check(KnowledgeDatabase.isUsable(database), "旧 Schema 应删除并重建为 v6");
        try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(database)) {
            check(reader.lookupItems(List.of("example:missing"), SearchLanguage.EN_US).isEmpty(),
                    "重建后的物品目录应从空表开始");
        }
    }

    private static void testBulkSync(Path root) throws Exception {
        Files.createDirectories(root);
        KnowledgeDatabase.ensureDatabase(root);
        List<ItemCatalogEntry> entries = new ArrayList<>(20_000);
        for (int index = 0; index < 20_000; index++) {
            String id = "fixture:item_" + index;
            entries.add(entry(
                    id,
                    "zh_cn",
                    "夹具物品 " + index,
                    "- 用于批量写入性能测试",
                    "fixture",
                    "fingerprint-" + index
            ));
        }
        long started = System.nanoTime();
        KnowledgeDatabase.ItemCatalogSyncResult first = KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                entries
        );
        double firstMs = (System.nanoTime() - started) / 1_000_000D;
        check(first.itemCount() == entries.size(), "批量同步应保留全部物品");

        Object databaseFileKey = Files.readAttributes(
                KnowledgeDatabase.path(root),
                BasicFileAttributes.class
        ).fileKey();
        started = System.nanoTime();
        KnowledgeDatabase.ItemCatalogSyncResult reused = KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                entries
        );
        double reusedMs = (System.nanoTime() - started) / 1_000_000D;
        check(reused.updatedCount() == 0 && reused.reusedCount() == entries.size(),
                "批量同步的相同指纹应全部复用");
        Object reusedFileKey = Files.readAttributes(
                KnowledgeDatabase.path(root),
                BasicFileAttributes.class
        ).fileKey();
        check(databaseFileKey == null || databaseFileKey.equals(reusedFileKey),
                "相同指纹同步不应复制并替换整个 knowledge.db");

        Object beforeChangedFileKey = Files.readAttributes(
                KnowledgeDatabase.path(root),
                BasicFileAttributes.class
        ).fileKey();
        List<ItemCatalogEntry> changedEntries = new ArrayList<>(entries);
        changedEntries.set(0, entry(
                "fixture:item_0",
                "zh_cn",
                "夹具物品 0（更新）",
                "- 更新后的批量写入性能测试",
                "fixture",
                "fingerprint-0-v2"
        ));
        started = System.nanoTime();
        KnowledgeDatabase.ItemCatalogSyncResult changed = KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                changedEntries
        );
        double changedMs = (System.nanoTime() - started) / 1_000_000D;
        Object afterChangedFileKey = Files.readAttributes(
                KnowledgeDatabase.path(root),
                BasicFileAttributes.class
        ).fileKey();
        check(changed.updatedCount() == 1 && changed.reusedCount() == entries.size() - 1,
                "单条指纹变化应只更新一个物品");
        check(beforeChangedFileKey == null || beforeChangedFileKey.equals(afterChangedFileKey),
                "增量物品同步不应复制并替换整个 knowledge.db");
        System.out.printf(
                "Item catalog bulk sync entries=%d first=%.2f ms reused=%.2f ms changed=%.2f ms%n",
                entries.size(), firstMs, reusedMs, changedMs
        );
    }

    private static ItemCatalogEntry entry(
            String id,
            String language,
            String name,
            String description,
            String sourceMod,
            String fingerprint
    ) {
        return new ItemCatalogEntry(id, language, name, description, sourceMod, fingerprint);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
