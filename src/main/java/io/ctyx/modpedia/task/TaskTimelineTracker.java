package io.ctyx.modpedia.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 比较同一存档作用域的两次 task_progress 快照。
 *
 * <p>FTBQ 不为 task_progress 保存变化历史，因此这里最多保留一个当前作用域的
 * 小型 Map；切换存档时直接替换，不会随着世界数量持续增长。</p>
 */
public final class TaskTimelineTracker {
    private String scopeKey = "";
    private Map<String, Double> previous = Map.of();

    public synchronized List<TaskTimelineEntry> detect(
            String currentScopeKey,
            Map<String, Double> currentProgress
    ) {
        String scope = currentScopeKey == null || currentScopeKey.isBlank()
                ? "local"
                : currentScopeKey;
        Map<String, Double> current = clean(currentProgress);
        if (!scope.equals(scopeKey)) {
            scopeKey = scope;
            previous = current;
            return List.of();
        }
        Map<String, Double> old = previous;
        previous = current;
        if (old.equals(current)) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>(old.keySet());
        ids.addAll(current.keySet());
        List<TaskTimelineEntry> result = new ArrayList<>();
        for (String id : ids) {
            Double before = old.get(id);
            Double after = current.get(id);
            if (!Objects.equals(before, after)) {
                result.add(new TaskTimelineEntry(
                        id,
                        TaskTimelineEventType.PROGRESS_CHANGED,
                        System.currentTimeMillis(),
                        before,
                        after
                ));
            }
        }
        return List.copyOf(result);
    }

    public synchronized void clear() {
        scopeKey = "";
        previous = Map.of();
    }

    private static Map<String, Double> clean(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && Double.isFinite(value)) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }
}
