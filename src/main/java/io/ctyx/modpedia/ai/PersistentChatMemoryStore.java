package io.ctyx.modpedia.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

/** LangChain4j ChatMemoryStore 到 ModPedia 会话文件的适配器。 */
public final class PersistentChatMemoryStore implements ChatMemoryStore {
    private final ConversationStore conversations;

    public PersistentChatMemoryStore(ConversationStore conversations) {
        this.conversations = conversations;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = conversations.memoryMessagesJson(String.valueOf(memoryId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(ChatMessageDeserializer.messagesFromJson(json));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        conversations.updateMemoryMessages(
                String.valueOf(memoryId),
                ChatMessageSerializer.messagesToJson(messages)
        );
    }

    @Override
    public void deleteMessages(Object memoryId) {
        conversations.updateMemoryMessages(String.valueOf(memoryId), "");
    }
}
