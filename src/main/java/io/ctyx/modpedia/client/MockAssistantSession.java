package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 阶段四的确定性模拟会话，后续替换为 AI 会话实现。 */
public final class MockAssistantSession implements AssistantSession {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-mock-assistant");
        thread.setDaemon(true);
        return thread;
    });

    private final CopyOnWriteArrayList<Consumer<AssistantUiState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private volatile AssistantUiState state = new AssistantUiState.Conversation(List.of(), false);
    private volatile String lastPrompt;

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
        EXECUTOR.schedule(() -> Minecraft.getInstance().execute(() -> finish(request, normalized)), 450, TimeUnit.MILLISECONDS);
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

    private void finish(long request, String prompt) {
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

        messages.add(new ChatMessage(
                MessageRole.ASSISTANT,
                "这是阶段四的模拟回答。\n\n我会根据本地手册知识库整理步骤，并在回答底部列出相关来源。\n\n你的问题是：**" + prompt + "**",
                List.of(new SourceReference(
                        "modpedia:bootstrap/knowledge-update",
                        "知识库更新说明",
                        "modpedia",
                        "assets/modpedia/knowledge/bootstrap/knowledge-update.md"
                ))
        ));
        publish(new AssistantUiState.Conversation(messages, false));
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
