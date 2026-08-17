package io.ctyx.modpedia.task;

import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 当前玩家在一个存档作用域内已经完成的任务快照。
 *
 * <p>这是客户端运行时缓存，不是全局知识库数据。存档作用域由
 * {@code TaskRuntimeSnapshot.scopeKey} 标识；切换存档或玩家时必须创建新的快照。
 * 初始内容来自进入世界时的 FTB Quests TeamData，之后只通过任务完成事件增量添加。</p>
 */
public record CompletedQuestSnapshot(
        String sourceKey,
        String scopeKey,
        String version,
        List<String> completedQuestIds,
        List<TaskTimelineEntry> timeline
) {
    private static final int MAX_TIMELINE_ENTRIES = 4_096;
    /** 保留只携带完成 ID 的旧构造方式。 */
    public CompletedQuestSnapshot(
            String sourceKey,
            String scopeKey,
            String version,
            List<String> completedQuestIds
    ) {
        this(sourceKey, scopeKey, version, completedQuestIds, List.of());
    }

    public CompletedQuestSnapshot {
        sourceKey = text(sourceKey, "ftbquests");
        scopeKey = text(scopeKey, "local");
        version = text(version, "unknown");
        completedQuestIds = clean(completedQuestIds);
        List<TaskTimelineEntry> cleanTimeline = timeline == null ? List.of() : timeline.stream()
                .filter(entry -> entry != null
                        && !entry.questId().isBlank()
                        && entry.eventType() == TaskTimelineEventType.COMPLETED)
                .distinct()
                .sorted(Comparator.comparingLong(TaskTimelineEntry::timestampEpochMillis)
                        .thenComparing(TaskTimelineEntry::questId))
                .toList();
        timeline = cleanTimeline.size() <= MAX_TIMELINE_ENTRIES
                ? cleanTimeline
                : List.copyOf(cleanTimeline.subList(
                        cleanTimeline.size() - MAX_TIMELINE_ENTRIES, cleanTimeline.size()));
    }

    public CompletedQuestSnapshot add(String questId) {
        return add(questId, System.currentTimeMillis());
    }

    public CompletedQuestSnapshot add(String questId, long timestampEpochMillis) {
        String normalized = FtbQuestIdCodec.fromRuntimeKey(questId);
        if (normalized.isBlank()) {
            return this;
        }
        Set<String> ids = new LinkedHashSet<>(completedQuestIds);
        ids.add(normalized);
        List<TaskTimelineEntry> updatedTimeline = new java.util.ArrayList<>(timeline);
        TaskTimelineEntry entry = new TaskTimelineEntry(
                normalized,
                TaskTimelineEventType.COMPLETED,
                timestampEpochMillis,
                null,
                1D
        );
        if (!updatedTimeline.contains(entry)) {
            updatedTimeline.add(entry);
        }
        if (ids.equals(new LinkedHashSet<>(completedQuestIds))
                && updatedTimeline.size() == timeline.size()) {
            return this;
        }
        return new CompletedQuestSnapshot(sourceKey, scopeKey, version, List.copyOf(ids), updatedTimeline);
    }

    /** 合并同一存档的初始完成快照和事件增量，不重复制造“现在完成”的时间。 */
    public CompletedQuestSnapshot merge(CompletedQuestSnapshot other) {
        if (other == null || !scopeKey.equals(other.scopeKey())) {
            return this;
        }
        Set<String> ids = new LinkedHashSet<>(completedQuestIds);
        ids.addAll(other.completedQuestIds());
        List<TaskTimelineEntry> mergedTimeline = new java.util.ArrayList<>(timeline);
        other.timeline().forEach(entry -> {
            if (!mergedTimeline.contains(entry)) {
                mergedTimeline.add(entry);
            }
        });
        return new CompletedQuestSnapshot(sourceKey, scopeKey, version,
                List.copyOf(ids), mergedTimeline);
    }

    public List<String> idsFor(TaskQuery query) {
        String requested = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
        if (requested.isBlank()) {
            return completedQuestIds;
        }
        return completedQuestIds.stream().filter(requested::equals).toList();
    }

    public TaskRuntimeSnapshot runtimeSnapshot(TaskQuery query) {
        return new TaskRuntimeSnapshot(
                sourceKey,
                scopeKey,
                version,
                List.of(),
                idsFor(query),
                java.util.Map.of(),
                query == null
                        ? timeline
                        : timeline.stream()
                        .filter(entry -> query.questId() == null || query.questId().isBlank()
                                || entry.questId().equals(FtbQuestIdCodec.fromRuntimeKey(query.questId())))
                        .toList()
        );
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(FtbQuestIdCodec::fromRuntimeKey)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
