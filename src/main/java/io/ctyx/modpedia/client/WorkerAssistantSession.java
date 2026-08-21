package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.api.ConversationSummary;
import io.ctyx.modpedia.api.MessageRole;
import io.ctyx.modpedia.api.SourceReference;
import io.ctyx.modpedia.knowledge.BuiltInGuide;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.task.TaskSearchSummary;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 游戏 JVM 中的轻量会话代理。
 *
 * <p>本类只维护 UI 快照和 IPC 请求 ID；AI、上下文、历史、SQLite 与 Markdown
 * 都由 Worker JVM 执行。Worker 断线时直接发布错误状态，不回退到游戏线程执行重活。</p>
 */
public final class WorkerAssistantSession implements AssistantSession {
    private final ModPediaBridge bridge;
    private final CopyOnWriteArrayList<Consumer<AssistantUiState>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<JsonObject> bridgeListener = this::receiveFromWorker;
    private final Map<String, Boolean> cancelledRequests = new ConcurrentHashMap<>();

    private volatile AssistantUiState state = new AssistantUiState.Conversation(List.of(), false);
    private volatile List<ConversationSummary> conversationSummaries = List.of();
    private volatile String activeConversationId = "";
    private volatile String activeConversationTitle = "新会话";
    private volatile String activeRequestId = "";
    private volatile String lastPrompt = "";
    private volatile String assistantDraft = "";

    public WorkerAssistantSession() {
        this(ModPediaBridge.get());
    }

    WorkerAssistantSession(ModPediaBridge bridge) {
        this.bridge = bridge;
        bridge.addClientListener(bridgeListener);
    }

    @Override
    public AssistantUiState state() {
        return state;
    }

    @Override
    public void submit(String prompt) {
        submitInternal(prompt, false);
    }

    private void submitInternal(String prompt, boolean retry) {
        String normalized = prompt == null ? "" : prompt.strip();
        if (normalized.isBlank() || isLoading()) {
            return;
        }
        if (!bridge.isReady()) {
            publish(new AssistantUiState.Error(state.messages(), "ModPedia Worker 尚未就绪，请重启游戏后重试。"));
            return;
        }

        List<ChatMessage> messages = new ArrayList<>(state.messages());
        if (retry && !messages.isEmpty() && messages.getLast().role() == MessageRole.USER) {
            messages.removeLast();
        }
        messages.add(new ChatMessage(MessageRole.USER, normalized, List.of()));
        String requestId = UUID.randomUUID().toString();
        activeRequestId = requestId;
        lastPrompt = normalized;
        assistantDraft = "";
        publish(new AssistantUiState.Loading(messages, "正在分析问题……", ""));

        boolean sent = bridge.startChat(
                requestId,
                activeConversationId,
                normalized,
                currentLanguage(),
                retry,
                null
        );
        if (!sent) {
            activeRequestId = "";
            publish(new AssistantUiState.Error(messages, "ModPedia Worker 连接不可用，请稍后重试。"));
        }
    }

    @Override
    public void showBuiltInGuide(String documentId) {
        if (BuiltInGuide.isSupported(documentId)) {
            submit("如何开始使用这个助手");
        }
    }

    @Override
    public void cancel() {
        String requestId = activeRequestId;
        if (requestId.isBlank()) {
            return;
        }
        cancelledRequests.put(requestId, Boolean.TRUE);
        activeRequestId = "";
        assistantDraft = "";
        bridge.cancel(requestId);
        publish(new AssistantUiState.Conversation(state.messages(), false));
    }

    @Override
    public void retry() {
        if (lastPrompt.isBlank() || isLoading()) {
            return;
        }
        submitInternal(lastPrompt, true);
    }

    @Override
    public void clear() {
        cancel();
        String requestId = UUID.randomUUID().toString();
        if (!bridge.command(WorkerProtocol.CONVERSATION_CLEAR, requestId, Map.of())) {
            activeConversationId = "";
            activeConversationTitle = "新会话";
            lastPrompt = "";
            publish(new AssistantUiState.Conversation(List.of(), false));
        }
    }

    @Override
    public void addListener(Consumer<AssistantUiState> listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
        listener.accept(state);
    }

    @Override
    public void removeListener(Consumer<AssistantUiState> listener) {
        listeners.remove(listener);
    }

    @Override
    public List<ConversationSummary> conversations() {
        return conversationSummaries;
    }

    @Override
    public String activeConversationId() {
        return activeConversationId;
    }

    @Override
    public String activeConversationTitle() {
        return activeConversationTitle;
    }

    @Override
    public void newConversation() {
        cancel();
        String requestId = UUID.randomUUID().toString();
        if (!bridge.command(WorkerProtocol.CONVERSATION_NEW, requestId, Map.of())) {
            publish(new AssistantUiState.Error(state.messages(), "ModPedia Worker 连接不可用。"));
        }
    }

    @Override
    public void selectConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        cancel();
        String requestId = UUID.randomUUID().toString();
        if (!bridge.command(
                WorkerProtocol.CONVERSATION_SELECT,
                requestId,
                Map.of("conversation_id", conversationId)
        )) {
            publish(new AssistantUiState.Error(state.messages(), "ModPedia Worker 连接不可用。"));
        }
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        if (!bridge.command(
                WorkerProtocol.CONVERSATION_RENAME,
                requestId,
                Map.of("conversation_id", conversationId, "title", title == null ? "" : title)
        )) {
            publish(new AssistantUiState.Error(state.messages(), "ModPedia Worker 连接不可用。"));
        }
    }

    @Override
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        cancel();
        String requestId = UUID.randomUUID().toString();
        if (!bridge.command(
                WorkerProtocol.CONVERSATION_DELETE,
                requestId,
                Map.of("conversation_id", conversationId)
        )) {
            publish(new AssistantUiState.Error(state.messages(), "ModPedia Worker 连接不可用。"));
        }
    }

    private void receiveFromWorker(JsonObject event) {
        String requestId = WorkerProtocol.string(event, "request_id");
        if (!requestId.isBlank() && cancelledRequests.containsKey(requestId)) {
            if (isTerminal(event)) {
                cancelledRequests.remove(requestId);
            }
            return;
        }
        applyEvent(event);
    }

    private void applyEvent(JsonObject event) {
        String type = WorkerProtocol.string(event, "type");
        String requestId = WorkerProtocol.string(event, "request_id");
        if (WorkerProtocol.CONVERSATION_STATE.equals(type)) {
            applyConversationState(event);
            return;
        }
        if (!requestId.isBlank() && !activeRequestId.isBlank() && !requestId.equals(activeRequestId)) {
            return;
        }
        switch (type) {
            case WorkerProtocol.STATUS, WorkerProtocol.TOOL_CALL -> {
                String message = WorkerProtocol.string(event, "message");
                if (message.isBlank()) {
                    message = "query_item_recipes".equals(WorkerProtocol.string(event, "tool"))
                            ? recipeToolStatus(event)
                            : switch (WorkerProtocol.string(event, "status")) {
                        case "task_runtime_read" -> "正在读取当前任务进度……";
                        case "task_runtime_file_read" -> "正在读取本地任务存档……";
                        case "task_database_query" -> "已读取任务进度，正在查询任务资料……";
                        case "searching" -> "正在搜索本地知识库……";
                        case "results" -> "正在核对本地资料……";
                        default -> "正在处理问题……";
                    };
                }
                publish(new AssistantUiState.Loading(state.messages(), message, assistantDraft));
            }
            case WorkerProtocol.TEXT_DELTA -> {
                assistantDraft += WorkerProtocol.string(event, "text");
                publish(new AssistantUiState.Loading(state.messages(), "正在整理回答……", assistantDraft));
            }
            case WorkerProtocol.COMPLETED -> {
                List<ChatMessage> messages = messages(event);
                if (messages.isEmpty()) {
                    messages = new ArrayList<>(state.messages());
                    String answer = WorkerProtocol.string(event, "answer");
                    if (!answer.isBlank()) {
                        messages.add(new ChatMessage(
                                MessageRole.ASSISTANT,
                                answer,
                                sources(event),
                                WorkerPayloadCodec.strings(WorkerPayloadCodec.array(event, "follow_up_questions")),
                                taskSummary(event)
                        ));
                    }
                }
                updateConversationMetadata(event);
                activeRequestId = "";
                assistantDraft = "";
                publish(new AssistantUiState.Conversation(messages, false));
            }
            case WorkerProtocol.ERROR -> {
                updateConversationMetadata(event);
                activeRequestId = "";
                assistantDraft = "";
                publish(new AssistantUiState.Error(
                        messagesOrCurrent(event),
                        WorkerProtocol.string(event, "message").isBlank()
                                ? "Worker 请求失败"
                                : WorkerProtocol.string(event, "message")
                ));
            }
            case WorkerProtocol.CANCELLED -> {
                activeRequestId = "";
                assistantDraft = "";
                publish(new AssistantUiState.Conversation(state.messages(), false));
            }
            default -> {
                // hello、pong 和知识库维护事件不改变聊天 UI。
            }
        }
    }

    private String recipeToolStatus(JsonObject event) {
        return switch (WorkerProtocol.string(event, "mode")) {
            case "WORKBENCH" -> "正在查询工作台合成配方……";
            case "FURNACE" -> "正在查询熔炉烧炼配方……";
            case "DETAIL" -> "正在读取选定处理方式的详细配方……";
            default -> "正在读取可用的物品处理方式……";
        };
    }

    private void applyConversationState(JsonObject event) {
        updateConversationMetadata(event);
        List<ChatMessage> messages = messages(event);
        if (!(state instanceof AssistantUiState.Loading) || activeRequestId.isBlank()) {
            publish(new AssistantUiState.Conversation(messages, false));
        }
    }

    private void updateConversationMetadata(JsonObject event) {
        String id = WorkerProtocol.string(event, "active_conversation_id");
        if (!id.isBlank()) {
            activeConversationId = id;
        }
        String title = WorkerProtocol.string(event, "active_title");
        if (!title.isBlank()) {
            activeConversationTitle = title;
        }
        JsonArray summaries = WorkerPayloadCodec.array(event, "conversations");
        if (!summaries.isEmpty()) {
            List<ConversationSummary> result = new ArrayList<>();
            for (JsonElement value : summaries) {
                if (value.isJsonObject()) {
                    result.add(WorkerPayloadCodec.summary(value.getAsJsonObject()));
                }
            }
            conversationSummaries = List.copyOf(result);
        }
    }

    private List<ChatMessage> messages(JsonObject event) {
        JsonArray values = WorkerPayloadCodec.array(event, "messages");
        List<ChatMessage> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonObject()) {
                result.add(WorkerPayloadCodec.chatMessage(value.getAsJsonObject()));
            }
        }
        return List.copyOf(result);
    }

    private List<ChatMessage> messagesOrCurrent(JsonObject event) {
        List<ChatMessage> result = messages(event);
        return result.isEmpty() ? state.messages() : result;
    }

    private List<SourceReference> sources(JsonObject event) {
        JsonArray values = WorkerPayloadCodec.array(event, "sources");
        List<SourceReference> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonObject()) {
                result.add(WorkerPayloadCodec.source(value.getAsJsonObject()));
            }
        }
        return List.copyOf(result);
    }

    private TaskSearchSummary taskSummary(JsonObject event) {
        return event != null
                && event.has("task_summary")
                && event.get("task_summary").isJsonObject()
                ? WorkerPayloadCodec.taskSummary(event.getAsJsonObject("task_summary"))
                : null;
    }

    private boolean isTerminal(JsonObject event) {
        String type = WorkerProtocol.string(event, "type");
        return WorkerProtocol.COMPLETED.equals(type)
                || WorkerProtocol.ERROR.equals(type)
                || WorkerProtocol.CANCELLED.equals(type);
    }

    private String currentLanguage() {
        try {
            return SearchLanguage.fromMinecraft(Minecraft.getInstance().options.languageCode).code();
        } catch (Throwable ignored) {
            return "zh_cn";
        }
    }

    private void publish(AssistantUiState next) {
        state = next;
        listeners.forEach(listener -> {
            try {
                listener.accept(next);
            } catch (Throwable ignored) {
                // 单个 UI 监听器异常不影响 Worker 事件消费。
            }
        });
    }
}
