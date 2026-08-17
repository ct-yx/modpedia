package io.ctyx.modpedia.task;

import java.util.List;

/** 可选任务适配器输出的与具体 API 解耦的快照。 */
public record TaskSnapshot(
        String snapshotId,
        String sourceKey,
        String fingerprint,
        String scopeKey,
        String version,
        String rawJson,
        List<TaskQuest> quests
) {
    public TaskSnapshot {
        snapshotId = text(snapshotId, "runtime");
        sourceKey = text(sourceKey, snapshotId);
        fingerprint = text(fingerprint, "runtime");
        scopeKey = text(scopeKey, "local");
        version = text(version, "unknown");
        rawJson = rawJson == null ? "{}" : rawJson;
        quests = quests == null ? List.of() : List.copyOf(quests);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public record TaskQuest(
            String questId,
            String parentId,
            String title,
            String subtitleMarkdown,
            String descriptionMarkdown,
            boolean optional,
            boolean visible,
            boolean started,
            boolean completed,
            int sortIndex,
            List<String> dependencies,
            List<TaskRequirement> tasks,
            List<TaskReward> rewards,
            String rawJson
    ) {
        public TaskQuest {
            questId = text(questId, "unknown");
            parentId = text(parentId, "");
            title = text(title, questId);
            subtitleMarkdown = subtitleMarkdown == null ? "" : subtitleMarkdown;
            descriptionMarkdown = descriptionMarkdown == null ? "" : descriptionMarkdown;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
            rawJson = rawJson == null ? "{}" : rawJson;
        }
    }

    public record TaskRequirement(
            String taskId,
            String type,
            String title,
            String targetId,
            double current,
            double required,
            boolean completed,
            String rawJson
    ) {
        public TaskRequirement {
            taskId = text(taskId, "unknown");
            type = text(type, "unknown");
            title = text(title, targetId);
            targetId = text(targetId, "");
            rawJson = rawJson == null ? "{}" : rawJson;
        }
    }

    public record TaskReward(
            String rewardId,
            String type,
            String title,
            boolean guaranteed,
            List<String> candidates,
            String rawJson
    ) {
        public TaskReward {
            rewardId = text(rewardId, "unknown");
            type = text(type, "unknown");
            title = text(title, type);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            rawJson = rawJson == null ? "{}" : rawJson;
        }
    }
}
