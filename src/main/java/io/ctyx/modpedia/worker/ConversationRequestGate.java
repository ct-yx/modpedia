package io.ctyx.modpedia.worker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 内的会话级聊天租约。
 *
 * <p>会话文件本身可以安全地串行写入，但这不能保证两个并行 AI 回合的语义顺序。
 * 一个会话只允许一个活动聊天请求；不同会话仍可并行执行。</p>
 */
final class ConversationRequestGate {
    private final Map<String, String> active = new ConcurrentHashMap<>();

    boolean tryAcquire(String conversationId, String requestId) {
        String conversation = normalize(conversationId);
        String request = normalize(requestId);
        if (conversation.isBlank() || request.isBlank()) {
            return false;
        }
        return active.putIfAbsent(conversation, request) == null;
    }

    void release(String conversationId, String requestId) {
        String conversation = normalize(conversationId);
        String request = normalize(requestId);
        if (!conversation.isBlank() && !request.isBlank()) {
            active.remove(conversation, request);
        }
    }

    void clear() {
        active.clear();
    }

    int activeCount() {
        return active.size();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
