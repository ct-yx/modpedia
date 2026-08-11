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
import java.util.Comparator;
import java.util.List;

/** 任务快照、实时进度、随机奖励和统一 knowledge.db 的纯 Java 回归。 */
public final class TaskKnowledgeStoreSelfTest {
    private TaskKnowledgeStoreSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-task-store-");
        try {
            TaskKnowledgeStore store = new TaskKnowledgeStore(root);
            TaskSnapshot first = snapshot("snapshot-one", "task:pack-a", "pack-a", "quest:start");
            store.syncSnapshot(first);

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

            store.updateProgress(
                    "snapshot-one", "pack-a", "quest:start", "task:stone",
                    "completed", 3, 3, true
            );
            TaskResponse progressed = store.query(new TaskQuery(
                    TaskQueryMode.DETAILS, "", "quest:start", 8, List.of("pack-a")));
            TaskResult progressedResult = progressed.results().getFirst();
            check(progressedResult.progressAvailable(), "实时进度存在时应标记 progress_available");
            check(progressedResult.requirements().getFirst().completed(),
                    "实时进度应覆盖静态任务要求");

            TaskResponse blocked = store.query(new TaskQuery(
                    TaskQueryMode.BLOCKED, "", "", 8, List.of("pack-a")));
            check(blocked.results().stream().noneMatch(result -> "quest:start".equals(result.questId())),
                    "完成实时要求后，任务不应继续被标记为要求阻塞");

            // 同一 task/quest ID 在不同来源中可以共存，不能污染彼此的数据。
            store.syncSnapshot(snapshot("snapshot-two", "task:pack-b", "pack-b", "quest:start"));
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
            JsonObject reward = taskJson.getAsJsonArray("results")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("rewards").get(0).getAsJsonObject();
            check(reward.get("is_random").getAsBoolean()
                            && !reward.get("guaranteed").getAsBoolean()
                            && reward.getAsJsonArray("candidates").size() == 2,
                    "search_tasks 不得把随机奖励候选伪装成确定奖励");

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
                        "task:stone", "item", "收集石头", "minecraft:stone", 0, 3, false, "{}"
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
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
