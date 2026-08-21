package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.api.MessageRole;
import io.ctyx.modpedia.api.SourceReference;
import io.ctyx.modpedia.knowledge.BuiltInGuide;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import io.ctyx.modpedia.search.SearchLanguage;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import io.ctyx.modpedia.storage.ModPediaPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 阶段四的确定性模拟会话：用本地规则搜索代替 AI，方便在游戏内联调 UI。 */
public final class MockAssistantSession implements AssistantSession {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-mock-assistant");
        thread.setDaemon(true);
        return thread;
    });

    private final CopyOnWriteArrayList<Consumer<AssistantUiState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final RetrievalService retrievalService;
    private volatile AssistantUiState state = new AssistantUiState.Conversation(List.of(), false);
    private volatile String lastPrompt;

    public MockAssistantSession() {
        this(new RetrievalService(defaultKnowledgeRoot()));
    }

    MockAssistantSession(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public AssistantUiState state() {
        return state;
    }

    @Override
    public void submit(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        if (normalized.isEmpty() || isLoading()) {
            return;
        }

        lastPrompt = normalized;
        long request = requestSequence.incrementAndGet();
        List<ChatMessage> messages = new ArrayList<>(state.messages());
        messages.add(new ChatMessage(MessageRole.USER, normalized, List.of()));
        publish(new AssistantUiState.Loading(messages));
        SearchLanguage language = SearchLanguage.fromMinecraft(Minecraft.getInstance().options.languageCode);
        EXECUTOR.schedule(() -> searchAndFinish(request, normalized, language), 450, TimeUnit.MILLISECONDS);
    }

    @Override
    public void showBuiltInGuide(String documentId) {
        if (!BuiltInGuide.isSupported(documentId) || isLoading()) {
            return;
        }
        String markdown = BuiltInGuide.readMarkdown(documentId, retrievalService, SearchLanguage.ZH_CN)
                .orElse("");
        if (markdown.isBlank()) {
            publish(new AssistantUiState.Error(
                    state.messages(),
                    "ModPedia 内置说明文档未找到，请按 F9 重建知识库后重试。"
            ));
            return;
        }

        lastPrompt = null;
        List<ChatMessage> messages = new ArrayList<>(state.messages());
        messages.add(new ChatMessage(MessageRole.USER, BuiltInGuide.prompt(), List.of()));
        messages.add(new ChatMessage(MessageRole.ASSISTANT, markdown, List.of(BuiltInGuide.source())));
        publish(new AssistantUiState.Conversation(messages, false));
    }

    @Override
    public void cancel() {
        requestSequence.incrementAndGet();
        if (isLoading()) {
            publish(new AssistantUiState.Conversation(state.messages(), false));
        }
    }

    @Override
    public void retry() {
        if (lastPrompt != null && !isLoading()) {
            if (state instanceof AssistantUiState.Error) {
                List<ChatMessage> messages = new ArrayList<>(state.messages());
                if (!messages.isEmpty() && messages.get(messages.size() - 1).role() == MessageRole.USER) {
                    messages.remove(messages.size() - 1);
                    state = new AssistantUiState.Conversation(messages, false);
                }
            }
            submit(lastPrompt);
        }
    }

    @Override
    public void clear() {
        requestSequence.incrementAndGet();
        lastPrompt = null;
        publish(new AssistantUiState.Conversation(List.of(), false));
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

    private void searchAndFinish(long request, String prompt, SearchLanguage language) {
        if (request != requestSequence.get()) {
            return;
        }

        SearchResponse response;
        try {
            response = retrievalService.search(new io.ctyx.modpedia.search.SearchQuery(
                    prompt,
                    io.ctyx.modpedia.search.SearchQuery.DEFAULT_LIMIT,
                    language
            ));
        } catch (RuntimeException exception) {
            response = new SearchResponse(
                    SearchStatus.INDEX_ERROR,
                    prompt,
                    List.of(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            );
        }
        SearchResponse result = response;
        Minecraft.getInstance().execute(() -> finish(request, prompt, result));
    }

    private void finish(long request, String prompt, SearchResponse response) {
        if (request != requestSequence.get()) {
            return;
        }

        List<ChatMessage> messages = new ArrayList<>(state.messages());
        String lower = prompt.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || prompt.contains("错误")) {
            publish(new AssistantUiState.Error(messages, "模拟请求错误：可以点击重试继续测试错误状态。"));
            return;
        }
        if (lower.contains("unknown") || prompt.contains("不存在") || prompt.contains("没有这个")) {
            publish(new AssistantUiState.Conversation(messages, true));
            return;
        }

        switch (response.status()) {
            case READY -> {
                messages.add(toAssistantMessage(prompt, response));
                publish(new AssistantUiState.Conversation(messages, false));
            }
            case NO_MATCH, EMPTY_QUERY -> publish(new AssistantUiState.Conversation(messages, true));
            case INDEX_NOT_READY -> publish(new AssistantUiState.Error(
                    messages,
                    "本地知识库尚未生成，请先等待启动扫描完成，或按 F9 重建后重试。"
            ));
            case INDEX_ERROR -> publish(new AssistantUiState.Error(
                    messages,
                    "本地知识库索引读取失败：" + (response.error().isBlank() ? "请按 F9 重建后重试。" : response.error())
            ));
        }
    }

    private ChatMessage toAssistantMessage(String prompt, SearchResponse response) {
        StringBuilder markdown = new StringBuilder()
                .append("本地规则搜索命中 ")
                .append(response.results().size())
                .append(" 条结果。\n\n")
                .append("查询：**")
                .append(prompt)
                .append("**\n\n");
        List<SourceReference> sources = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            SearchResult result = response.results().get(index);
            String title = result.title().isBlank() ? result.documentId() : result.title();
            markdown.append("### ").append(index + 1).append(". ").append(title)
                    .append(" [来源: ").append(result.documentId())
                    .append(" | 标注: ").append(citationLabel(title)).append("]\n");
            if (!result.headingPath().isBlank()) {
                markdown.append("位置：").append(result.headingPath()).append("\n");
            }
            markdown.append("匹配分：").append(result.score());
            if (!result.matchedTerms().isEmpty()) {
                markdown.append(" · 命中：").append(String.join(", ", result.matchedTerms()));
            }
            markdown.append("\n\n").append(result.segmentMarkdown()).append("\n\n");
            sources.add(new SourceReference(
                    result.documentId(),
                    title,
                    result.sourceMod(),
                    result.sourcePath()
            ));
        }
        return new ChatMessage(
                MessageRole.ASSISTANT,
                markdown.toString().trim(),
                sources,
                List.of(
                        "还要查看“" + prompt + "”的前置条件吗？",
                        "还要查看“" + prompt + "”的配方或材料吗？",
                        "如果使用失败，如何排查“" + prompt + "”？"
                )
        );
    }

    private static String citationLabel(String value) {
        return (value == null ? "" : value.replace("\n", " ").replace("\r", " ").strip())
                .replace("]", "）")
                .replace("|", "·");
    }

    private static Path defaultKnowledgeRoot() {
        ModPediaPaths paths = ModPediaPaths.forConfig(FMLPaths.CONFIGDIR.get());
        paths.migrateLegacyQuietly();
        return paths.runtimeKnowledgeRoot();
    }

    private void publish(AssistantUiState next) {
        state = next;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> notifyListeners(next));
        } else {
            notifyListeners(next);
        }
    }

    private void notifyListeners(AssistantUiState next) {
        listeners.forEach(listener -> listener.accept(next));
    }
}
