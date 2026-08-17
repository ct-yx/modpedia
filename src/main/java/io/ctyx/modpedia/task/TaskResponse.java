package io.ctyx.modpedia.task;

import java.util.List;

public record TaskResponse(
        TaskStatus status,
        TaskQuery query,
        List<TaskResult> results,
        String error,
        boolean hasMore,
        int taskDefinitionCount
) {
    public TaskResponse(
            TaskStatus status,
            TaskQuery query,
            List<TaskResult> results,
            String error
    ) {
        this(status, query, results, error, false, 0);
    }

    /** 保留现有五参数构造器；定义总数在旧调用方未提供时按 0 处理。 */
    public TaskResponse(
            TaskStatus status,
            TaskQuery query,
            List<TaskResult> results,
            String error,
            boolean hasMore
    ) {
        this(status, query, results, error, hasMore, 0);
    }

    public TaskResponse {
        status = status == null ? TaskStatus.ERROR : status;
        query = query == null ? TaskQuery.search("") : query;
        results = results == null ? List.of() : List.copyOf(results);
        error = error == null ? "" : error;
        taskDefinitionCount = Math.max(0, taskDefinitionCount);
    }

    public boolean hasResults() {
        return !results.isEmpty();
    }
}
