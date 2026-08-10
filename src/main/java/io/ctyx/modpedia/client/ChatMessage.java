package io.ctyx.modpedia.client;

import java.util.List;

public record ChatMessage(
        MessageRole role,
        String markdown,
        List<SourceReference> sources,
        List<String> followUpQuestions
) {
    /** 兼容旧版会话和现有调用方。 */
    public ChatMessage(MessageRole role, String markdown, List<SourceReference> sources) {
        this(role, markdown, sources, List.of());
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
    }
}
