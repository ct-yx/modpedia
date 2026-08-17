package io.ctyx.modpedia.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.ai.SearchKnowledgeTool;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 任务快照、实时进度、随机奖励和统一 knowledge.db 的纯 Java 回归。 */
public final class TaskKnowledgeStoreSelfTest {
    private TaskKnowledgeStoreSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-task-store-");
        try {
            TaskKnowledgeStore store = new TaskKnowledgeStore(root);
            TaskSnapshot first = snapshot("snapshot-one", "ftbquests:static:pack-a", "pack-a", "quest:start");
            store.syncSnapshot(first);

            // 升级回归：旧版 ftbquests:<world> 运行时快照不能参与静态查询，
            // 同步任意静态来源时也必须清理对应的知识来源元数据。
            try (var connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath());
                 var statement = connection.prepareStatement("""
                         INSERT INTO task_snapshots(
                             snapshot_id, source_key, fingerprint, scope_key, version, updated_at, raw_json
                         ) VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, "legacy-snapshot");
                statement.setString(2, "ftbquests:overworld");
                statement.setString(3, "legacy");
                statement.setString(4, "overworld");
                statement.setString(5, "old");
                statement.setLong(6, System.currentTimeMillis());
                statement.setString(7, "{}");
                statement.executeUpdate();
            }
            insertLegacySource(root, "task:ftbquests:overworld");
            store.cleanupLegacyRuntimeSnapshots();
            check(countWhere(root, "task_snapshots", "source_key = 'ftbquests:overworld'") == 0,
                    "旧版运行时快照不应继续留在任务查询表中");
            check(countWhere(root, "knowledge_sources", "source_id = 'task:ftbquests:overworld'") == 0,
                    "旧版 FTBQ 快照清理时不应留下孤立来源");

            // 即使本轮没有静态任务目录，Worker 重建也会显式执行遗留清理。
            try (var connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath());
                 var statement = connection.prepareStatement("""
                         INSERT INTO task_snapshots(
                             snapshot_id, source_key, fingerprint, scope_key, version, updated_at, raw_json
                         ) VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, "legacy-again");
                statement.setString(2, "ftbquests:another-world");
                statement.setString(3, "legacy");
                statement.setString(4, "another-world");
                statement.setString(5, "old");
                statement.setLong(6, System.currentTimeMillis());
                statement.setString(7, "{}");
                statement.executeUpdate();
            }
            insertLegacySource(root, "task:task:legacy-pack");
            store.cleanupLegacyRuntimeSnapshots();
            check(countWhere(root, "task_snapshots", "source_key = 'ftbquests:another-world'") == 0,
                    "显式 Worker 重建清理应删除旧版运行时快照");
            check(countWhere(root, "knowledge_sources", "source_id = 'task:task:legacy-pack'") == 0,
                    "旧版 task 快照清理时不应留下孤立来源");

            List<String> candidateIds = store.candidateQuestIds(
                    new TaskQuery(TaskQueryMode.DETAILS, "开始", "", 8, List.of("pack-a")),
                    3
            );
            check(candidateIds.contains("quest:start"), "任务候选 ID 应优先从数据库抽取");

            check(!hasTable(root, "task_progress"), "Schema v7 不应创建实时进度持久化表");

            store.syncSnapshot(largeSnapshot("ftbquests:static:pack-large", "pack-large", 40));
            TaskResponse paged = store.query(new TaskQuery(
                    TaskQueryMode.SEARCH, "批量任务", "", 3, List.of("pack-large")));
            check(paged.results().size() == 3 && paged.hasMore(),
                    "SQL 分页应只返回 limit 条并准确报告后续结果");
            TaskResponse exact = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:pack-large:0", 1, List.of("pack-large")));
            check(exact.results().size() == 1 && !exact.hasMore(),
                    "恰好命中 limit 时 hasMore 应为 false");

            store.syncSnapshot(mixedSnapshot("ftbquests:static:pack-filtered", "pack-filtered", 20, 5));
            long staticQuestCount = count(root, "task_quests");
            long staticTaskCount = count(root, "task_tasks");
            TaskResponse filteredPage = store.query(new TaskQuery(
                    TaskQueryMode.BLOCKED, "", "", 2, List.of("pack-filtered")));
            check(filteredPage.results().size() == 2 && filteredPage.hasMore(),
                    "运行时模式过滤后应继续读取后续 SQL 页，而不是漏掉阻塞任务");

            TaskResponse next = store.query(new TaskQuery(TaskQueryMode.NEXT, "", "", 8, List.of("pack-a")));
            check(next.status() == TaskStatus.READY && next.results().size() == 1,
                    "未同步完成任务不能作为下一步候选");
            check("quest:start".equals(next.results().getFirst().questId()),
                    "NEXT 应先返回没有未完成依赖的任务");
            check("blocked_requirement".equals(next.results().getFirst().status()),
                    "静态任务要求未完成时应明确标记阻塞原因");

            TaskResponse details = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "开始", "", 8, List.of("pack-a")));
            check(details.hasResults() && details.results().getFirst().requirements().size() == 1,
                    "DETAILS 应返回完整任务要求");
            check(details.results().getFirst().rewards().getFirst().candidates().size() == 2,
                    "随机奖励必须保留候选列表");
            check(!details.results().getFirst().progressAvailable()
                            && !details.results().getFirst().requirements().getFirst().completed(),
                    "没有运行时快照时只能返回静态默认进度");

            TaskRuntimeSnapshot runtime = new TaskRuntimeSnapshot(
                    "ftbquests:overworld",
                    "player:test|world:overworld",
                    "1.21.1",
                    List.of("quest:start"),
                    List.of(),
                    Map.of("task:stone", 3D)
            );
            TaskRuntimeReadResult overlappingRuntime = TaskRuntimeReadResult.read(
                    new TaskRuntimeSnapshot(
                            "ftbquests:overworld",
                            "player:test|world:overworld",
                            "1.21.1",
                            List.of("quest:start", "quest:started-only"),
                            List.of("quest:start", "quest:completed-only"),
                            Map.of("task:stone", 3D, "task:iron", 1D)
                    )
            );
            check(overlappingRuntime.questCount() == 3,
                    "started 与 completed 重叠时运行时状态数必须按并集统计");
            check(overlappingRuntime.runtimeSnapshot().progressItemCount() == 2,
                    "实时进度项数必须只统计实际携带的 progress 条目");
            TaskResponse progressed = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of("pack-a")), runtime);
            TaskResult progressedResult = progressed.results().getFirst();
            check(progressedResult.progressAvailable(), "实时进度存在时应标记 progress_available");
            check(progressedResult.requirements().getFirst().completed(),
                    "实时进度应覆盖静态任务要求");
            check(progressedResult.requirements().getFirst().current() == 3D,
                    "实时 current 值应覆盖静态默认值");
            check(count(root, "task_quests") == staticQuestCount
                            && count(root, "task_tasks") == staticTaskCount,
                    "读取实时进度不能修改静态任务定义");

            TaskResponse blocked = store.query(new TaskQuery(
                    TaskQueryMode.BLOCKED, "", "", 8, List.of("pack-a")), runtime);
            check(blocked.results().stream().noneMatch(result -> "quest:start".equals(result.questId())),
                    "完成实时要求后，任务不应继续被标记为要求阻塞");

            // 同一 task/quest ID 在不同来源中可以共存，不能污染彼此的数据。
            store.syncSnapshot(snapshot("snapshot-two", "ftbquests:static:pack-b", "pack-b", "quest:start"));
            TaskResponse secondCollection = store.query(new TaskQuery(
                    TaskQueryMode.SEARCH, "第二来源", "", 8, List.of("pack-b")));
            check(secondCollection.hasResults()
                            && "pack-b".equals(secondCollection.results().getFirst().scopeKey()),
                    "多个任务来源应按 scope_key 隔离且允许相同任务 ID");

            // 手册/Wiki 同步不能删除任务运行表。
            KnowledgeDocument manual = new KnowledgeDocument(
                    "manual:one", "test", "custom_markdown", "手册", "guide",
                    List.of("手册"), "test", "custom/manual.md", "# 手册\n\n正文。"
            );
            KnowledgeDatabase.sync(root, List.of(new KnowledgeDatabase.DocumentInput(
                    "custom:manual", "fingerprint", "custom/manual.md", "neutral", 100, manual
            )), true);
            TaskResponse afterManualSync = store.query(new TaskQuery(
                    TaskQueryMode.SEARCH, "第二来源", "", 8, List.of("pack-b")));
            check(afterManualSync.hasResults(), "手册重建不应删除任务快照");

            RetrievalService retrieval = new RetrievalService(root);
            SearchKnowledgeTool tool = new SearchKnowledgeTool(
                    retrieval, SearchLanguage.ZH_CN, 8, 8_000, 1, 3, ignored -> { }
            );
            JsonObject taskJson = JsonParser.parseString(tool.searchTasks(
                    "details", "第二来源", "", 8, List.of("pack-b")
            )).getAsJsonObject();
            check(taskJson.get("status").getAsString().equals("READY"),
                    "search_tasks 应返回结构化 READY 状态");
            check(taskJson.get("task_definition_count").getAsInt() == 2,
                    "search_tasks 应单独返回当前来源的静态任务定义数");
            JsonObject reward = taskJson.getAsJsonArray("results")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("rewards").get(0).getAsJsonObject();
            check(reward.get("is_random").getAsBoolean()
                            && !reward.get("guaranteed").getAsBoolean()
                            && reward.getAsJsonArray("candidates").size() == 2,
                    "search_tasks 不得把随机奖励候选伪装成确定奖励");

            JsonObject pagedTool = JsonParser.parseString(gatedSearchTool(store, retrieval)).getAsJsonObject();
            check(pagedTool.get("returned_count").getAsInt() == 3
                            && pagedTool.get("has_more").getAsBoolean(),
                    "search_tasks 应直接使用任务存储提供的 hasMore");

            AtomicInteger runtimeReads = new AtomicInteger();
            List<String> taskTraceTools = new java.util.ArrayList<>();
            SearchKnowledgeTool gatedTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    8,
                    8_000,
                    1,
                    3,
                    store,
                    (query, requestKey) -> {
                        runtimeReads.incrementAndGet();
                        return TaskRuntimeReadResult.read(new TaskRuntimeSnapshot(
                                "ftbquests:overworld",
                                "player:test|world:overworld",
                                "1.21.1",
                                List.of("quest:start"),
                                List.of(),
                                Map.of()
                        ));
                    },
                    "self-test-request",
                    trace -> taskTraceTools.add(trace.tool())
            );
            gatedTool.searchTasks("details", "第二来源", "", 8, List.of("pack-b"));
            gatedTool.searchTasks("details", "开始", "", 8, List.of("pack-a"));
            check(runtimeReads.get() == 1, "同一 AI 问题的多轮任务工具调用只能读取一次运行时进度");
            check(taskTraceTools.size() == 2
                            && taskTraceTools.stream().allMatch("search_tasks"::equals),
                    "任务查询轨迹必须记录为 search_tasks，不能误记为模组手册搜索");

            // 顺序回归：运行时读取器先返回当前玩家进度，随后静态任务库才允许
            // 用这份快照覆盖结果。若顺序反过来，下面的 current/completed 会仍是
            // 数据库中的静态默认值 0/false。
            List<String> queryOrder = new java.util.ArrayList<>();
            TaskKnowledgeStore observedStore = new TaskKnowledgeStore(
                    root,
                    ignored -> queryOrder.add("database")
            );
            SearchKnowledgeTool orderedTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    8,
                    8_000,
                    1,
                    3,
                    observedStore,
                    (query, requestKey) -> {
                        // 这个回调代表游戏 JVM 的 TeamData 读取；静态数据库
                        // 查询必须在它返回之后才发生。
                        queryOrder.add("runtime");
                        return TaskRuntimeReadResult.read(new TaskRuntimeSnapshot(
                                "ftbquests:overworld",
                                "player:test|world:overworld",
                                "1.21.1",
                                List.of("quest:start"),
                                List.of(),
                                Map.of("task:stone", 3D),
                                List.of(new TaskTimelineEntry(
                                        "quest:start",
                                        TaskTimelineEventType.COMPLETED,
                                        1_786_436_182_827L,
                                        null,
                                        1D
                                ))
                        ));
                    },
                    "ordered-task-request",
                    ignored -> { }
            );
            JsonObject ordered = JsonParser.parseString(orderedTool.searchTasks(
                    "details", "开始", "quest:start", 8, List.of("pack-a")
            )).getAsJsonObject();
            JsonObject orderedRequirement = ordered.getAsJsonArray("results")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("requirements").get(0).getAsJsonObject();
            check(ordered.get("runtime_progress_read").getAsBoolean(),
                    "查询静态任务定义前必须先读取玩家当前进度");
            check(orderedRequirement.get("current").getAsDouble() == 3D
                            && orderedRequirement.get("completed").getAsBoolean(),
                    "静态任务结果必须使用先读取到的运行时进度覆盖");
            check(queryOrder.equals(List.of("runtime", "database")),
                    "任务链必须先读取玩家当前进度，再查询静态任务数据库");
            check(ordered.get("runtime_state_count").getAsInt() == 1
                            && ordered.get("progress_item_count").getAsInt() == 1
                            && ordered.get("timeline_entry_count").getAsInt() == 1
                            && "开始任务".equals(ordered.getAsJsonArray("timeline").get(0)
                            .getAsJsonObject().get("title").getAsString())
                            && ordered.getAsJsonArray("timeline").get(0).getAsJsonObject()
                            .get("timestamp_epoch_ms").getAsLong() == 1_786_436_182_827L
                            && ordered.get("task_summary").getAsJsonObject()
                            .get("runtime_progress_available").getAsBoolean(),
                    "search_tasks 应返回运行时状态数、实时进度项数、具体时间线和可用状态");

            // 来源隔离：未能与静态 source/scope 精确绑定时，相同 quest/task ID
            // 在多个来源中出现，不得把运行时状态传播到任一来源。
            TaskRuntimeSnapshot ambiguousRuntime = new TaskRuntimeSnapshot(
                    "ftbquests:unknown-world",
                    "player:unknown|world:unknown",
                    "1.21.1",
                    List.of("quest:start"),
                    List.of(),
                    Map.of("task:stone", 3D)
            );
            TaskResponse ambiguous = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of()), ambiguousRuntime);
            check(ambiguous.results().stream().allMatch(result -> result.requirements().stream()
                            .allMatch(requirement -> requirement.current() == 0D)),
                    "跨多个任务来源的未绑定运行时状态不得污染同 ID 任务");

            // source_key 与 scope_key 分别命中不同静态来源时，不能把两条条件
            // 用 OR 拼接后错误绑定到任意一份任务书。
            TaskRuntimeSnapshot splitIdentityRuntime = new TaskRuntimeSnapshot(
                    "ftbquests:static:chapter-a.snbt",
                    "pack-b",
                    "1.21.1",
                    List.of("quest:start"),
                    List.of(),
                    Map.of("task:stone", 3D)
            );
            TaskResponse splitIdentity = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of()), splitIdentityRuntime);
            check(splitIdentity.results().stream().allMatch(result -> result.requirements().stream()
                            .allMatch(requirement -> requirement.current() == 0D)),
                    "source 和 scope 分别命中不同来源时不得绑定运行时状态");

            // quest ID 在两个来源中重复、但 task ID 恰好只在其中一个来源出现时，
            // 也不能仅凭 task ID 绑定进度；task ID 只有和已确认的父 quest 一起才
            // 构成可用身份。
            store.syncSnapshot(sharedQuestSnapshot(
                    "snapshot-shared-a", "ftbquests:static:pack-c", "pack-c", "task:unique-a"
            ));
            store.syncSnapshot(sharedQuestSnapshot(
                    "snapshot-shared-b", "ftbquests:static:pack-d", "pack-d", "task:unique-b"
            ));
            TaskRuntimeSnapshot ambiguousTaskRuntime = new TaskRuntimeSnapshot(
                    "ftbquests:unknown-world",
                    "player:unknown|world:unknown",
                    "1.21.1",
                    List.of("quest:shared"),
                    List.of(),
                    Map.of("task:unique-a", 7D)
            );
            TaskResponse ambiguousTask = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:shared", 8, List.of()), ambiguousTaskRuntime);
            check(ambiguousTask.results().stream()
                            .flatMap(result -> result.requirements().stream())
                            .allMatch(requirement -> requirement.current() == 0D),
                    "父 quest 来源不唯一时不得仅凭唯一 task ID 套用运行时进度");

            // Worker 只能拿到玩家/世界作用域时，如果这个 scope 恰好也是静态
            // 章节 scope，仍必须把状态限制在该章节；不能因为 quest/task ID 相同
            // 而把 pack-b 的进度套到 pack-a。
            TaskRuntimeSnapshot scopedRuntime = new TaskRuntimeSnapshot(
                    "ftbquests:unknown-world",
                    "pack-b",
                    "1.21.1",
                    List.of("quest:start"),
                    List.of(),
                    Map.of("task:stone", 3D)
            );
            TaskResponse scoped = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of()), scopedRuntime);
            check(scoped.results().stream()
                            .filter(result -> "pack-b".equals(result.scopeKey()))
                            .allMatch(result -> result.requirements().getFirst().current() == 3D),
                    "运行时 scope 命中静态章节时应只覆盖该章节");
            check(scoped.results().stream()
                            .filter(result -> "pack-a".equals(result.scopeKey()))
                            .allMatch(result -> result.requirements().getFirst().current() == 0D),
                    "运行时 scope 不得覆盖其它同 ID 任务来源");

            // 即使运行时 source_key 精确命中 pack-a，查询范围也必须优先约束
            // 到 pack-b；不能因为 source_key 更具体就绕过调用方的 collection 过滤。
            TaskRuntimeSnapshot exactSourceWrongCollection = new TaskRuntimeSnapshot(
                    "ftbquests:static:pack-a", "local", "1.21.1",
                    List.of("quest:start"), List.of(), Map.of("task:stone", 3D)
            );
            TaskResponse wrongCollection = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of("pack-b")),
                    exactSourceWrongCollection);
            check(wrongCollection.results().stream()
                            .filter(result -> "pack-b".equals(result.scopeKey()))
                            .allMatch(result -> result.requirements().getFirst().current() == 0D),
                    "运行时 source_key 命中其它 collection 时不得覆盖当前查询来源");

            // BLOCKED 允许模型只给出模式而不传 query/quest_id；这个分支也不能
            // 绕过运行时读取。否则“为什么卡住”会先走静态库，拿不到当前进度。
            AtomicInteger emptyBlockedRuntimeReads = new AtomicInteger();
            SearchKnowledgeTool emptyBlockedTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    8,
                    8_000,
                    1,
                    3,
                    observedStore,
                    (query, requestKey) -> {
                        emptyBlockedRuntimeReads.incrementAndGet();
                        return TaskRuntimeReadResult.read(new TaskRuntimeSnapshot(
                                "ftbquests:overworld",
                                "player:test|world:overworld",
                                "1.21.1",
                                List.of("quest:start"),
                                List.of(),
                                Map.of()
                        ));
                    },
                    "blocked-without-filter",
                    ignored -> { }
            );
            JsonObject emptyBlocked = JsonParser.parseString(emptyBlockedTool.searchTasks(
                    "blocked", "", "", 8, List.of()
            )).getAsJsonObject();
            check(emptyBlockedRuntimeReads.get() == 1
                            && emptyBlocked.get("runtime_progress_read").getAsBoolean(),
                    "没有 query/quest_id 的阻塞查询也必须先读取当前玩家进度");

            System.out.println("ModPedia task knowledge self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static TaskSnapshot snapshot(
            String snapshotId,
            String sourceKey,
            String scopeKey,
            String questId
    ) {
        return snapshotWithTask(snapshotId, sourceKey, scopeKey, questId, "task:stone", "minecraft:stone");
    }

    private static TaskSnapshot sharedQuestSnapshot(
            String snapshotId,
            String sourceKey,
            String scopeKey,
            String taskId
    ) {
        return snapshotWithTask(snapshotId, sourceKey, scopeKey, "quest:shared", taskId, "minecraft:stone");
    }

    private static TaskSnapshot snapshotWithTask(
            String snapshotId,
            String sourceKey,
            String scopeKey,
            String questId,
            String taskId,
            String targetId
    ) {
        TaskSnapshot.TaskQuest start = new TaskSnapshot.TaskQuest(
                questId,
                "",
                scopeKey.equals("pack-b") ? "第二来源" : "开始任务",
                "",
                scopeKey.equals("pack-b") ? "第二来源任务说明" : "收集石头后继续。",
                false,
                true,
                true,
                false,
                0,
                List.of(),
                List.of(new TaskSnapshot.TaskRequirement(
                        taskId, "item", "收集石头", targetId, 0, 3, false, "{}"
                )),
                List.of(new TaskSnapshot.TaskReward(
                        "reward:box", "loot_box", "随机奖励箱", false,
                        List.of("minecraft:iron_ingot", "minecraft:gold_ingot"), "{}"
                )),
                "{}"
        );
        TaskSnapshot.TaskQuest next = new TaskSnapshot.TaskQuest(
                "quest:next", "", "下一步", "", "完成前置后开放。", false,
                true, false, false, 1, List.of(questId), List.of(), List.of(), "{}"
        );
        return new TaskSnapshot(
                snapshotId, sourceKey, "fingerprint-" + snapshotId, scopeKey, "1.0", "{}",
                List.of(start, next)
        );
    }

    private static TaskSnapshot largeSnapshot(String sourceKey, String scopeKey, int count) {
        List<TaskSnapshot.TaskQuest> quests = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            quests.add(new TaskSnapshot.TaskQuest(
                    "quest:" + scopeKey + ":" + index,
                    "",
                    "批量任务 " + index,
                    "",
                    "批量任务说明",
                    false,
                    true,
                    false,
                    false,
                    index,
                    List.of(),
                    List.of(),
                    List.of(),
                    "{}"
            ));
        }
        return new TaskSnapshot(
                "snapshot-" + scopeKey,
                sourceKey,
                "fingerprint-" + scopeKey,
                scopeKey,
                "1.0",
                "{}",
                quests
        );
    }

    private static TaskSnapshot mixedSnapshot(
            String sourceKey,
            String scopeKey,
            int readyCount,
            int blockedCount
    ) {
        List<TaskSnapshot.TaskQuest> quests = new java.util.ArrayList<>();
        for (int index = 0; index < readyCount + blockedCount; index++) {
            boolean blocked = index >= readyCount;
            List<TaskSnapshot.TaskRequirement> requirements = blocked
                    ? List.of(new TaskSnapshot.TaskRequirement(
                    "task:blocked:" + index,
                    "item",
                    "阻塞条件",
                    "minecraft:stone",
                    0,
                    1,
                    false,
                    "{}"
            ))
                    : List.of();
            quests.add(new TaskSnapshot.TaskQuest(
                    "quest:" + scopeKey + ":" + index,
                    "",
                    (blocked ? "阻塞" : "可用") + "任务 " + index,
                    "",
                    "任务说明",
                    false,
                    true,
                    false,
                    false,
                    index,
                    List.of(),
                    requirements,
                    List.of(),
                    "{}"
            ));
        }
        return new TaskSnapshot(
                "snapshot-" + scopeKey,
                sourceKey,
                "fingerprint-" + scopeKey,
                scopeKey,
                "1.0",
                "{}",
                quests
        );
    }

    private static String gatedSearchTool(TaskKnowledgeStore store, RetrievalService retrieval)
            throws Exception {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(
                retrieval,
                SearchLanguage.ZH_CN,
                8,
                8_000,
                1,
                3,
                store,
                (query, requestKey) -> TaskRuntimeReadResult.unavailable("测试未读取实时进度"),
                "paged-task-request",
                ignored -> { }
        );
        return tool.searchTasks("search", "批量任务", "", 3, List.of("pack-large"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long count(Path root, String table) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath()
        );
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static long countWhere(Path root, String table, String predicate) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static void insertLegacySource(Path root, String sourceId) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath());
             var statement = connection.prepareStatement("""
                     INSERT OR REPLACE INTO knowledge_sources(
                         source_id, collection_id, content_kind, source_type, origin_type,
                         title, language, version, origin_uri, local_root, fingerprint,
                         priority, metadata_json, updated_at
                     ) VALUES (?, 'task', 'task_runtime', 'task_snapshot', 'runtime',
                               'legacy', 'neutral', 'old', '', '', 'legacy', 0, '{}', ?)
                     """)) {
            statement.setString(1, sourceId);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static boolean hasTable(Path root, String table) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + KnowledgeDatabase.path(root).toAbsolutePath()
        );
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
             )) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
