package io.ctyx.modpedia.api;

/** 历史会话列表使用的轻量摘要。 */
public record ConversationSummary(
        String id,
        String title,
        long updatedAt,
        int messageCount
) {
    public ConversationSummary {
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? "新会话" : title;
        messageCount = Math.max(0, messageCount);
    }
}
