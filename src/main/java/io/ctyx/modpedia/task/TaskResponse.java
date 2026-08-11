package io.ctyx.modpedia.task;

import java.util.List;

public record TaskResponse(
        TaskStatus status,
        TaskQuery query,
        List<TaskResult> results,
        String error
) {
    public TaskResponse {
        status = status == null ? TaskStatus.ERROR : status;
        query = query == null ? TaskQuery.search("") : query;
        results = results == null ? List.of() : List.copyOf(results);
        error = error == null ? "" : error;
    }

    public boolean hasResults() {
        return !results.isEmpty();
    }
}
