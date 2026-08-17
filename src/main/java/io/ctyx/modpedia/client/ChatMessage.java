package io.ctyx.modpedia.client;

import io.ctyx.modpedia.task.TaskSearchSummary;

import java.util.List;

public record ChatMessage(
        MessageRole role,
        String markdown,
        List<SourceReference> sources,
        List<String> followUpQuestions,
        TaskSearchSummary taskSummary
) {
    /** 兼容旧版会话和现有调用方。 */
    public ChatMessage(MessageRole role, String markdown, List<SourceReference> sources) {
        this(role, markdown, sources, List.of(), null);
    }

    /** 兼容现有调用方；没有任务查询摘要时保持空值。 */
    public ChatMessage(
            MessageRole role,
            String markdown,
            List<SourceReference> sources,
            List<String> followUpQuestions
    ) {
        this(role, markdown, sources, followUpQuestions, null);
    }

    public ChatMessage {
        role = role == null ? MessageRole.ASSISTANT : role;
        markdown = markdown == null ? "" : markdown;
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (followUpQuestions == null || followUpQuestions.isEmpty()) {
            followUpQuestions = List.of();
        } else {
            followUpQuestions = followUpQuestions.stream()
                    .filter(question -> question != null && !question.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(3)
                    .toList();
        }
        if (taskSummary != null && !taskSummary.visible()) {
            taskSummary = null;
        }
    }
}
