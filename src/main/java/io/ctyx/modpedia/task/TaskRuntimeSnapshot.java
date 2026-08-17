package io.ctyx.modpedia.task;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 当前玩家的临时任务状态。
 *
 * <p>该类型只存在于一次客户端查询链路中，不对应任何 SQLite 表。任务标题、
 * 要求、依赖和奖励仍从静态任务定义表读取，当前玩家的开始/完成状态和任务进度
 * 由这里的值覆盖返回结果。</p>
 */
public record TaskRuntimeSnapshot(
        String sourceKey,
        String scopeKey,
        String version,
        List<String> startedQuestIds,
        List<String> completedQuestIds,
        Map<String, Double> taskProgress,
        List<TaskTimelineEntry> timeline
) {
    private static final int MAX_TIMELINE_ENTRIES = 4_096;
    /** 保留没有时间线字段的旧调用方式。 */
    public TaskRuntimeSnapshot(
            String sourceKey,
            String scopeKey,
            String version,
            List<String> startedQuestIds,
            List<String> completedQuestIds,
            Map<String, Double> taskProgress
    ) {
        this(sourceKey, scopeKey, version, startedQuestIds, completedQuestIds, taskProgress, List.of());
    }

    public TaskRuntimeSnapshot {
        sourceKey = text(sourceKey, "ftbquests");
        scopeKey = text(scopeKey, "local");
        version = text(version, "unknown");
        startedQuestIds = cleanIds(startedQuestIds);
        completedQuestIds = cleanIds(completedQuestIds);
        taskProgress = cleanProgress(taskProgress);
        timeline = cleanTimeline(timeline);
    }

    public boolean hasQuestState(String questId) {
        String normalized = FtbQuestIdCodec.fromRuntimeKey(questId);
        return startedQuestIds.contains(normalized) || completedQuestIds.contains(normalized);
    }

    /** 返回当前运行时快照中唯一的任务状态数量。一个任务同时 started/completed 时只计一次。 */
    public int questStateCount() {
        Set<String> ids = new LinkedHashSet<>(startedQuestIds);
        ids.addAll(completedQuestIds);
        return ids.size();
    }

    /** 返回本次快照中实际携带进度数值的任务项数量。 */
    public int progressItemCount() {
        return taskProgress.size();
    }

    /** 返回按时间从新到旧排列的最近运行时事件。 */
    public List<TaskTimelineEntry> recentTimeline(int limit) {
        int actualLimit = Math.max(0, limit);
        return timeline.stream()
                .sorted(Comparator
                        .comparingLong(TaskTimelineEntry::timestampEpochMillis)
                        .reversed()
                        .thenComparing(entry -> entry.eventType().name())
                        .thenComparing(TaskTimelineEntry::questId))
                .limit(actualLimit)
                .toList();
    }

    /** 按精确 quest_id 过滤时间线；空 quest_id 返回完整时间线。 */
    public List<TaskTimelineEntry> timelineFor(TaskQuery query) {
        String requested = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
        if (requested.isBlank()) {
            return timeline;
        }
        return timeline.stream().filter(entry -> requested.equals(entry.questId())).toList();
    }

    public int timelineEntryCount() {
        return timeline.size();
    }

    private static List<String> cleanIds(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(FtbQuestIdCodec::fromRuntimeKey)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static Map<String, Double> cleanProgress(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value)) {
                return;
            }
            String normalized = FtbQuestIdCodec.fromRuntimeKey(key);
            if (!normalized.isBlank()) {
                result.put(normalized, value);
            }
        });
        return Map.copyOf(result);
    }

    private static List<TaskTimelineEntry> cleanTimeline(List<TaskTimelineEntry> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<TaskTimelineEntry> sorted = values.stream()
                .filter(entry -> entry != null && !entry.questId().isBlank())
                .distinct()
                .sorted(Comparator
                        .comparingLong(TaskTimelineEntry::timestampEpochMillis)
                        .thenComparing(entry -> entry.eventType().name())
                        .thenComparing(TaskTimelineEntry::questId))
                .toList();
        if (sorted.size() <= MAX_TIMELINE_ENTRIES) {
            return sorted;
        }
        return List.copyOf(sorted.subList(sorted.size() - MAX_TIMELINE_ENTRIES, sorted.size()));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
