package io.ctyx.modpedia.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
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
import io.ctyx.modpedia.client.AssistantSession;
import io.ctyx.modpedia.client.AssistantUiState;
import io.ctyx.modpedia.client.ConversationSummary;
import io.ctyx.modpedia.client.MessageRole;
import io.ctyx.modpedia.client.SourceReference;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.search.SearchQuery;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于 LangChain4j 的 AI 会话：工具调用、上下文窗口和历史存储由成熟模块协作完成。 */
public final class AiAssistantSession implements AssistantSession {
    private static final String PHASE_ANALYZING = "screen.modpedia.phase.analyzing";
    private static final String PHASE_SEARCHING = "screen.modpedia.phase.searching";
    private static final String PHASE_MORE_SEARCH = "screen.modpedia.phase.more_search";
    private static final String PHASE_RESULTS = "screen.modpedia.phase.results";
    private static final String PHASE_ORGANIZING = "screen.modpedia.phase.organizing";
    private static final String PHASE_STREAM_FALLBACK = "screen.modpedia.phase.stream_fallback";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-ai-session");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern SOURCE_PATTERN = Pattern.compile("\\[来源\\s*:\\s*([^\\]]+)]");

    private final CopyOnWriteArrayList<Consumer<AssistantUiState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final RetrievalService retrievalService;
    private final ConversationStore conversationStore;
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
        AiSettings settings = settingsStore.load();
        String conversationId = conversationStore.activeId();
        long request = requestSequence.incrementAndGet();
        lastPrompt = normalized;
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
            publish(new AssistantUiState.Error(
                    conversationStore.active().messages(),
                    "请先打开助手设置，填写 API 地址和模型名称。"
            ));
            return;
        }

        int rounds = settings.effectiveMaxRounds();
        int results = settings.effectiveMaxResults();
        int contextChars = settings.effectiveMaxContextChars();
        SearchKnowledgeTool searchTool = new SearchKnowledgeTool(
                retrievalService,
                language,
                results,
                contextChars,
                1,
                trace -> onSearchTrace(request, conversationId, trace)
        );

        if (settings.streaming()) {
            startStreaming(request, conversationId, normalized, settings, language, rounds, searchTool);
        } else {
            startBlocking(request, conversationId, normalized, settings, language, rounds, searchTool);
        }
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
            try {
                response = retrievalService.search(new SearchQuery(
                        prompt,
                        settings.effectiveMaxResults(),
                        language
                ));
            } catch (Throwable throwable) {
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
            try {
                Minecraft.getInstance().execute(() -> finishLocalSearch(
                        request, conversationId, prompt, language, result
                ));
            } catch (Throwable ignored) {
                finishLocalSearch(request, conversationId, prompt, language, result);
            }
        });
    }

    private void finishLocalSearch(
            long request,
            String conversationId,
            String prompt,
            SearchLanguage language,
            SearchResponse response
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
                System.currentTimeMillis()
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
                    LocalSearchMessageFormatter.format(prompt, response)
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
        conversationStore.removeLastMessageIfRole(conversationStore.activeId(), MessageRole.USER);
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

    private void startStreaming(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool
    ) {
        EXECUTOR.execute(() -> {
            AtomicBoolean fallbackStarted = new AtomicBoolean();
            try {
                var model = AiClient.streamingModel(settings);
                StreamingAssistantService service = buildStreamingService(
                        model, settings, language, rounds, searchTool
                );
                StringBuilder draft = new StringBuilder();
                TokenStream stream = service.chat(conversationId, prompt)
                        .onPartialResponseWithContext((PartialResponse partial, PartialResponseContext context) -> {
                            streamingHandle = context.streamingHandle();
                            if (request != requestSequence.get()) {
                                return;
                            }
                            if (partial != null && partial.text() != null) {
                                draft.append(partial.text());
                                publishLoading(conversationId, PHASE_ORGANIZING, draft.toString());
                            }
                        })
                        .beforeToolExecution(ignored -> publishLoading(conversationId, PHASE_SEARCHING, draft.toString()))
                        .onToolExecuted(ignored -> publishLoading(conversationId, PHASE_RESULTS, draft.toString()))
                        .onCompleteResponse(response -> finish(request, conversationId, draft.toString()))
                        .onError(error -> fallbackToBlocking(
                                request,
                                conversationId,
                                prompt,
                                settings,
                                language,
                                rounds,
                                searchTool,
                                error,
                                fallbackStarted
                        ));
                streamingHandle = null;
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
                        fallbackStarted
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
            AtomicBoolean fallbackStarted
    ) {
        if (request != requestSequence.get() || !fallbackStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            publishLoading(conversationId, PHASE_STREAM_FALLBACK, "");
            BlockingAssistantService service = buildBlockingService(
                    AiClient.blockingModel(settings),
                    settings,
                    language,
                    rounds,
                    searchTool
            );
            String answer = service.chat(conversationId, prompt);
            finish(request, conversationId, answer);
        } catch (Throwable fallbackError) {
            fail(request, conversationId, fallbackError == null ? streamingError : fallbackError);
        }
    }

    private void startBlocking(
            long request,
            String conversationId,
            String prompt,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool
    ) {
        EXECUTOR.execute(() -> {
            try {
                var model = AiClient.blockingModel(settings);
                BlockingAssistantService service = buildBlockingService(
                        model, settings, language, rounds, searchTool
                );
                publishLoading(conversationId, PHASE_ORGANIZING, "");
                String answer = service.chat(conversationId, prompt);
                finish(request, conversationId, answer);
            } catch (Throwable throwable) {
                fail(request, conversationId, throwable);
            }
        });
    }

    private StreamingAssistantService buildStreamingService(
            OpenAiStreamingChatModel model,
            AiSettings settings,
            SearchLanguage language,
            int rounds,
            SearchKnowledgeTool searchTool
    ) {
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
                .maxToolCallingRoundTrips(rounds)
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
            SearchKnowledgeTool searchTool
    ) {
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
                .maxToolCallingRoundTrips(rounds)
                .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "搜索工具参数格式有误，请改写 query、language、limit、focus 后重试。"
                ))
                .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                        "本地知识库搜索暂时失败，请换一个查询词继续搜索。"
                ))
                .build();
    }

    private TokenWindowChatMemory createMemory(String id, AiSettings settings) {
        int maxTokens = Math.max(1_000, settings.effectiveMaxContextChars() / 4);
        TokenCountEstimator estimator;
        try {
            estimator = new OpenAiTokenCountEstimator(settings.model());
        } catch (RuntimeException ignored) {
            estimator = new ApproximateTokenCountEstimator();
        }
        return TokenWindowChatMemory.builder()
                .id(id)
                .maxTokens(maxTokens, estimator)
                .chatMemoryStore(new PersistentChatMemoryStore(conversationStore))
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    private void onSearchTrace(long request, String conversationId, SearchTrace trace) {
        if (request != requestSequence.get()) {
            return;
        }
        conversationStore.appendTrace(conversationId, trace);
        publishLoading(conversationId, PHASE_MORE_SEARCH, "");
    }

    private void publishLoading(String conversationId, String phase, String draft) {
        if (!conversationId.equals(conversationStore.activeId())) {
            return;
        }
        publish(new AssistantUiState.Loading(
                conversationStore.active().messages(),
                phase,
                draft
        ));
    }

    private void finish(long request, String conversationId, String answer) {
        if (request != requestSequence.get()) {
            return;
        }
        String normalized = answer == null ? "" : answer.strip();
        if (normalized.isBlank()) {
            fail(request, conversationId, new IllegalStateException("AI 返回了空回答"));
            return;
        }
        List<SourceReference> sources = collectSources(conversationId, normalized);
        conversationStore.appendMessage(conversationId, new io.ctyx.modpedia.client.ChatMessage(
                MessageRole.ASSISTANT,
                normalized,
                sources
        ));
        streamingHandle = null;
        if (conversationId.equals(conversationStore.activeId())) {
            publish(new AssistantUiState.Conversation(
                    conversationStore.active().messages(),
                    false
            ));
        }
    }

    private List<SourceReference> collectSources(String conversationId, String answer) {
        ConversationRecord record = conversationStore.get(conversationId);
        if (record == null) {
            return List.of();
        }
        Map<String, SourceReference> all = new LinkedHashMap<>();
        for (SearchTrace trace : record.searchTraces()) {
            for (SourceReference source : trace.sources()) {
                all.putIfAbsent(source.documentId(), source);
            }
        }
        Map<String, SourceReference> cited = new LinkedHashMap<>();
        Matcher matcher = SOURCE_PATTERN.matcher(answer);
        while (matcher.find()) {
            String id = matcher.group(1).strip();
            SourceReference source = all.get(id);
            if (source != null) {
                cited.putIfAbsent(id, source);
            }
        }
        return cited.isEmpty() ? List.copyOf(all.values()) : List.copyOf(cited.values());
    }

    private void fail(long request, String conversationId, Throwable throwable) {
        if (request != requestSequence.get()) {
            return;
        }
        streamingHandle = null;
        String reason = throwable == null || throwable.getMessage() == null
                ? "请检查 AI 设置、网络连接和模型名称。"
                : throwable.getMessage();
        publish(new AssistantUiState.Error(
                conversationStore.get(conversationId) == null
                        ? List.of()
                        : conversationStore.get(conversationId).messages(),
                "AI 请求失败：" + sanitize(reason)
        ));
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

    private static java.nio.file.Path defaultKnowledgeRoot() {
        return FMLPaths.CONFIGDIR.get().resolve("modpedia").resolve("knowledge");
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
