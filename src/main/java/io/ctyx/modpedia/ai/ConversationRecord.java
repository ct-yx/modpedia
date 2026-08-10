package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.client.ChatMessage;

import java.util.List;

/** UI 会话文件的可持久化结构；memoryMessagesJson 仅用于旧版本上下文迁移。 */
public record ConversationRecord(
        String id,
        String title,
        long createdAt,
        long updatedAt,
        List<ChatMessage> messages,
        List<SearchTrace> searchTraces,
        String memoryMessagesJson
) {
    public ConversationRecord {
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? "新会话" : title;
        messages = messages == null ? List.of() : List.copyOf(messages);
        searchTraces = searchTraces == null ? List.of() : List.copyOf(searchTraces);
        memoryMessagesJson = memoryMessagesJson == null ? "" : memoryMessagesJson;
    }
}
