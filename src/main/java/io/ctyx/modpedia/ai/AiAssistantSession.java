package io.ctyx.modpedia.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.ModPediaClient;
import io.ctyx.modpedia.client.AssistantSession;
import io.ctyx.modpedia.client.AssistantUiState;
import io.ctyx.modpedia.client.BuiltInGuide;
import io.ctyx.modpedia.client.ConversationSummary;
import io.ctyx.modpedia.storage.ModPediaPaths;
import io.ctyx.modpedia.client.MessageRole;
import io.ctyx.modpedia.client.SourceReference;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.search.SearchQuery;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.search.ItemQueryParser;
import io.ctyx.modpedia.task.TaskSearchSummary;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 基于 LangChain4j 的 AI 会话：工具调用、上下文窗口和历史存储由成熟模块协作完成。 */
public final class AiAssistantSession implements AssistantSession {
    private static final String PHASE_ANALYZING = "screen.modpedia.phase.analyzing";
    private static final String PHASE_SEARCHING = "screen.modpedia.phase.searching";
    private static final String PHASE_MORE_SEARCH = "screen.modpedia.phase.more_search";
    private static final String PHASE_RESULTS = "screen.modpedia.phase.results";
    private static final String PHASE_ORGANIZING = "screen.modpedia.phase.organizing";
    private static final String PHASE_STREAM_FALLBACK = "screen.modpedia.phase.stream_fallback";
    private static final String PHASE_RETRYING = "screen.modpedia.phase.retrying";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-ai-session");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_DISPLAYED_SOURCES = 5;

    private final CopyOnWriteArrayList<Consumer<AssistantUiState>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Long, CopyOnWriteArrayList<SearchTrace>> requestTraces = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> requestStartedNanos = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicLong terminalRequest = new AtomicLong();
    private final RetrievalService retrievalService;
    private final ConversationStore conversationStore;
    private final PersistentChatMemoryStore memoryStore;
    private final AiSettingsStore settingsStore;
    private final PromptBuilder promptBuilder;
    private volatile AssistantUiState state;
    private volatile String lastPrompt;
    private volatile StreamingHandle streamingHandle;

    public AiAssistantSession() {
        this(
                new RetrievalService(defaultKnowledgeRoot()),
                ConversationStore.runtime(),
                AiSettingsStore.runtime(),
                PromptBuilder.runtime()
        );
    }

    public AiAssistantSession(
            RetrievalService retrievalService,
            ConversationStore conversationStore,
            AiSettingsStore settingsStore,
            PromptBuilder promptBuilder
    ) {
        this.retrievalService = retrievalService;
        this.conversationStore = conversationStore;
        this.memoryStore = new PersistentChatMemoryStore(conversationStore);
        this.settingsStore = settingsStore;
        this.promptBuilder = promptBuilder;
        this.state = new AssistantUiState.Conversation(conversationStore.active().messages(), false);
    }

    @Override
    public AssistantUiState state() {
        return state;
    }

    @Override
    public void submit(String prompt) {
        String normalized = prompt == null ? "" : prompt.strip();
        if (normalized.isBlank() || isLoading()) {
            return;
        }
        if (BuiltInGuide.isUsageQuestion(normalized)) {
            showBuiltInGuide(BuiltInGuide.ASSISTANT_USAGE_DOCUMENT_ID);
            return;
        }
        AiSettings settings = settingsStore.load();
        String conversationId = conversationStore.activeId();
        int repairedMessages = memoryStore.repair(conversationId);
        if (repairedMessages != 0) {
            ModPedia.LOGGER.warn(
                    "AI memory repaired before request: conversation={}, removedMessages={}, reset={}",
                    conversationId,
                    Math.max(0, repairedMessages),
                    repairedMessages < 0
            );
        }
        long request = requestSequence.incrementAndGet();
        terminalRequest.set(0L);
        requestTraces.clear();
        requestStartedNanos.clear();
        requestTraces.put(request, new CopyOnWriteArrayList<>());
        requestStartedNanos.put(request, System.nanoTime());
        lastPrompt = normalized;
        ModPedia.LOGGER.info(
                "AI request started: request={}, conversation={}, mode={}, streaming={}, model={}",
                request,
                conversationId,
                settings.mode(),
                settings.streaming(),
                AiClient.effectiveModelName(settings.model())
        );
        conversationStore.appendMessage(conversationId, new io.ctyx.modpedia.client.ChatMessage(
                MessageRole.USER,
                normalized,
                List.of()
        ));
        publish(new AssistantUiState.Loading(
                conversationStore.get(conversationId).messages(),
                PHASE_ANALYZING,
                ""
        ));

        SearchLanguage language = currentLanguage();
        retrievalService.setLanguage(language);
        if (settings.mode() == AssistantMode.SEARCH_ONLY) {
            publishLoading(conversationId, PHASE_SEARCHING, "");
            startLocalSearch(request, conversationId, normalized, settings, language);
            return;
        }
        if (!settings.configured()) {
            requestTraces.remove(request);
            requestStartedNanos.remove(request);
            publish(new AssistantUiState.Error(
                    conversationStore.active().messages(),
                    "请先打开助手设置，填写 API 地址和模型名称。"
            ));
            return;
        }
        if (settings.effectiveApiKey().isBlank()) {
            requestTraces.remove(request);
            requestStartedNanos.remove(request);
            publish(new AssistantUiState.Error(
                    conversationStore.active().messages(),
                    "请先打开助手设置，填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。"
            ));
            return;
        }

        int rounds = settings.effectiveMaxRounds();
        int results = settings.effectiveMaxResults();
        int contextChars = settings.effectiveMaxContextChars();
        boolean taskQuestion = TaskQuestionClassifier.isTaskQuestion(normalized);
        SearchKnowledgeTool searchTool = createSearchTool(
                request,
                conversationId,
                language,
                results,
                contextChars,
                rounds
        );

        if (settings.streaming()) {
            startStreaming(request, conversationId, normalized, settings, language, rounds, searchTool, taskQuestion);
        } else {
            startBlocking(request, conversationId, normalized, settings, language, rounds, searchTool, taskQuestion);
        }
    }

    @Override
    public void showBuiltInGuide(String documentId) {
        if (!BuiltInGuide.isSupported(documentId) || isLoading()) {
            return;
        }
        requestSequence.incrementAndGet();
        requestTraces.clear();
        requestStartedNanos.clear();
        StreamingHandle handle = streamingHandle;
        streamingHandle = null;
        if (handle != null) {
            handle.cancel();
        }

        String conversationId = conversationStore.activeId();
        SearchLanguage language = currentLanguage();
        String markdown = BuiltInGuide.readMarkdown(documentId, retrievalService, language).orElse("");
        if (markdown.isBlank()) {
            publish(new AssistantUiState.Error(
                    conversationStore.active().messages(),
                    "ModPedia 内置说明文档未找到，请重启游戏或按 F9 重建知识库。"
            ));
            return;
        }

        lastPrompt = null;
        String prompt = BuiltInGuide.prompt();
        conversationStore.appendMessage(conversationId, new io.ctyx.modpedia.client.ChatMessage(
                MessageRole.USER,
                prompt,
                List.of()
        ));
        conversationStore.appendMessage(conversationId, new io.ctyx.modpedia.client.ChatMessage(
                MessageRole.ASSISTANT,
                markdown,
                List.of(BuiltInGuide.source())
        ));
        ModPedia.LOGGER.info("Displayed built-in guide: document={}", documentId);
        publish(new AssistantUiState.Conversation(
                conversationStore.active().messages(),
                false
        ));
    }

    private void startLocalSearch(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language
    ) {
        EXECUTOR.execute(() -> {
            SearchResponse response;
            List<ItemCatalogEntry> capturedItemContext;
            try {
                ItemQueryParser.Parsed parsed = ItemQueryParser.parse(prompt);
                capturedItemContext = retrievalService.lookupItemContext(prompt, language);
                String searchPrompt = parsed.searchableText();
                if (!capturedItemContext.isEmpty()) {
                    searchPrompt = searchPrompt + " " + capturedItemContext.stream()
                            .map(entry -> entry.itemId() + " " + entry.displayName())
                            .collect(java.util.stream.Collectors.joining(" "));
                }
                response = retrievalService.search(new SearchQuery(
                        searchPrompt,
                        settings.effectiveMaxResults(),
                        language
                ));
            } catch (Throwable throwable) {
                capturedItemContext = List.of();
                response = new SearchResponse(
                        SearchStatus.INDEX_ERROR,
                        prompt,
                        List.of(),
                        throwable.getMessage() == null
                                ? throwable.getClass().getSimpleName()
                                : throwable.getMessage()
                );
            }
            SearchResponse result = response;
            List<ItemCatalogEntry> itemContext = List.copyOf(capturedItemContext);
            try {
                Minecraft.getInstance().execute(() -> finishLocalSearch(
                        request, conversationId, prompt, language, result, itemContext
                ));
            } catch (Throwable ignored) {
                finishLocalSearch(request, conversationId, prompt, language, result, itemContext);
            }
        });
    }

    private void finishLocalSearch(
            long request,
            String conversationId,
            String prompt,
            SearchLanguage language,
            SearchResponse response,
            List<ItemCatalogEntry> itemContext
    ) {
        if (request != requestSequence.get()) {
            return;
        }
        List<SourceReference> sources = response.results().stream()
                .map(this::sourceOf)
                .toList();
        conversationStore.appendTrace(conversationId, new SearchTrace(
                prompt,
                language.code(),
                "identify",
                1,
                response.status().name(),
                false,
                sources,
                System.currentTimeMillis(),
                "search_knowledge"
        ));

        if (response.status() == SearchStatus.INDEX_NOT_READY
                || response.status() == SearchStatus.INDEX_ERROR) {
            publish(new AssistantUiState.Error(
                    conversationStore.get(conversationId) == null
                            ? List.of()
                            : conversationStore.get(conversationId).messages(),
                    response.status() == SearchStatus.INDEX_NOT_READY
                            ? "本地知识库尚未生成，请先等待启动扫描完成，或按 F9 重建后重试。"
                            : "本地知识库索引读取失败："
                            + (response.error().isBlank() ? "请按 F9 重建后重试。" : response.error())
            ));
            return;
        }

        if (response.status() == SearchStatus.READY) {
            conversationStore.appendMessage(
                    conversationId,
                    LocalSearchMessageFormatter.format(prompt, response, itemContext)
            );
        }
        if (conversationId.equals(conversationStore.activeId())) {
            publish(new AssistantUiState.Conversation(
                    conversationStore.active().messages(),
                    response.status() == SearchStatus.NO_MATCH
            ));
        }
    }

    private SourceReference sourceOf(SearchResult result) {
        String title = result.title().isBlank() ? result.documentId() : result.title();
        return new SourceReference(result.documentId(), title, result.sourceMod(), result.sourcePath());
    }

    @Override
    public void cancel() {
        requestSequence.incrementAndGet();
        requestTraces.clear();
        StreamingHandle handle = streamingHandle;
        streamingHandle = null;
        if (handle != null) {
            handle.cancel();
        }
        if (isLoading()) {
            publish(new AssistantUiState.Conversation(conversationStore.active().messages(), false));
        }
    }

    @Override
    public void retry() {
        if (lastPrompt == null || isLoading()) {
            return;
        }
        String conversationId = conversationStore.activeId();
        conversationStore.removeLastMessageIfRole(conversationId, MessageRole.USER);
        int removedMessages = memoryStore.prepareForRetry(conversationId, lastPrompt);
        ModPedia.LOGGER.info(
                "AI retry context reset: conversation={}, removedMessages={}, reset={}",
                conversationId,
                Math.max(0, removedMessages),
                removedMessages < 0
        );
        submit(lastPrompt);
    }

    @Override
    public void clear() {
        cancel();
        conversationStore.create();
        lastPrompt = null;
        publish(new AssistantUiState.Conversation(List.of(), false));
    }

    @Override
    public List<ConversationSummary> conversations() {
        return conversationStore.summaries();
    }

    @Override
    public String activeConversationId() {
        return conversationStore.activeId();
    }

    @Override
    public String activeConversationTitle() {
        return conversationStore.active().title();
    }

    @Override
    public void newConversation() {
        cancel();
        conversationStore.create();
        lastPrompt = null;
        publish(new AssistantUiState.Conversation(List.of(), false));
    }

    @Override
    public void selectConversation(String conversationId) {
        cancel();
        if (conversationStore.select(conversationId)) {
            lastPrompt = null;
            publish(new AssistantUiState.Conversation(conversationStore.active().messages(), false));
        }
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        conversationStore.rename(conversationId, title);
        publish(new AssistantUiState.Conversation(conversationStore.active().messages(), false));
    }

    @Override
    public void deleteConversation(String conversationId) {
        cancel();
        memoryStore.deleteMessages(conversationId);
        conversationStore.delete(conversationId);
        publish(new AssistantUiState.Conversation(conversationStore.active().messages(), false));
    }

    @Override
    public void addListener(Consumer<AssistantUiState> listener) {
        listeners.addIfAbsent(listener);
        listener.accept(state);
    }

    @Override
    public void removeListener(Consumer<AssistantUiState> listener) {
        listeners.remove(listener);
    }

    private SearchKnowledgeTool createSearchTool(
            long request,
            String conversationId,
            SearchLanguage language,
            int results,
            int contextChars,
            int maxRounds
    ) {
        return new SearchKnowledgeTool(
                retrievalService,
                language,
                results,
                contextChars,
                1,
                maxRounds,
                new io.ctyx.modpedia.task.TaskKnowledgeStore(retrievalService.knowledgeRoot()),
                ModPediaClient.taskAdapter(),
                Long.toString(request),
                trace -> onSearchTrace(request, conversationId, trace)
        );
    }

    private void startStreaming(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            boolean taskQuestion
    ) {
        EXECUTOR.execute(() -> {
            AiRequestLifecycle lifecycle = new AiRequestLifecycle();
            try {
                var model = AiClient.streamingModel(settings);
                StreamingAssistantService service = buildStreamingService(
                        model, settings, language, rounds, searchTool, taskQuestion
                );
                StringBuilder draft = new StringBuilder();
                AtomicBoolean firstTextLogged = new AtomicBoolean();
                TokenStream stream = service.chat(conversationId, prompt)
                        .onPartialResponseWithContext((PartialResponse partial, PartialResponseContext context) -> {
                            streamingHandle = context.streamingHandle();
                            if (request != requestSequence.get()) {
                                return;
                            }
                            if (partial != null && partial.text() != null) {
                                if (!partial.text().isBlank() && firstTextLogged.compareAndSet(false, true)) {
                                    ModPedia.LOGGER.info(
                                            "AI first text received: request={}, elapsedMs={}",
                                            request,
                                            elapsedMillis(requestStartedNanos.get(request))
                                    );
                                }
                                draft.append(partial.text());
                                publishLoading(conversationId, PHASE_ORGANIZING, draft.toString());
                            }
                        })
                        .beforeToolExecution(ignored -> publishLoading(conversationId, PHASE_SEARCHING, draft.toString()))
                        .onToolExecuted(ignored -> publishLoading(conversationId, PHASE_RESULTS, draft.toString()))
                        .onCompleteResponse(response -> {
                            if (request != requestSequence.get() || !lifecycle.isOpen()) {
                                return;
                            }
                            String answer = draft.toString();
                            if (answer.isBlank()) {
                                answer = responseText(response);
                            }
                            if (answer.isBlank()) {
                                fallbackToBlocking(
                                        request,
                                        conversationId,
                                        prompt,
                                        settings,
                                        language,
                                        rounds,
                                        searchTool,
                                        new IllegalStateException("流式响应完成但没有文本内容"),
                                        lifecycle,
                                        taskQuestion
                                );
                            } else if (lifecycle.complete()) {
                                finish(request, conversationId, answer, searchTool.taskSummary());
                            }
                        })
                        .onError(error -> {
                            fallbackToBlocking(
                                    request,
                                    conversationId,
                                    prompt,
                                    settings,
                                    language,
                                    rounds,
                                    searchTool,
                                    error,
                                    lifecycle,
                                    taskQuestion
                            );
                        });
                // start() 之后由 partial-response 回调填充 StreamingHandle；不要在这里
                // 再清空，否则用户在首个 token 到来前点击取消时无法中止请求。
                stream.start();
            } catch (Throwable throwable) {
                fallbackToBlocking(
                        request,
                        conversationId,
                        prompt,
                        settings,
                        language,
                        rounds,
                        searchTool,
                        throwable,
                        lifecycle,
                        taskQuestion
                );
            }
        });
    }

    private void fallbackToBlocking(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            Throwable streamingError,
            AiRequestLifecycle lifecycle,
            boolean taskQuestion
    ) {
        if (request != requestSequence.get() || !lifecycle.beginFallback()) {
            return;
        }
        AiSettings effectiveSettings = fallbackSettings(settings, streamingError);
        try {
            publishLoading(conversationId, PHASE_STREAM_FALLBACK, "");
            int removedMessages = memoryStore.prepareForRetry(conversationId, prompt);
            SearchKnowledgeTool freshSearchTool = createSearchTool(
                    request,
                    conversationId,
                    language,
                    settings.effectiveMaxResults(),
                    settings.effectiveMaxContextChars(),
                    rounds
            );
            ModPedia.LOGGER.warn(
                    "Streaming request fallback: conversation={}, removedMessages={}, reset={}",
                    conversationId,
                    Math.max(0, removedMessages),
                    removedMessages < 0
            );
            BlockingAssistantService service = buildBlockingService(
                    AiClient.blockingModel(effectiveSettings),
                    effectiveSettings,
                    language,
                    rounds,
                    freshSearchTool,
                    taskQuestion
            );
            String answer = service.chat(conversationId, prompt);
            if (lifecycle.completeFallback()) {
                finish(request, conversationId, answer, freshSearchTool.taskSummary());
            }
        } catch (Throwable fallbackError) {
            if (!retryBlockingOnce(
                    request,
                    conversationId,
                    prompt,
                    effectiveSettings,
                    language,
                    rounds,
                    new AtomicBoolean(),
                    fallbackError == null ? streamingError : fallbackError,
                    taskQuestion
            )) {
                fail(request, conversationId, fallbackError == null ? streamingError : fallbackError);
            }
        }
    }

    private void startBlocking(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            boolean taskQuestion
    ) {
        EXECUTOR.execute(() -> {
            AtomicBoolean retryStarted = new AtomicBoolean();
            try {
                var model = AiClient.blockingModel(settings);
                BlockingAssistantService service = buildBlockingService(
                        model, settings, language, rounds, searchTool, taskQuestion
                );
                publishLoading(conversationId, PHASE_ORGANIZING, "");
                String answer = service.chat(conversationId, prompt);
                finish(request, conversationId, answer, searchTool.taskSummary());
            } catch (Throwable throwable) {
                if (retryBlockingOnce(
                        request,
                        conversationId,
                        prompt,
                        settings,
                        language,
                        rounds,
                        retryStarted,
                        throwable,
                        taskQuestion
                )) {
                    return;
                }
                if (!retryBlockingWithFallback(
                        request,
                        conversationId,
                        prompt,
                        settings,
                        language,
                        rounds,
                        searchTool,
                        throwable,
                        taskQuestion
                )) {
                    fail(request, conversationId, throwable);
                }
            }
        });
    }

    /** 对网关临时失败或孤立工具调用自动重试一次，避免玩家手动重复点击。 */
    private boolean retryBlockingOnce(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            AtomicBoolean retryStarted,
            Throwable firstFailure,
            boolean taskQuestion
    ) {
        if (!AiClient.isRetryableFailure(firstFailure)
                || !retryStarted.compareAndSet(false, true)
                || request != requestSequence.get()) {
            return false;
        }
        try {
            publishLoading(conversationId, PHASE_RETRYING, "");
            Thread.sleep(700L);
            int removedMessages = memoryStore.prepareForRetry(conversationId, prompt);
            SearchKnowledgeTool freshSearchTool = createSearchTool(
                    request,
                    conversationId,
                    language,
                    settings.effectiveMaxResults(),
                    settings.effectiveMaxContextChars(),
                    rounds
            );
            ModPedia.LOGGER.warn(
                    "AI automatic retry: conversation={}, removedMessages={}, reset={}, reason={}",
                    conversationId,
                    Math.max(0, removedMessages),
                    removedMessages < 0,
                    sanitize(AiClient.friendlyError(firstFailure, settings.effectiveApiKey()))
            );
            BlockingAssistantService service = buildBlockingService(
                    AiClient.blockingModel(settings),
                    settings,
                    language,
                    rounds,
                    freshSearchTool,
                    taskQuestion
            );
            finish(request, conversationId, service.chat(conversationId, prompt), freshSearchTool.taskSummary());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(request, conversationId, interrupted);
        } catch (Throwable retryFailure) {
            fail(request, conversationId, retryFailure);
        }
        return true;
    }

    private boolean retryBlockingWithFallback(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            Throwable failure,
            boolean taskQuestion
    ) {
        String fallbackModel = AiClient.fallbackModelName(settings.model());
        if (!AiClient.isGrokOAuthUnavailable(failure) || fallbackModel.isBlank()) {
            return false;
        }
        AiSettings effectiveSettings = settings.withModel(fallbackModel);
        ModPedia.LOGGER.warn(
                "AI model fallback: from={}, to={}, reason=unavailable Grok OAuth account",
                settings.model(),
                fallbackModel
        );
        try {
            publishLoading(conversationId, PHASE_STREAM_FALLBACK, "");
            int removedMessages = memoryStore.prepareForRetry(conversationId, prompt);
            SearchKnowledgeTool freshSearchTool = createSearchTool(
                    request,
                    conversationId,
                    language,
                    effectiveSettings.effectiveMaxResults(),
                    effectiveSettings.effectiveMaxContextChars(),
                    rounds
            );
            ModPedia.LOGGER.warn(
                    "Blocking model fallback context reset: conversation={}, removedMessages={}, reset={}",
                    conversationId,
                    Math.max(0, removedMessages),
                    removedMessages < 0
            );
            BlockingAssistantService service = buildBlockingService(
                    AiClient.blockingModel(effectiveSettings),
                    effectiveSettings,
                    language,
                    rounds,
                    freshSearchTool,
                    taskQuestion
            );
            finish(request, conversationId, service.chat(conversationId, prompt), freshSearchTool.taskSummary());
        } catch (Throwable fallbackError) {
            fail(request, conversationId, fallbackError);
        }
        return true;
    }

    private AiSettings fallbackSettings(AiSettings settings, Throwable failure) {
        String fallbackModel = AiClient.fallbackModelName(settings.model());
        if (!AiClient.isGrokOAuthUnavailable(failure) || fallbackModel.isBlank()) {
            return settings;
        }
        ModPedia.LOGGER.warn(
                "AI model fallback: from={}, to={}, reason=unavailable Grok OAuth account",
                settings.model(),
                fallbackModel
        );
        return settings.withModel(fallbackModel);
    }

    private StreamingAssistantService buildStreamingService(
            OpenAiStreamingChatModel model,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            boolean taskQuestion
    ) {
        AtomicBoolean firstRequest = new AtomicBoolean(true);
        return AiServices.builder(StreamingAssistantService.class)
                .streamingChatModel(model)
                .systemMessage(promptBuilder.build(
                        language,
                        settings.intensity(),
                        rounds,
                        settings.effectiveMaxResults(),
                        settings.effectiveMaxContextChars()
                ))
                .chatMemoryProvider(id -> createMemory(String.valueOf(id), settings))
                .tools(searchTool)
                // 提示词只能“建议”工具调用；首次请求强制 REQUIRED，避免模型直接
                // 编造答案或把“如何使用”误答成整合包内另一个模组。工具结果回传后的
                // 请求恢复 AUTO，模型才能根据证据完整度决定是否继续补搜或直接回答。
                .chatRequestTransformer(request -> requireSearchOnFirstRequest(
                        request, firstRequest, taskQuestion
                ))
                .maxToolCallingRoundTrips(toolCallingRoundTrips(rounds))
                .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "搜索工具参数格式有误，请改写 query、language、limit、focus 后重试。"
                ))
                .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地知识库搜索暂时失败，请换一个查询词继续搜索。"
                ))
                .build();
    }

    private BlockingAssistantService buildBlockingService(
            OpenAiChatModel model,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool,
            boolean taskQuestion
    ) {
        AtomicBoolean firstRequest = new AtomicBoolean(true);
        return AiServices.builder(BlockingAssistantService.class)
                .chatModel(model)
                .systemMessage(promptBuilder.build(
                        language,
                        settings.intensity(),
                        rounds,
                        settings.effectiveMaxResults(),
                        settings.effectiveMaxContextChars()
                ))
                .chatMemoryProvider(id -> createMemory(String.valueOf(id), settings))
                .tools(searchTool)
                .chatRequestTransformer(request -> requireSearchOnFirstRequest(
                        request, firstRequest, taskQuestion
                ))
                .maxToolCallingRoundTrips(toolCallingRoundTrips(rounds))
                .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "搜索工具参数格式有误，请改写 query、language、limit、focus 后重试。"
                ))
                .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地知识库搜索暂时失败，请换一个查询词继续搜索。"
                ))
                .build();
    }

    private TokenWindowChatMemory createMemory(String id, AiSettings settings) {
        // maxContextChars 是搜索结果的字符预算，不是 token 预算。之前直接除以 4，
        // 对中文 Markdown（约 2 个字符/token）会把完整工具结果裁掉；LangChain4j
        // 随后只能把 system 和最终 AI 消息留在窗口里，模型看不到搜索证据，就会退回欢迎语。
        int maxTokens = memoryTokenBudget(settings.effectiveMaxContextChars());
        TokenCountEstimator estimator;
        try {
            estimator = new OpenAiTokenCountEstimator(settings.model());
        } catch (RuntimeException ignored) {
            estimator = new ApproximateTokenCountEstimator();
        }
        return TokenWindowChatMemory.builder()
                .id(id)
                .maxTokens(maxTokens, estimator)
                .chatMemoryStore(memoryStore)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    static int memoryTokenBudget(int contextChars) {
        int normalized = Math.max(4_000, Math.min(64_000, contextChars));
        // contextChars 是 Markdown 字符预算，不是 token 数。中文通常约 2 个字符/token，
        // 英文则更省；直接 1:1 映射会把 64,000 字符扩大成不必要的 64,000 token，
        // 既增加网关延迟，也可能触发模型上下文上限。预留少量系统提示词和消息结构开销，
        // 实际保留哪些消息仍由 LangChain4j 的 estimator 决定。
        return Math.max(8_000, (normalized + 1) / 2 + 2_048);
    }

    /**
     * 搜索轮数和 LangChain4j 的 round-trip 不是同一个单位：一次工具搜索至少需要
     * “模型请求工具 → 工具结果回传 → 模型生成回答”至少需要两次往返；兼容网关
     * 还可能在搜索预算已耗尽后再次确认工具结果。工具本身会硬限制真正的搜索轮数，
     * 这里额外留出两次“停止搜索并整理回答”的模型往返，避免把正常的预算收敛误判为链路错误。
     */
    static int toolCallingRoundTrips(int searchRounds) {
        return Math.max(4, Math.min(12, Math.max(1, searchRounds) + 3));
    }

    private void onSearchTrace(long request, String conversationId, SearchTrace trace) {
        if (request != requestSequence.get()) {
            return;
        }
        requestTraces.computeIfAbsent(request, ignored -> new CopyOnWriteArrayList<>()).add(trace);
        conversationStore.appendTrace(conversationId, trace);
        ModPedia.LOGGER.info(
                "AI knowledge search: request={}, round={}, elapsedMs={}, query={}, language={}, focus={}, status={}, sources={}, hasMore={}",
                request,
                trace.round(),
                elapsedMillis(requestStartedNanos.get(request)),
                trace.query(),
                trace.language(),
                trace.focus(),
                trace.status(),
                trace.sources().size(),
                trace.hasMore()
        );
        publishLoading(conversationId, PHASE_MORE_SEARCH, "");
    }

    private void publishLoading(String conversationId, String phase, String draft) {
        if (!conversationId.equals(conversationStore.activeId())) {
            return;
        }
        publish(new AssistantUiState.Loading(
                conversationStore.active().messages(),
                phase,
                AiResponseSanitizer.sanitize(draft)
        ));
    }

    private void finish(
            long request,
            String conversationId,
            String answer,
            TaskSearchSummary taskSummary
    ) {
        if (request != requestSequence.get()) {
            return;
        }
        String normalized = AiResponseSanitizer.sanitize(answer).strip();
        FollowUpQuestionParser.Parsed parsed = FollowUpQuestionParser.parse(normalized);
        if (parsed.markdown().isBlank() && parsed.questions().isEmpty()) {
            fail(request, conversationId, new IllegalStateException("AI 返回了空回答"));
            return;
        }
        if (!terminalRequest.compareAndSet(0L, request)) {
            return;
        }
        List<SearchTrace> traces = requestTraces.remove(request);
        List<SourceReference> sources = selectCitedSources(
                traces == null ? List.of() : traces,
                parsed.markdown()
        );
        // 保留来源协议在回答中的原始位置。MarkdownRenderer 会逐行解析它，并在对应
        // 句子后生成可点击的来源标注；这里不能先全局删除再把来源统一堆到气泡底部。
        String displayMarkdown = parsed.markdown();
        String renderableMarkdown = SourceCitationParser.removeCitationMarkup(displayMarkdown);
        if (renderableMarkdown.isBlank() && !parsed.questions().isEmpty()) {
            displayMarkdown = "已整理当前问题的下一步检索方向。";
        }
        if (SourceCitationParser.removeCitationMarkup(displayMarkdown).isBlank() && !sources.isEmpty()) {
            displayMarkdown = "已根据本地手册整理，详细依据已标注在相关正文后。";
        }
        ModPedia.LOGGER.info(
                "AI response completed: request={}, conversation={}, totalMs={}, chars={}, searchRounds={}, sources={}",
                request,
                conversationId,
                elapsedMillis(requestStartedNanos.remove(request)),
                normalized.length(),
                traces == null ? 0 : traces.size(),
                sources.size()
        );
        conversationStore.appendMessage(conversationId, new io.ctyx.modpedia.client.ChatMessage(
                MessageRole.ASSISTANT,
                displayMarkdown,
                sources,
                parsed.questions(),
                taskSummary
        ));
        streamingHandle = null;
        if (conversationId.equals(conversationStore.activeId())) {
            publish(new AssistantUiState.Conversation(
                    conversationStore.active().messages(),
                    false
            ));
        }
    }

    static List<SourceReference> selectCitedSources(List<SearchTrace> traces, String answer) {
        List<SourceReference> cited = SourceCitationParser.selectSources(
                traces,
                answer,
                MAX_DISPLAYED_SOURCES
        );
        // 模型没有明确引用时不再把所有候选结果自动变成跳转链接；否则相关页面会
        // 混在一起，用户也无法判断哪些内容真正支撑了回答。
        return cited;
    }

    private void fail(long request, String conversationId, Throwable throwable) {
        if (request != requestSequence.get()) {
            return;
        }
        if (!terminalRequest.compareAndSet(0L, request)) {
            return;
        }
        requestTraces.remove(request);
        Long startedNanos = requestStartedNanos.remove(request);
        streamingHandle = null;
        String reason = throwable == null
                ? "请检查 AI 设置、网络连接和模型名称。"
                : AiClient.friendlyError(throwable, settingsStore.load().effectiveApiKey());
        ModPedia.LOGGER.warn(
                "AI request failed: request={}, totalMs={}, type={}, reason={}",
                request,
                elapsedMillis(startedNanos),
                throwable == null ? "unknown" : throwable.getClass().getName(),
                sanitize(reason)
        );
        publish(new AssistantUiState.Error(
                conversationStore.get(conversationId) == null
                        ? List.of()
                        : conversationStore.get(conversationId).messages(),
                "AI 请求失败：" + sanitize(reason)
        ));
    }

    static ChatRequest requireSearchOnFirstRequest(ChatRequest request, AtomicBoolean firstRequest) {
        return AiToolRouter.requireSearchOnFirstRequest(request, firstRequest, false);
    }

    static ChatRequest requireSearchOnFirstRequest(
            ChatRequest request,
            AtomicBoolean firstRequest,
            boolean taskQuestion
    ) {
        return AiToolRouter.requireSearchOnFirstRequest(request, firstRequest, taskQuestion);
    }

    private String sanitize(String message) {
        String key = settingsStore.load().effectiveApiKey();
        return key.isBlank() ? message : message.replace(key, "[已隐藏密钥]");
    }

    private SearchLanguage currentLanguage() {
        try {
            return SearchLanguage.fromMinecraft(Minecraft.getInstance().options.languageCode);
        } catch (Throwable ignored) {
            return SearchLanguage.ZH_CN;
        }
    }

    private void publish(AssistantUiState next) {
        state = next;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.isSameThread()) {
                notifyListeners(next);
            } else {
                minecraft.execute(() -> notifyListeners(next));
            }
        } catch (Throwable ignored) {
            notifyListeners(next);
        }
    }

    private void notifyListeners(AssistantUiState next) {
        listeners.forEach(listener -> listener.accept(next));
    }

    private static String responseText(ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            return "";
        }
        return response.aiMessage().text().strip();
    }

    private static long elapsedMillis(Long startedNanos) {
        if (startedNanos == null) {
            return -1L;
        }
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static java.nio.file.Path defaultKnowledgeRoot() {
        ModPediaPaths paths = ModPediaPaths.forConfig(FMLPaths.CONFIGDIR.get());
        paths.migrateLegacyQuietly();
        return paths.runtimeKnowledgeRoot();
    }

    public interface StreamingAssistantService {
        TokenStream chat(@MemoryId String conversationId, @UserMessage String prompt);
    }

    public interface BlockingAssistantService {
        String chat(@MemoryId String conversationId, @UserMessage String prompt);
    }

    private static final class ApproximateTokenCountEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInText(String text) {
            return Math.max(1, text == null ? 0 : text.codePointCount(0, text.length()) / 2);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return estimateTokenCountInText(message == null ? "" : message.toString());
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 3;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }
}
