package io.ctyx.modpedia.task;

import io.ctyx.modpedia.protocol.WorkerPayloadCodec;

import java.util.List;

/** 已完成任务快照的存档作用域、去重和增量更新回归测试。 */
public final class CompletedQuestSnapshotSelfTest {
    private CompletedQuestSnapshotSelfTest() {
    }

    public static void main(String[] args) {
        CompletedQuestSnapshot initial = new CompletedQuestSnapshot(
                "ftbquests:local",
                "player:p|world:save-a",
                "2101.1.29",
                List.of("1", "0000000000000002", "1")
        );
        check(initial.completedQuestIds().equals(List.of(
                "0000000000000001",
                "0000000000000002"
        )), "进入世界快照应规范化并去重完成任务 ID");

        CompletedQuestSnapshot updated = initial.add("3", 1_786_436_182_827L)
                .add("0000000000000003", 1_786_436_182_827L);
        check(updated.completedQuestIds().equals(List.of(
                "0000000000000001",
                "0000000000000002",
                "0000000000000003"
        )), "完成事件只应增量加入新的任务 ID");
        check(updated.add("") == updated, "空任务 ID 不应改变快照");
        check(updated.timeline().size() == 1
                        && updated.timeline().getFirst().eventType() == TaskTimelineEventType.COMPLETED
                        && updated.timeline().getFirst().timestampEpochMillis() == 1_786_436_182_827L,
                "完成事件应保留原始完成时间并按相同事件去重");
        check(updated.runtimeSnapshot(TaskQuery.search("")).timelineEntryCount() == 1,
                "运行时快照应携带完成时间线，但仍只存在内存中");
        TaskRuntimeSnapshot decoded = WorkerPayloadCodec.runtimeSnapshot(
                WorkerPayloadCodec.runtimeSnapshot(updated.runtimeSnapshot(TaskQuery.search("")))
        );
        check(decoded.timeline().equals(updated.timeline()),
                "Worker IPC 往返不得丢失任务时间线");

        TaskTimelineTracker tracker = new TaskTimelineTracker();
        check(tracker.detect("player:p|world:save-a", java.util.Map.of("task:stone", 1D)).isEmpty(),
                "首次进度读取只建立基线，不应伪造变化事件");
        List<TaskTimelineEntry> changes = tracker.detect(
                "player:p|world:save-a", java.util.Map.of("task:stone", 3D));
        check(changes.size() == 1
                        && changes.getFirst().eventType() == TaskTimelineEventType.PROGRESS_CHANGED
                        && changes.getFirst().previousProgress() == 1D
                        && changes.getFirst().currentProgress() == 3D
                        && changes.getFirst().hasKnownTimestamp(),
                "两次同一存档进度变化应生成带检测时间的时间线事件");
        check(tracker.detect("player:p|world:save-b", java.util.Map.of("task:stone", 9D)).isEmpty(),
                "切换存档时应重建基线，不能把旧存档进度当成变化");
        check(updated.idsFor(new TaskQuery(
                TaskQueryMode.DETAILS, "", "2", 8, List.of()
        )).equals(List.of("0000000000000002")), "quest_id 查询应只返回目标完成状态");

        CompletedQuestSnapshot otherSave = new CompletedQuestSnapshot(
                initial.sourceKey(),
                "player:p|world:save-b",
                initial.version(),
                List.of("4")
        );
        check(!initial.scopeKey().equals(otherSave.scopeKey()), "不同存档必须使用不同快照作用域");
        check(otherSave.completedQuestIds().equals(List.of("0000000000000004")),
                "新存档不能继承旧存档的完成任务");

        String overworldScope = TaskRuntimeScope.forPlayerAndWorld("p", "save-a");
        String netherScope = TaskRuntimeScope.forPlayerAndWorld("p", "save-a");
        check(overworldScope.equals(netherScope), "同一存档跨维度必须复用任务快照作用域");
        check(!overworldScope.equals(TaskRuntimeScope.forPlayerAndWorld("p", "save-b")),
                "不同存档仍必须使用不同任务快照作用域");

        System.out.println("ModPedia completed quest snapshot self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
