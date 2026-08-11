package io.ctyx.modpedia.task;

import java.util.List;

public record TaskQuery(
        TaskQueryMode mode,
        String text,
        String questId,
        int limit,
        List<String> collectionIds
) {
    public static final int DEFAULT_LIMIT = 8;

    public TaskQuery {
        mode = mode == null ? TaskQueryMode.SEARCH : mode;
        text = text == null ? "" : text.strip();
        questId = questId == null ? "" : questId.strip();
        limit = Math.max(1, Math.min(20, limit));
        collectionIds = collectionIds == null ? List.of() : collectionIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    public static TaskQuery search(String text) {
        return new TaskQuery(TaskQueryMode.SEARCH, text, "", DEFAULT_LIMIT, List.of());
    }
}
