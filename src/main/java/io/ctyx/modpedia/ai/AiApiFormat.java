package io.ctyx.modpedia.ai;

import java.util.Locale;

/**
 * AI 服务使用的 HTTP 协议格式。
 *
 * <p>Chat Completions 保留当前默认链路；另外三种格式由 ModPedia 的轻量协议适配器
 * 转换为 LangChain4j 的统一 ChatModel/Tool 调用模型。</p>
 */
public enum AiApiFormat {
    CHAT_COMPLETIONS,
    NATIVE_MESSAGES,
    RESPONSES,
    GENERATE_CONTENT;

    public static AiApiFormat parse(String value) {
        if (value == null) {
            return CHAT_COMPLETIONS;
        }
        String normalized = value.strip()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NATIVE", "MESSAGES", "NATIVE_MESSAGES", "NATIVEMESSAGES", "ANTHROPIC_MESSAGES" -> NATIVE_MESSAGES;
            case "RESPONSE", "RESPONSES", "OPENAI_RESPONSES" -> RESPONSES;
            case "GEMINI", "GENERATE", "GENERATE_CONTENT", "GENERATECONTENT", "GOOGLE_GENERATE_CONTENT" -> GENERATE_CONTENT;
            case "CHAT", "CHAT_COMPLETION", "CHAT_COMPLETIONS", "CHATCOMPLETION", "CHATCOMPLETIONS", "OPENAI_CHAT_COMPLETIONS" -> CHAT_COMPLETIONS;
            default -> CHAT_COMPLETIONS;
        };
    }

    public boolean isChatCompletions() {
        return this == CHAT_COMPLETIONS;
    }
}
