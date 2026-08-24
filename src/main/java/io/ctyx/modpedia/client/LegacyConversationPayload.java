package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Worker 会话 JSON 的旧版客户端兼容读取器。
 *
 * <p>当前 Worker 使用 {@code markdown} 保存消息正文，早期适配层曾使用
 * {@code content}。正文读取集中在这里，避免完成事件、历史加载和会话切换
 * 使用不同字段而产生空消息。</p>
 */
public final class LegacyConversationPayload {
    private LegacyConversationPayload() {
    }

    public static String messageText(JsonObject message) {
        String markdown = string(message, "markdown");
        return markdown.trim().isEmpty() ? string(message, "content") : markdown;
    }

    public static String answerText(JsonObject event, String draft) {
        String answer = string(event, "answer");
        if (!answer.trim().isEmpty()) {
            return answer;
        }
        return draft == null || draft.trim().isEmpty() ? "" : draft;
    }

    public static boolean hasAssistantText(JsonArray messages) {
        if (messages == null) {
            return false;
        }
        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String role = string(message, "role");
            if (("assistant".equalsIgnoreCase(role)
                    || "ASSISTANT".equalsIgnoreCase(role))
                    && !messageText(message).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
