package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.community.store.memory.chat.sql.SQLChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * ModPedia 对 Community SQL ChatMemoryStore 的轻量装配层。
 *
 * <p>数据库读写、消息序列化和 ChatMemoryStore 契约由社区实现负责；本类只处理
 * SQLite 文件路径、旧版会话 JSON 的一次性迁移，以及失败重试时的工具调用清理。</p>
 */
public final class PersistentChatMemoryStore implements ChatMemoryStore {
    private static final Logger LOG = Logger.getLogger("ModPediaChatMemory");
    private static final String DATABASE_FILE = "memory.sqlite";
    private static final String TABLE_NAME = "chat_memory";
    private static final String MEMORY_ID_COLUMN = "memory_id";
    private static final String CONTENT_COLUMN = "messages_json";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 最近两次工具检索仍保留完整结果；更早结果只压缩重复正文，不删除证据身份。 */
    private static final int FULL_TOOL_TURNS = 2;
    private static final int HISTORICAL_SEGMENT_CHARS = 2_400;
    private static final int HISTORICAL_DESCRIPTION_CHARS = 900;
    private static final int HISTORICAL_STRING_CHARS = 800;
    private static final String COMPACTED_MARKER = "history_compacted";

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
        List<ChatMessage> compacted = compactToolHistory(safeMessages);
        // 社区实现使用官方 ChatMessageSerializer，能够保留工具调用 ID 和消息顺序。
        delegate.updateMessages(id, compacted);
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
                // 修复失败尾部时保留此前完整工具证据，供 prepareForRetry 精确移除
                // 当前用户消息；下一次正常上下文更新再执行成本压缩。
                delegate.updateMessages(memoryId, sanitized);
                clearLegacyMessages(memoryId);
            }
            return messages.size() - sanitized.size();
        } catch (RuntimeException exception) {
            LOG.warning("AI memory repair failed: conversation=" + memoryId
                    + ", reason=" + messageOf(exception));
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
            LOG.warning("AI SQLite memory read failed, trying legacy conversation JSON: conversation="
                    + memoryId + ", reason=" + messageOf(exception));
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
                LOG.warning("AI legacy memory import failed, keeping old conversation JSON: conversation="
                        + memoryId + ", reason=" + messageOf(exception));
            }
            return legacy;
        } catch (RuntimeException exception) {
            LOG.warning("AI legacy memory JSON is invalid: conversation="
                    + memoryId + ", reason=" + messageOf(exception));
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
        List<ChatMessage> complete = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        int toolTurnStart = -1;

        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (!pending.isEmpty()) {
                // Tool result 必须紧跟发出对应请求的 AiMessage，并且 ID 必须匹配。
                // 一旦出现普通消息、孤立结果或错误 ID，整个工具回合从请求处截断。
                if (!(message instanceof ToolExecutionResultMessage toolResult)
                        || toolResult.id() == null
                        || !pending.remove(toolResult.id())) {
                    return List.copyOf(messages.subList(0, toolTurnStart));
                }
                complete.add(message);
                continue;
            }

            if (message instanceof ToolExecutionResultMessage) {
                // 没有前置 Ai tool call 的结果不能交给网关；保留此前完整上下文。
                return List.copyOf(messages.subList(0, index));
            }

            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                pending.clear();
                for (var request : aiMessage.toolExecutionRequests()) {
                    if (request == null || request.id() == null || request.id().isBlank()
                            || !pending.add(request.id())) {
                        return List.copyOf(messages.subList(0, index));
                    }
                }
                toolTurnStart = index;
            }
            complete.add(message);
        }

        // 到达列表末尾仍有未返回的工具结果，删除从 Ai tool call 开始的尾部。
        if (!pending.isEmpty() && toolTurnStart >= 0) {
            return List.copyOf(messages.subList(0, toolTurnStart));
        }
        return List.copyOf(complete);
    }

    /**
     * 对历史工具证据做温和压缩，而不是删除整个工具回合。
     *
     * <p>最近两次工具回合保持完整；更早回合保留工具调用、查询条件、来源 ID、标题、
     * 标题路径、来源路径和正文首尾片段。这样上下文仍然有界，但模型在多轮追问时
     * 可以知道此前查过什么、证据来自哪里，以及旧答案依赖的关键步骤。</p>
     */
    static List<ChatMessage> compactToolHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int latestUser = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                latestUser = index;
                break;
            }
        }
        if (latestUser < 0) {
            return List.copyOf(messages);
        }

        List<ToolTurn> turns = new ArrayList<>();
        for (int index = 0; index < latestUser; index++) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            int endExclusive = completeToolTurnEnd(messages, index);
            if (endExclusive > index && endExclusive <= latestUser) {
                turns.add(new ToolTurn(index, endExclusive));
                index = endExclusive - 1;
            }
        }
        if (turns.size() <= FULL_TOOL_TURNS) {
            return List.copyOf(messages);
        }

        int fullStart = turns.size() - FULL_TOOL_TURNS;
        List<ChatMessage> compacted = new ArrayList<>(messages);
        for (int turnIndex = 0; turnIndex < fullStart; turnIndex++) {
            ToolTurn turn = turns.get(turnIndex);
            for (int index = turn.startInclusive(); index < turn.endExclusive(); index++) {
                ChatMessage message = compacted.get(index);
                if (message instanceof ToolExecutionResultMessage result) {
                    if (!result.hasSingleText()) {
                        continue;
                    }
                    var replacement = ToolExecutionResultMessage.builder()
                            .id(result.id())
                            .toolName(result.toolName())
                            .text(compactToolResult(result.text()));
                    if (result.isError() != null) {
                        replacement.isError(result.isError());
                    }
                    if (!result.attributes().isEmpty()) {
                        replacement.attributes(result.attributes());
                    }
                    compacted.set(index, replacement.build());
                }
            }
        }
        return List.copyOf(compacted);
    }

    private record ToolTurn(int startInclusive, int endExclusive) {
    }

    /** 保留旧工具结果的结构化事实；无法解析的第三方工具结果保留首尾文本。 */
    static String compactToolResult(String text) {
        if (text == null || text.isBlank() || text.contains("\"" + COMPACTED_MARKER + "\"")) {
            return text == null ? "" : text;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                return JSON.toJson(compactJsonObject(parsed.getAsJsonObject()));
            }
        } catch (RuntimeException ignored) {
            // 工具可能返回普通文本；继续使用首尾压缩，不让一次格式异常丢掉整个历史。
        }
        return compactText(text, HISTORICAL_SEGMENT_CHARS);
    }

    private static JsonObject compactJsonObject(JsonObject source) {
        JsonObject target = new JsonObject();
        source.entrySet().forEach(entry -> {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (COMPACTED_MARKER.equals(key)) {
                return;
            }
            if ("results".equals(key) || "item_context".equals(key)
                    || "timeline".equals(key) || "requirements".equals(key)
                    || "rewards".equals(key) || "candidates".equals(key)
                    || "unmet_dependencies".equals(key)) {
                target.add(key, compactJsonValue(value, key));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                target.addProperty(key, compactText(value.getAsString(), HISTORICAL_STRING_CHARS));
            } else {
                target.add(key, value.deepCopy());
            }
        });
        target.addProperty(COMPACTED_MARKER, true);
        target.addProperty("history_note", "较早工具证据已压缩；保留来源、标题和正文首尾片段，当前轮结果保持完整");
        return target;
    }

    private static JsonElement compactJsonValue(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) {
            return value;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                array.add(compactJsonValue(item, key));
            }
            return array;
        }
        if (!value.isJsonObject()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return JSON.toJsonTree(compactText(value.getAsString(), stringLimit(key)));
            }
            return value.deepCopy();
        }
        JsonObject source = value.getAsJsonObject();
        JsonObject target = new JsonObject();
        source.entrySet().forEach(entry -> {
            String field = entry.getKey();
            JsonElement fieldValue = entry.getValue();
            if (fieldValue.isJsonPrimitive() && fieldValue.getAsJsonPrimitive().isString()) {
                target.addProperty(field, compactText(fieldValue.getAsString(), stringLimit(field)));
            } else if (fieldValue.isJsonArray() || fieldValue.isJsonObject()) {
                target.add(field, compactJsonValue(fieldValue, field));
            } else {
                target.add(field, fieldValue.deepCopy());
            }
        });
        return target;
    }

    private static int stringLimit(String field) {
        String normalized = field == null ? "" : field.toLowerCase(Locale.ROOT);
        if (normalized.contains("segment") || normalized.contains("description")
                || normalized.equals("markdown") || normalized.equals("body")) {
            return normalized.contains("description")
                    ? HISTORICAL_DESCRIPTION_CHARS
                    : HISTORICAL_SEGMENT_CHARS;
        }
        if (normalized.contains("path") || normalized.endsWith("_id")
                || normalized.equals("title") || normalized.equals("heading_path")) {
            return 1_200;
        }
        return HISTORICAL_STRING_CHARS;
    }

    private static String compactText(String text, int maximum) {
        String value = text == null ? "" : text;
        if (maximum <= 0 || value.length() <= maximum) {
            return value;
        }
        int head = Math.max(1, maximum * 2 / 3);
        int tail = Math.max(1, maximum - head);
        return value.substring(0, head)
                + "\n…[ModPedia 历史证据中段已压缩，来源和关键首尾内容保留]…\n"
                + value.substring(value.length() - tail);
    }

    private static int completeToolTurnEnd(List<ChatMessage> messages, int start) {
        AiMessage aiMessage = (AiMessage) messages.get(start);
        Set<String> pending = new LinkedHashSet<>();
        for (var request : aiMessage.toolExecutionRequests()) {
            if (request == null || request.id() == null || request.id().isBlank()
                    || !pending.add(request.id())) {
                return -1;
            }
        }
        int end = start + 1;
        while (end < messages.size() && !pending.isEmpty()) {
            ChatMessage message = messages.get(end);
            if (!(message instanceof ToolExecutionResultMessage result)
                    || result.id() == null
                    || !pending.remove(result.id())) {
                return -1;
            }
            end++;
        }
        return pending.isEmpty() ? end : -1;
    }
}
