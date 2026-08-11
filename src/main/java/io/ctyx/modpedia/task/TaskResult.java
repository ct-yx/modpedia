package io.ctyx.modpedia.task;

import java.util.List;

public record TaskResult(
        String questId,
        String title,
        String descriptionMarkdown,
        String status,
        boolean visible,
        boolean optional,
        List<String> unmetDependencies,
        List<TaskRequirementResult> requirements,
        List<TaskRewardResult> rewards,
        String sourcePath,
        String snapshotId,
        String scopeKey,
        boolean progressAvailable
) {
    public TaskResult {
        questId = questId == null ? "" : questId;
        title = title == null ? "" : title;
        descriptionMarkdown = descriptionMarkdown == null ? "" : descriptionMarkdown;
        status = status == null ? "unknown" : status;
        unmetDependencies = unmetDependencies == null ? List.of() : List.copyOf(unmetDependencies);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        sourcePath = sourcePath == null ? "" : sourcePath;
        snapshotId = snapshotId == null ? "" : snapshotId;
        scopeKey = scopeKey == null ? "" : scopeKey;
    }

    public record TaskRequirementResult(
            String taskId,
            String type,
            String targetId,
            double current,
            double required,
            boolean completed,
            String title
    ) {
    }

    public record TaskRewardResult(
            String rewardId,
            String type,
            String title,
            boolean guaranteed,
            List<String> candidates
    ) {
        public TaskRewardResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
