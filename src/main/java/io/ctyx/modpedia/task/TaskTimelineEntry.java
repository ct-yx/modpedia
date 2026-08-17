package io.ctyx.modpedia.task;

/**
 * 一条只存在于运行时查询链路的任务时间线记录。
 *
 * <p>started/completed 的时间来自 FTBQ TeamData；进度变化没有历史日志时，
 * timestamp 表示 ModPedia 检测到变化的时间。该类型不会写入 knowledge.db。</p>
 */
public record TaskTimelineEntry(
        String questId,
        TaskTimelineEventType eventType,
        long timestampEpochMillis,
        Double previousProgress,
        Double currentProgress
) {
    public TaskTimelineEntry {
        questId = questId == null ? "" : FtbQuestIdCodec.fromRuntimeKey(questId);
        eventType = eventType == null ? TaskTimelineEventType.DETECTED : eventType;
        timestampEpochMillis = Math.max(0L, timestampEpochMillis);
        previousProgress = finite(previousProgress);
        currentProgress = finite(currentProgress);
    }

    public boolean hasKnownTimestamp() {
        return timestampEpochMillis > 0L;
    }

    private static Double finite(Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }
}
