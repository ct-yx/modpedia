package io.ctyx.modpedia.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import io.ctyx.modpedia.ai.AiClient;
import io.ctyx.modpedia.ai.AiResponseSanitizer;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AiSettingsStore;
import io.ctyx.modpedia.ai.AssistantMode;
import io.ctyx.modpedia.ai.CalculationTool;
import io.ctyx.modpedia.ai.ConversationStore;
import io.ctyx.modpedia.ai.ConversationRecord;
import io.ctyx.modpedia.ai.FollowUpQuestionParser;
import io.ctyx.modpedia.ai.LocalSearchMessageFormatter;
import io.ctyx.modpedia.ai.PersistentChatMemoryStore;
import io.ctyx.modpedia.ai.PromptBuilder;
import io.ctyx.modpedia.ai.RecipeQueryTool;
import io.ctyx.modpedia.ai.SearchKnowledgeTool;
import io.ctyx.modpedia.ai.SearchTrace;
import io.ctyx.modpedia.ai.SourceCitationParser;
import io.ctyx.modpedia.ai.TaskQuestionClassifier;
import io.ctyx.modpedia.knowledge.BuiltInGuide;
import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.api.MessageRole;
import io.ctyx.modpedia.api.SourceReference;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.search.ItemQueryParser;
import io.ctyx.modpedia.search.KnowledgeScope;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.search.SearchQuery;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import io.ctyx.modpedia.task.TaskKnowledgeStore;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskRuntimeReadResult;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.TaskSearchSummary;
import io.ctyx.modpedia.recipe.RecipeQuery;
import io.ctyx.modpedia.recipe.RecipeQueryTrace;
import io.ctyx.modpedia.recipe.RecipeResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Worker 内的 AI、上下文、SQLite 检索和会话编排。 */
public final class WorkerChatService {
    private static final int MAX_DISPLAYED_SOURCES = 5;
    private static final CalculationTool CALCULATION_TOOL = new CalculationTool();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");

    private final Path knowledgeRoot;
    private final ConversationStore conversationStore;
    private final PersistentChatMemoryStore memoryStore;
    private final AiSettingsStore settingsStore;
    private final PromptBuilder promptBuilder = PromptBuilder.runtime();
    private final RetrievalService retrievalService;
    private final WorkerEventSink sink;
    private final RuntimeContextRequester runtimeContextRequester;
    private final RecipeQueryRequester recipeQueryRequester;
    private final Predicate<String> requestCancelled;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SearchTrace>> requestTraces =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestConversations = new ConcurrentHashMap<>();

    public WorkerChatService(
            Path knowledgeRoot,
            ConversationStore conversationStore,
            AiSettingsStore settingsStore,
            WorkerEventSink sink,
            RuntimeContextRequester runtimeContextRequester
    ) {
        this(
                knowledgeRoot,
                conversationStore,
                settingsStore,
                sink,
                runtimeContextRequester,
                null,
                ignored -> false
        );
    }

    public WorkerChatService(
            Path knowledgeRoot,
            ConversationStore conversationStore,
            AiSettingsStore settingsStore,
            WorkerEventSink sink,
            RuntimeContextRequester runtimeContextRequester,
            Predicate<String> requestCancelled
    ) {
        this(
                knowledgeRoot,
                conversationStore,
                settingsStore,
                sink,
                runtimeContextRequester,
                null,
                requestCancelled
        );
    }

    public WorkerChatService(
            Path knowledgeRoot,
            ConversationStore conversationStore,
            AiSettingsStore settingsStore,
            WorkerEventSink sink,
            RuntimeContextRequester runtimeContextRequester,
            RecipeQueryRequester recipeQueryRequester,
            Predicate<String> requestCancelled
    ) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.conversationStore = conversationStore;
        this.memoryStore = new PersistentChatMemoryStore(conversationStore);
        this.settingsStore = settingsStore;
        this.retrievalService = new RetrievalService(this.knowledgeRoot);
        this.sink = sink;
        this.runtimeContextRequester = runtimeContextRequester;
        this.recipeQueryRequester = recipeQueryRequester;
        this.requestCancelled = requestCancelled == null ? ignored -> false : requestCancelled;
    }

    public void handle(JsonObject request) throws Exception {
        String requestId = WorkerProtocol.string(request, "request_id");
        if (cancelled(requestId)) {
            return;
        }
        String conversationId = WorkerProtocol.string(request, "conversation_id");
        if (conversationId.isBlank()) {
            conversationId = conversationStore.activeId();
        }
        String prompt = WorkerProtocol.string(request, "prompt").strip();
        SearchLanguage language = parseLanguage(WorkerProtocol.string(request, "language"));
        if (prompt.isBlank()) {
            sendError(requestId, "问题为空");
            return;
        }
        requestConversations.put(requestId, conversationId);
        if (WorkerProtocol.bool(request, "retry", false)) {
            // 重试复用同一会话，但先移除上一次失败请求留下的用户消息和
            // LangChain 未完成工具回合，避免上下文出现重复 user 或孤立 tool call。
            conversationStore.removeLastMessageIfRole(conversationId, MessageRole.USER);
            memoryStore.prepareForRetry(conversationId, prompt);
        }
        requestTraces.put(requestId, new CopyOnWriteArrayList<>());
        if (BuiltInGuide.isUsageQuestion(prompt)) {
            completeBuiltInGuide(requestId, conversationId, language);
            return;
        }

        conversationStore.appendMessage(conversationId, new ChatMessage(
                MessageRole.USER,
                prompt,
                List.of()
        ));
        sendStatus(requestId, "analyzing", "正在分析问题……");
        AiSettings settings = settingsStore.load();
        retrievalService.setLanguage(language);
        if (settings.mode() == AssistantMode.SEARCH_ONLY) {
            completeLocalSearch(requestId, conversationId, prompt, language, settings);
            return;
        }
        if (!settings.configured()) {
            sendError(requestId, "请先打开助手设置，填写 API 地址和模型名称。");
            return;
        }
        if (settings.effectiveApiKey().isBlank()) {
            sendError(requestId, "请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。");
            return;
        }

        int rounds = settings.effectiveMaxRounds();
        int results = settings.effectiveMaxResults();
        int contextChars = settings.effectiveMaxContextChars();
        boolean taskQuestion = TaskQuestionClassifier.isTaskQuestion(prompt);
        SearchKnowledgeTool searchTool = createSearchTool(
                requestId,
                language,
                results,
                contextChars,
                rounds
        );
        RecipeQueryTool recipeTool = createRecipeTool(requestId, conversationId, results);
        if (settings.streaming()) {
            stream(requestId, conversationId, prompt, language, settings, rounds, searchTool, recipeTool, taskQuestion);
        } else {
            block(requestId, conversationId, prompt, language, settings, rounds, searchTool, recipeTool, taskQuestion);
        }
    }

    private void completeBuiltInGuide(String requestId, String conversationId, SearchLanguage language) {
        if (cancelled(requestId)) {
            return;
        }
        requestTraces.remove(requestId);
        String markdown = BuiltInGuide.readMarkdown(
                BuiltInGuide.ASSISTANT_USAGE_DOCUMENT_ID,
                retrievalService,
                language
        ).orElse("");
        if (markdown.isBlank()) {
            sendError(requestId, "ModPedia 内置说明文档未找到，请先完成 Worker 知识库构建。");
            return;
        }
        if (cancelled(requestId)) {
            return;
        }
        conversationStore.appendMessage(conversationId, new ChatMessage(
                MessageRole.USER, BuiltInGuide.prompt(), List.of()
        ));
        conversationStore.appendMessage(conversationId, new ChatMessage(
                MessageRole.ASSISTANT, markdown, List.of(BuiltInGuide.source())
        ));
        sendCompleted(requestId, markdown, List.of(BuiltInGuide.source()), List.of(), conversationId);
    }

    private void completeLocalSearch(
            String requestId,
            String conversationId,
            String prompt,
            SearchLanguage language,
            AiSettings settings
    ) {
        try {
            requestTraces.remove(requestId);
            ItemQueryParser.Parsed parsed = ItemQueryParser.parse(prompt);
            List<ItemCatalogEntry> itemContext = retrievalService.lookupItemContext(prompt, language);
            String query = parsed.searchableText();
            if (!itemContext.isEmpty()) {
                query += " " + itemContext.stream()
                        .map(item -> item.itemId() + " " + item.displayName())
                        .collect(java.util.stream.Collectors.joining(" "));
            }
            SearchResponse response = retrievalService.search(new SearchQuery(
                    query,
                    settings.effectiveMaxResults(),
                    language
            ));
            List<SourceReference> sources = response.results().stream().map(this::sourceOf).toList();
            if (cancelled(requestId)) {
                return;
            }
            ChatMessage answer = response.status() == SearchStatus.READY
                    ? LocalSearchMessageFormatter.format(prompt, response, itemContext)
                    : new ChatMessage(MessageRole.ASSISTANT,
                    response.status() == SearchStatus.INDEX_NOT_READY
                            ? "本地知识库尚未准备完成。"
                            : "没有找到与问题直接匹配的资料。",
                    sources);
            conversationStore.appendMessage(conversationId, answer);
            sendCompleted(requestId, answer.markdown(), sources, answer.followUpQuestions(), conversationId);
        } catch (Throwable failure) {
            sendError(requestId, "本地搜索失败：" + messageOf(failure));
        }
    }

    private void block(
            String requestId,
            String conversationId,
            String prompt,
            SearchLanguage language,
            AiSettings settings,
            int rounds,
            SearchKnowledgeTool searchTool,
            RecipeQueryTool recipeTool,
            boolean taskQuestion
    ) {
        if (cancelled(requestId)) {
            return;
        }
        try {
            sendStatus(requestId, "organizing", "正在整理回答……");
            BlockingAssistantService service = buildBlockingService(
                    AiClient.chatModel(settings), settings, language, rounds, searchTool, recipeTool, taskQuestion
            );
            finish(requestId, conversationId, service.chat(conversationId, prompt), searchTool.taskSummary());
        } catch (Throwable firstFailure) {
            if (cancelled(requestId)) {
                return;
            }
            logAiFailure(requestId, settings, firstFailure, false);
            if (AiClient.shouldRetryImmediately(firstFailure)) {
                try {
                    sleepBeforeRetry(firstFailure);
                    sendStatus(requestId, "retrying", "首次请求失败，正在自动重试……");
                    memoryStore.prepareForRetry(conversationId, prompt);
                    SearchKnowledgeTool retryTool = createSearchTool(
                            requestId, language, settings.effectiveMaxResults(),
                            settings.effectiveMaxContextChars(), rounds
                    );
                    RecipeQueryTool retryRecipeTool = createRecipeTool(requestId, conversationId,
                            settings.effectiveMaxResults());
                    BlockingAssistantService retry = buildBlockingService(
                            AiClient.chatModel(settings), settings, language, rounds, retryTool,
                            retryRecipeTool, taskQuestion
                    );
                    finish(requestId, conversationId, retry.chat(conversationId, prompt), retryTool.taskSummary());
                    return;
                } catch (Throwable retryFailure) {
                    if (cancelled(requestId)) {
                        return;
                    }
                    logAiFailure(requestId, settings, retryFailure, false);
                    sendError(requestId, "AI 请求失败："
                            + AiClient.friendlyError(retryFailure, settings.effectiveApiKey()));
                    return;
                }
            }
            if (cancelled(requestId)) {
                return;
            }
            sendError(requestId, "AI 请求失败："
                    + AiClient.friendlyError(firstFailure, settings.effectiveApiKey()));
        }
    }

    private void stream(
            String requestId,
            String conversationId,
            String prompt,
            SearchLanguage language,
            AiSettings settings,
            int rounds,
            SearchKnowledgeTool searchTool,
            RecipeQueryTool recipeTool,
            boolean taskQuestion
    ) {
        AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();
        AtomicBoolean terminal = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            StreamingChatModel model = AiClient.streamingChatModel(settings);
            StreamingAssistantService service = buildStreamingService(
                    model, settings, language, rounds, searchTool, recipeTool, taskQuestion
            );
            StringBuilder draft = new StringBuilder();
            TokenStream stream = service.chat(conversationId, prompt)
                    .onPartialResponseWithContext((PartialResponse partial, PartialResponseContext context) -> {
                        if (context != null && context.streamingHandle() != null) {
                            activeHandle.set(context.streamingHandle());
                        }
                        if (partial == null || partial.text() == null || partial.text().isEmpty()
                                || terminal.get() || cancelled(requestId)) {
                            return;
                        }
                        draft.append(partial.text());
                        JsonObject delta = WorkerProtocol.message(WorkerProtocol.TEXT_DELTA, requestId);
                        delta.addProperty("text", partial.text());
                        sendQuietly(delta);
                    })
                    .beforeToolExecution(ignored -> {
                        if (!terminal.get() && !cancelled(requestId)) {
                            sendStatus(requestId, "searching", "正在搜索本地知识库……");
                        }
                    })
                    .onToolExecuted(ignored -> {
                        if (!terminal.get() && !cancelled(requestId)) {
                            sendStatus(requestId, "results", "已获得本地资料，正在核对证据……");
                        }
                    })
                    .onCompleteResponse(response -> {
                        if (!terminal.compareAndSet(false, true)) {
                            finished.countDown();
                            return;
                        }
                        try {
                            if (cancelled(requestId)) {
                                return;
                            }
                            String answer = draft.isEmpty() ? responseText(response) : draft.toString();
                            try {
                                finish(requestId, conversationId, answer, searchTool.taskSummary());
                            } catch (Throwable failure) {
                                sendError(requestId, "AI 回答处理失败："
                                        + AiClient.friendlyError(failure, settings.effectiveApiKey()));
                            }
                        } finally {
                            finished.countDown();
                        }
                    })
                    .onError(error -> {
                        if (!terminal.compareAndSet(false, true)) {
                            finished.countDown();
                            return;
                        }
                        try {
                            if (!cancelled(requestId)) {
                                logAiFailure(requestId, settings, error, true);
                                sendError(requestId, "AI 流式请求失败："
                                        + AiClient.friendlyError(error, settings.effectiveApiKey()));
                            }
                        } finally {
                            finished.countDown();
                        }
                    });
            stream.start();
            // 模型自身有 timeout；Worker 再保留一个有限的总等待上限，避免
            // 流式断链后请求线程永久占住操作线程和历史会话。
            long waitSeconds = Math.max(30L, Math.min(360L, settings.timeoutSeconds() + 30L));
            if (!finished.await(waitSeconds, TimeUnit.SECONDS)) {
                StreamingHandle active = activeHandle.get();
                if (active != null) {
                    active.cancel();
                }
                // 先夺取终态闸门，再发超时错误。迟到的完成/错误/增量回调
                // 只能看到 terminal=true，不能再写会话或覆盖 ERROR。
                if (terminal.compareAndSet(false, true)) {
                    if (!cancelled(requestId)) {
                        sendError(requestId, "AI 流式请求超时");
                    }
                }
                finished.countDown();
            }
        } catch (InterruptedException interrupted) {
            StreamingHandle active = activeHandle.get();
            if (active != null) {
                active.cancel();
            }
            terminal.set(true);
            Thread.currentThread().interrupt();
            finished.countDown();
            requestTraces.remove(requestId);
        } catch (Throwable failure) {
            StreamingHandle active = activeHandle.get();
            if (active != null) {
                active.cancel();
            }
            if (terminal.compareAndSet(false, true) && !cancelled(requestId)) {
                logAiFailure(requestId, settings, failure, true);
                sendError(requestId, "AI 流式请求失败："
                        + AiClient.friendlyError(failure, settings.effectiveApiKey()));
            }
            finished.countDown();
        }
    }

    private void sleepBeforeRetry(Throwable failure) {
        long delay = AiClient.retryDelayMillis(failure);
        if (delay <= 0L) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 只记录模型、状态和异常类型，不记录 API Key、请求正文、会话内容或上游完整 JSON。
     * 这条日志用于区分网关 503、认证错误、工具格式错误和本地异常。
     */
    private void logAiFailure(
            String requestId,
            AiSettings settings,
            Throwable failure,
            boolean streaming
    ) {
        int status = AiClient.httpStatusCode(failure);
        int retryAfter = AiClient.retryAfterSeconds(failure);
        String model = settings == null ? "" : settings.model();
        LOG.warning("AI_CHAT_FAILURE request=" + requestId
                + " model=" + model
                + " status=" + status
                + " retry_after=" + retryAfter
                + " streaming=" + streaming
                + " error_type=" + (failure == null ? "unknown" : failure.getClass().getSimpleName()));
    }

    private SearchKnowledgeTool createSearchTool(
            String requestId,
            SearchLanguage language,
            int results,
            int contextChars,
            int rounds
    ) {
        TaskRuntimeReaderAdapter runtimeReader = new TaskRuntimeReaderAdapter(requestId);
        return new SearchKnowledgeTool(
                retrievalService,
                language,
                results,
                contextChars,
                1,
                rounds,
                new TaskKnowledgeStore(knowledgeRoot),
                runtimeReader,
                requestId,
                trace -> onSearchTrace(requestId, trace)
        );
    }

    private RecipeQueryTool createRecipeTool(String requestId, String conversationId, int results) {
        return new RecipeQueryTool(
                recipeQueryRequester == null
                        ? null
                        : query -> recipeQueryRequester.request(
                                requestId,
                                conversationId,
                                query
                        ),
                results,
                trace -> onRecipeTrace(requestId, trace)
        );
    }

    private void onSearchTrace(String requestId, SearchTrace trace) {
        requestTraces.computeIfAbsent(requestId, ignored -> new CopyOnWriteArrayList<>()).add(trace);
        JsonObject event = event(WorkerProtocol.TOOL_CALL, requestId);
        addTraceFields(event, trace);
        JsonArray sources = new JsonArray();
        trace.sources().forEach(source -> sources.add(WorkerPayloadCodec.source(source)));
        event.add("sources", sources);
        sendQuietly(event);

        // LangChain4j 已经完成本地工具执行后才会调用 traceSink；把结果单独
        // 发出，客户端/诊断器可以区分“工具被调用”和“工具结果已返回”。
        JsonObject result = event(WorkerProtocol.TOOL_RESULT, requestId);
        addTraceFields(result, trace);
        result.addProperty("returned_count", trace.sources().size());
        result.add("sources", sources.deepCopy());
        sendQuietly(result);
    }

    private void onRecipeTrace(String requestId, RecipeQueryTrace trace) {
        if (trace == null || trace.query() == null || trace.response() == null) {
            return;
        }
        JsonObject call = event(WorkerProtocol.TOOL_CALL, requestId);
        call.addProperty("tool", RecipeQueryTool.TOOL_NAME);
        call.addProperty("item_id", trace.query().itemId());
        call.addProperty("mode", trace.query().mode().name());
        call.addProperty("method_id", trace.query().methodId());
        call.addProperty("status", "requested");
        sendQuietly(call);

        RecipeResponse response = trace.response();
        JsonObject result = event(WorkerProtocol.TOOL_RESULT, requestId);
        result.addProperty("tool", RecipeQueryTool.TOOL_NAME);
        result.addProperty("item_id", response.itemId());
        result.addProperty("mode", response.mode().name());
        result.addProperty("status", response.status());
        result.addProperty("returned_count", response.recipes().size());
        result.addProperty("method_count", response.methods().size());
        result.addProperty("has_more", response.hasMore());
        result.add("machines", WorkerPayloadCodec.array(response.machines()));
        sendQuietly(result);
    }

    private void addTraceFields(JsonObject event, SearchTrace trace) {
        event.addProperty("tool", trace.tool());
        event.addProperty("query", trace.query());
        event.addProperty("language", trace.language());
        event.addProperty("focus", trace.focus());
        event.addProperty("round", trace.round());
        event.addProperty("status", trace.status());
        event.addProperty("has_more", trace.hasMore());
    }

    private void finish(
            String requestId,
            String conversationId,
            String answer,
            TaskSearchSummary taskSummary
    ) {
        if (cancelled(requestId)) {
            return;
        }
        String normalized = AiResponseSanitizer.sanitize(answer).strip();
        FollowUpQuestionParser.Parsed parsed = FollowUpQuestionParser.parse(normalized);
        if (parsed.markdown().isBlank() && parsed.questions().isEmpty()) {
            sendError(requestId, "AI 返回了空回答");
            return;
        }
        List<SearchTrace> traces = requestTraces.remove(requestId);
        if (traces == null) {
            traces = List.of();
        }
        if (cancelled(requestId)) {
            return;
        }
        List<SourceReference> sources = SourceCitationParser.selectSources(
                traces, parsed.markdown(), MAX_DISPLAYED_SOURCES
        );
        // SearchTrace 已通过协议发送；会话记录由 Worker 自己保留正文和引用。
        conversationStore.appendMessage(conversationId, new ChatMessage(
                MessageRole.ASSISTANT,
                parsed.markdown(),
                sources,
                parsed.questions(),
                taskSummary
        ));
        sendCompleted(requestId, parsed.markdown(), sources, parsed.questions(), conversationId, taskSummary);
    }

    private void sendCompleted(
            String requestId,
            String answer,
            List<SourceReference> sources,
            List<String> followUps,
            String conversationId
    ) {
        sendCompleted(requestId, answer, sources, followUps, conversationId, null);
    }

    private void sendCompleted(
            String requestId,
            String answer,
            List<SourceReference> sources,
            List<String> followUps,
            String conversationId,
            TaskSearchSummary taskSummary
    ) {
        if (cancelled(requestId)) {
            return;
        }
        JsonObject event = event(WorkerProtocol.COMPLETED, requestId);
        event.addProperty("answer", answer == null ? "" : answer);
        JsonArray sourceArray = new JsonArray();
        if (sources != null) {
            sources.forEach(source -> sourceArray.add(WorkerPayloadCodec.source(source)));
        }
        event.add("sources", sourceArray);
        event.add("follow_up_questions", WorkerPayloadCodec.array(followUps));
        if (taskSummary != null && taskSummary.visible()) {
            event.add("task_summary", WorkerPayloadCodec.taskSummary(taskSummary));
        }
        event.addProperty("conversation_id", conversationId);
        appendConversation(event, conversationId);
        sendQuietly(event);
    }

    private void appendConversation(JsonObject event, String conversationId) {
        ConversationStore store = conversationStore;
        ConversationRecord record = store.get(conversationId);
        if (record == null) {
            return;
        }
        JsonArray messages = new JsonArray();
        record.messages().forEach(message -> messages.add(WorkerPayloadCodec.chatMessage(message)));
        event.add("messages", messages);
        event.addProperty("active_conversation_id", store.activeId());
        event.addProperty("active_title", store.active().title());
        JsonArray summaries = new JsonArray();
        store.summaries().forEach(summary -> summaries.add(WorkerPayloadCodec.summary(summary)));
        event.add("conversations", summaries);
    }

    private void sendStatus(String requestId, String phase, String message) {
        JsonObject event = event(WorkerProtocol.STATUS, requestId);
        event.addProperty("phase", phase);
        event.addProperty("message", message);
        sendQuietly(event);
    }

    private void sendError(String requestId, String message) {
        if (cancelled(requestId)) {
            requestTraces.remove(requestId);
            return;
        }
        requestTraces.remove(requestId);
        JsonObject event = event(WorkerProtocol.ERROR, requestId);
        event.addProperty("message", message == null || message.isBlank() ? "Worker 请求失败" : message);
        appendConversation(event, conversationFor(requestId));
        sendQuietly(event);
    }

    private JsonObject event(String type, String requestId) {
        JsonObject event = WorkerProtocol.message(type, requestId);
        String conversationId = conversationFor(requestId);
        if (!conversationId.isBlank()) {
            event.addProperty("conversation_id", conversationId);
        }
        return event;
    }

    private String conversationFor(String requestId) {
        String conversationId = requestConversations.get(requestId);
        if (conversationId == null || conversationId.isBlank()) {
            return conversationStore == null ? "" : conversationStore.activeId();
        }
        return conversationId;
    }

    /** 由 WorkerServer 在一次逻辑请求结束后释放临时请求状态。 */
    void releaseRequest(String requestId) {
        requestConversations.remove(requestId);
        requestTraces.remove(requestId);
    }

    private void sendQuietly(JsonObject event) {
        String requestId = WorkerProtocol.string(event, "request_id");
        String chatRequestId = WorkerProtocol.string(event, "chat_request_id");
        if (cancelled(requestId) || cancelled(chatRequestId)) {
            return;
        }
        try {
            sink.send(event);
        } catch (IOException ignored) {
            // socket 断开由 WorkerServer 的主循环负责收尾。
        }
    }

    private boolean cancelled(String requestId) {
        return requestId != null && !requestId.isBlank() && requestCancelled.test(requestId);
    }

    private SourceReference sourceOf(SearchResult result) {
        return new SourceReference(
                result.documentId(),
                result.title().isBlank() ? result.documentId() : result.title(),
                result.sourceMod(),
                result.sourcePath()
        );
    }

    private BlockingAssistantService buildBlockingService(
            ChatModel model,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            RecipeQueryTool recipeTool,
            boolean taskQuestion
    ) {
        AtomicBoolean firstRequest = new AtomicBoolean(true);
        return AiServices.builder(BlockingAssistantService.class)
                .chatModel(model)
                .systemMessage(promptBuilder.build(
                        language, settings.intensity(), rounds,
                        settings.effectiveMaxResults(), settings.effectiveMaxContextChars()
                ))
                .chatMemoryProvider(id -> createMemory(String.valueOf(id), settings))
                .tools(searchTool, CALCULATION_TOOL, recipeTool)
                .chatRequestTransformer(request -> WorkerAiSupport.requireSearchOnFirstRequest(
                        request, firstRequest, taskQuestion,
                        io.ctyx.modpedia.ai.AiTokenBudget.answerTokens(settings.intensity()),
                        settings.apiFormat().isChatCompletions()
                                && AiClient.usesCompletionTokenParameter(settings.model())
                ))
                .maxToolCallingRoundTrips(WorkerAiSupport.toolCallingRoundTrips(rounds))
                .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地工具参数格式有误，请检查 query、expression、language 等参数后重试。"
                ))
                .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地工具执行暂时失败，请检查参数后重试。"
                ))
                .build();
    }

    private StreamingAssistantService buildStreamingService(
            StreamingChatModel model,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            RecipeQueryTool recipeTool,
            boolean taskQuestion
    ) {
        AtomicBoolean firstRequest = new AtomicBoolean(true);
        return AiServices.builder(StreamingAssistantService.class)
                .streamingChatModel(model)
                .systemMessage(promptBuilder.build(
                        language, settings.intensity(), rounds,
                        settings.effectiveMaxResults(), settings.effectiveMaxContextChars()
                ))
                .chatMemoryProvider(id -> createMemory(String.valueOf(id), settings))
                .tools(searchTool, CALCULATION_TOOL, recipeTool)
                .chatRequestTransformer(request -> WorkerAiSupport.requireSearchOnFirstRequest(
                        request, firstRequest, taskQuestion,
                        io.ctyx.modpedia.ai.AiTokenBudget.answerTokens(settings.intensity()),
                        settings.apiFormat().isChatCompletions()
                                && AiClient.usesCompletionTokenParameter(settings.model())
                ))
                .maxToolCallingRoundTrips(WorkerAiSupport.toolCallingRoundTrips(rounds))
                .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地工具参数格式有误，请检查 query、expression、language 等参数后重试。"
                ))
                .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地工具执行暂时失败，请检查参数后重试。"
                ))
                .build();
    }

    private TokenWindowChatMemory createMemory(String id, AiSettings settings) {
        TokenCountEstimator estimator = WorkerAiSupport.tokenCountEstimator(settings.model());
        return TokenWindowChatMemory.builder()
                .id(id)
                .maxTokens(WorkerAiSupport.memoryTokenBudget(settings.effectiveMaxContextChars()), estimator)
                .chatMemoryStore(memoryStore)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    private SearchLanguage parseLanguage(String value) {
        return switch (value == null ? "" : value.toLowerCase()) {
            case "en_us", "en", "english" -> SearchLanguage.EN_US;
            case "neutral" -> SearchLanguage.NEUTRAL;
            default -> SearchLanguage.ZH_CN;
        };
    }

    private static String responseText(ChatResponse response) {
        return response == null || response.aiMessage() == null || response.aiMessage().text() == null
                ? ""
                : response.aiMessage().text().strip();
    }

    private String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? failure == null ? "未知错误" : failure.getClass().getSimpleName()
                : current.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    @FunctionalInterface
    public interface RuntimeContextRequester {
        TaskRuntimeSnapshot request(String requestId, String conversationId, TaskQuery query);
    }

    @FunctionalInterface
    public interface RecipeQueryRequester {
        RecipeResponse request(String requestId, String conversationId, RecipeQuery query);
    }

    private final class TaskRuntimeReaderAdapter implements io.ctyx.modpedia.task.TaskRuntimeReader {
        private final String requestId;

        private TaskRuntimeReaderAdapter(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public io.ctyx.modpedia.task.TaskRuntimeReadResult readForQuery(TaskQuery query, String ignored) {
            TaskRuntimeSnapshot snapshot = runtimeContextRequester == null
                    ? null
                    : runtimeContextRequester.request(requestId, conversationFor(requestId), query);
            return snapshot == null
                    ? io.ctyx.modpedia.task.TaskRuntimeReadResult.unavailable("当前请求未取得任务运行时快照")
                    : io.ctyx.modpedia.task.TaskRuntimeReadResult.read(snapshot);
        }
    }

    public interface StreamingAssistantService {
        TokenStream chat(@MemoryId String conversationId, @UserMessage String prompt);
    }

    public interface BlockingAssistantService {
        String chat(@MemoryId String conversationId, @UserMessage String prompt);
    }
}
