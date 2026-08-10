package io.ctyx.modpedia.ai;

import dev.langchain4j.community.store.memory.chat.sql.SQLChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.ctyx.modpedia.ModPedia;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ModPedia 对 Community SQL ChatMemoryStore 的轻量装配层。
 *
 * <p>数据库读写、消息序列化和 ChatMemoryStore 契约由社区实现负责；本类只处理
 * SQLite 文件路径、旧版会话 JSON 的一次性迁移，以及失败重试时的工具调用清理。</p>
 */
public final class PersistentChatMemoryStore implements ChatMemoryStore {
    private static final String DATABASE_FILE = "memory.sqlite";
    private static final String TABLE_NAME = "chat_memory";
    private static final String MEMORY_ID_COLUMN = "memory_id";
    private static final String CONTENT_COLUMN = "messages_json";

    private final ConversationStore conversations;
    private final SQLChatMemoryStore delegate;
    private final Path databasePath;

    public PersistentChatMemoryStore(ConversationStore conversations) {
        this(conversations, databasePath(conversations));
    }

    PersistentChatMemoryStore(ConversationStore conversations, Path databasePath) {
        if (conversations == null) {
            throw new IllegalArgumentException("conversations must not be null");
        }
        this.conversations = conversations;
        this.databasePath = databasePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.databasePath.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 AI 上下文数据库目录：" + this.databasePath, exception);
        }

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + this.databasePath);
        this.delegate = SQLChatMemoryStore.builder()
                .dataSource(dataSource)
                .sqlDialect(new SQLiteDialect())
                .tableName(TABLE_NAME)
                .memoryIdColumnName(MEMORY_ID_COLUMN)
                .contentColumnName(CONTENT_COLUMN)
                .autoCreateTable(true)
                .build();
    }

    static Path databasePath(ConversationStore conversations) {
        if (conversations == null) {
            throw new IllegalArgumentException("conversations must not be null");
        }
        return conversations.root().resolve(DATABASE_FILE);
    }

    Path databasePath() {
        return databasePath;
    }

    @Override
    public synchronized List<ChatMessage> getMessages(Object memoryId) {
        return loadMessages(String.valueOf(memoryId));
    }

    @Override
    public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        List<ChatMessage> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        // 社区实现使用官方 ChatMessageSerializer，能够保留工具调用 ID 和消息顺序。
        delegate.updateMessages(id, safeMessages);
        clearLegacyMessages(id);
    }

    @Override
    public synchronized void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        delegate.deleteMessages(id);
        clearLegacyMessages(id);
    }

    /**
     * 在创建新的 AI 请求前清理孤立的工具调用尾部。
     *
     * @return 被删除的尾部消息数量；{@code -1} 表示上下文无法读取
     */
    public synchronized int repair(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return 0;
        }
        try {
            List<ChatMessage> messages = loadMessages(memoryId);
            if (messages.isEmpty()) {
                return 0;
            }
            List<ChatMessage> sanitized = removeIncompleteToolTurn(messages);
            if (sanitized.size() != messages.size()) {
                updateMessages(memoryId, sanitized);
            }
            return messages.size() - sanitized.size();
        } catch (RuntimeException exception) {
            ModPedia.LOGGER.warn(
                    "AI memory repair failed: conversation={}, reason={}",
                    memoryId,
                    messageOf(exception)
            );
            return -1;
        }
    }

    /**
     * 重试请求前移除当前未完成的最后一轮用户输入及其工具调用。
     * 保留更早的完整轮次，避免同一个旧的 function call ID 再次进入请求。
     */
    public synchronized int prepareForRetry(String memoryId) {
        return prepareForRetry(memoryId, "");
    }

    /**
     * 只移除本次请求对应的用户消息及其未完成尾部。
     *
     * <p>流式请求可能在 LangChain4j 把当前用户消息写入持久化存储前就失败；
     * 这时无条件删除“最后一个用户消息”会误删上一轮成功对话。</p>
     */
    public synchronized int prepareForRetry(String memoryId, String expectedPrompt) {
        if (memoryId == null || memoryId.isBlank()) {
            return 0;
        }
        repair(memoryId);
        List<ChatMessage> messages = loadMessages(memoryId);
        int lastUser = -1;
        String expected = expectedPrompt == null ? "" : expectedPrompt.strip();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage user
                    && (expected.isBlank()
                    || (user.hasSingleText() && expected.equals(user.singleText())))) {
                lastUser = index;
                break;
            }
        }
        if (lastUser < 0) {
            return 0;
        }
        List<ChatMessage> retained = List.copyOf(messages.subList(0, lastUser));
        updateMessages(memoryId, retained);
        return messages.size() - retained.size();
    }

    /** 兼容旧调用方；新的会话流程使用实例方法，确保读写同一个 SQLite store。 */
    @Deprecated
    public static int repair(ConversationStore conversations, String memoryId) {
        return new PersistentChatMemoryStore(conversations).repair(memoryId);
    }

    /** 兼容旧调用方；新的会话流程使用实例方法。 */
    @Deprecated
    public static int prepareForRetry(ConversationStore conversations, String memoryId) {
        return new PersistentChatMemoryStore(conversations).prepareForRetry(memoryId);
    }

    /** 兼容旧调用方；新的会话流程使用实例方法。 */
    @Deprecated
    public static int prepareForRetry(
            ConversationStore conversations,
            String memoryId,
            String expectedPrompt
    ) {
        return new PersistentChatMemoryStore(conversations).prepareForRetry(memoryId, expectedPrompt);
    }

    private List<ChatMessage> loadMessages(String memoryId) {
        try {
            List<ChatMessage> stored = delegate.getMessages(memoryId);
            if (!stored.isEmpty()) {
                return List.copyOf(stored);
            }
        } catch (RuntimeException exception) {
            // 旧版 JSON 仍可能是本次启动的可用回退；如果没有旧数据，再以空上下文启动。
            ModPedia.LOGGER.warn(
                    "AI SQLite memory read failed, trying legacy conversation JSON: conversation={}, reason={}",
                    memoryId,
                    messageOf(exception)
            );
        }

        String legacyJson = conversations.memoryMessagesJson(memoryId);
        if (legacyJson == null || legacyJson.isBlank()) {
            return List.of();
        }
        try {
            List<ChatMessage> legacy = List.copyOf(ChatMessageDeserializer.messagesFromJson(legacyJson));
            try {
                delegate.updateMessages(memoryId, legacy);
                clearLegacyMessages(memoryId);
            } catch (RuntimeException exception) {
                // 导入失败时继续使用旧 JSON；下一次读取仍会重试导入。
                ModPedia.LOGGER.warn(
                        "AI legacy memory import failed, keeping old conversation JSON: conversation={}, reason={}",
                        memoryId,
                        messageOf(exception)
                );
            }
            return legacy;
        } catch (RuntimeException exception) {
            ModPedia.LOGGER.warn(
                    "AI legacy memory JSON is invalid: conversation={}, reason={}",
                    memoryId,
                    messageOf(exception)
            );
            return List.of();
        }
    }

    private void clearLegacyMessages(String memoryId) {
        if (!conversations.memoryMessagesJson(memoryId).isBlank()) {
            conversations.updateMemoryMessages(memoryId, "");
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    /** 删除上游请求失败后遗留的“AI 工具调用但没有对应工具结果”尾部。 */
    static List<ChatMessage> removeIncompleteToolTurn(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            // 工具结果本身是完整工具回合的一部分，不能在这里直接截断。
            if (message instanceof ToolExecutionResultMessage) {
                continue;
            }
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }

            Set<String> pending = new HashSet<>();
            aiMessage.toolExecutionRequests().forEach(request -> {
                if (request == null || request.id() == null || request.id().isBlank()) {
                    pending.add("__invalid_tool_request__");
                } else {
                    pending.add(request.id());
                }
            });
            int next = index + 1;
            while (!pending.isEmpty() && next < messages.size()) {
                ChatMessage result = messages.get(next);
                if (!(result instanceof ToolExecutionResultMessage toolResult)
                        || !pending.remove(toolResult.id())) {
                    break;
                }
                next++;
            }
            if (!pending.isEmpty()) {
                return List.copyOf(messages.subList(0, index));
            }
            index = next - 1;
        }
        return List.copyOf(new ArrayList<>(messages));
    }
}
