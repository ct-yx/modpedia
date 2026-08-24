package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 旧版界面的纯会话状态机。
 *
 * <p>网络回调只负责调用这里的状态转换，绘制层不再把“正在生成的草稿”和
 * 已经固化的回答混在一起。该类不依赖 Minecraft，便于在无图形环境中回归
 * 发送、重试、取消和失败路径。</p>
 */
public final class LegacyConversationState {
    private final List<LegacyChatMessage> messages = new ArrayList<LegacyChatMessage>();
    private String draft = "";
    private String lastPrompt = "";
    private boolean retryAvailable;

    public void submit(String prompt, boolean retry) {
        String value = safe(prompt);
        if (value.isEmpty()) {
            return;
        }
        lastPrompt = value;
        retryAvailable = false;
        draft = "";
        if (!retry) {
            messages.add(new LegacyChatMessage(LegacyChatMessage.Role.USER, value));
        }
    }

    public void appendDraft(String delta) {
        if (delta != null && !delta.isEmpty()) {
            draft += delta;
        }
    }

    public void complete(String answer, List<LegacySourceReference> sources,
            List<String> followUps) {
        String value = safe(answer);
        if (!value.isEmpty()) {
            messages.add(new LegacyChatMessage(LegacyChatMessage.Role.ASSISTANT,
                    value, sources, followUps));
        }
        draft = "";
        retryAvailable = false;
    }

    public void fail(boolean allowRetry) {
        commitDraft();
        retryAvailable = allowRetry && !lastPrompt.isEmpty();
    }

    public void cancel() {
        commitDraft();
        retryAvailable = !lastPrompt.isEmpty();
    }

    public void replaceMessages(List<LegacyChatMessage> values) {
        messages.clear();
        if (values != null) {
            for (LegacyChatMessage message : values) {
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        lastPrompt = "";
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getRole() == LegacyChatMessage.Role.USER) {
                lastPrompt = messages.get(index).getMarkdown();
                break;
            }
        }
        draft = "";
        retryAvailable = false;
    }

    public void clear() {
        messages.clear();
        draft = "";
        lastPrompt = "";
        retryAvailable = false;
    }

    public List<LegacyChatMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<LegacyChatMessage>(messages));
    }

    public String draft() {
        return draft;
    }

    public String lastPrompt() {
        return lastPrompt;
    }

    public boolean retryAvailable() {
        return retryAvailable;
    }

    private void commitDraft() {
        if (!draft.isEmpty()) {
            messages.add(new LegacyChatMessage(LegacyChatMessage.Role.ASSISTANT, draft));
            draft = "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
