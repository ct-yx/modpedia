package io.ctyx.modpedia.task;

/**
 * 一次按问题触发的任务运行时读取结果。
 *
 * <p>该结果携带的 {@link TaskRuntimeSnapshot} 只用于当前查询链路的内存覆盖，
 * 不会写入统一的 knowledge.db；任务正文仍由 {@link TaskKnowledgeStore} 查询。</p>
 */
public record TaskRuntimeReadResult(
        boolean available,
        boolean read,
        int questCount,
        String message,
        TaskRuntimeSnapshot runtimeSnapshot
) {
    public TaskRuntimeReadResult {
        questCount = Math.max(0, questCount);
        message = message == null ? "" : message;
    }

    public static TaskRuntimeReadResult unavailable(String message) {
        return new TaskRuntimeReadResult(false, false, 0, message, null);
    }

    public static TaskRuntimeReadResult read(int questCount) {
        return new TaskRuntimeReadResult(true, true, questCount, "", null);
    }

    public static TaskRuntimeReadResult read(TaskRuntimeSnapshot snapshot) {
        int questCount = snapshot == null
                ? 0
                : snapshot.questStateCount();
        return new TaskRuntimeReadResult(true, true, questCount, "", snapshot);
    }

    public static TaskRuntimeReadResult cached(int questCount) {
        return new TaskRuntimeReadResult(true, false, questCount, "本轮任务问题已经读取过当前进度", null);
    }

    public static TaskRuntimeReadResult cached(TaskRuntimeReadResult previous) {
        if (previous == null) {
            return cached(0);
        }
        return new TaskRuntimeReadResult(
                previous.available(),
                false,
                previous.questCount(),
                "本轮任务问题已经读取过当前进度",
                previous.runtimeSnapshot()
        );
    }
}
