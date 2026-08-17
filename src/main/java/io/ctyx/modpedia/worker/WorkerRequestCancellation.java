package io.ctyx.modpedia.worker;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerProtocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Worker 请求取消状态的线程安全闸门。
 *
 * <p>Future.cancel(true) 只负责发出中断信号，底层 HTTP/SSE 回调仍可能在稍后
 * 投递完成或错误事件。所有 Worker 输出都经过这里，取消后只允许唯一的
 * {@code cancelled} 终态穿过，避免 UI 先显示取消、随后又被迟到事件覆盖。</p>
 */
final class WorkerRequestCancellation {
    /**
     * 取消状态只需要覆盖上游迟到回调的短窗口。永久保存随机 request_id 会让
     * 长时间运行的 Worker 的集合无界增长，因此使用带过期时间的有界表。
     */
    static final long RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(5);
    static final int MAX_ENTRIES = 4096;
    private final ConcurrentHashMap<String, Long> cancelled = new ConcurrentHashMap<>();

    boolean cancel(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        purgeExpired();
        String normalized = requestId.strip();
        Long previous = cancelled.putIfAbsent(normalized,
                System.currentTimeMillis() + RETENTION_MILLIS);
        trimToCapacity();
        return previous == null;
    }

    boolean isCancelled(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        String normalized = requestId.strip();
        Long expiresAt = cancelled.get(normalized);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            cancelled.remove(normalized, expiresAt);
            return false;
        }
        return true;
    }

    boolean allows(JsonObject message) {
        if (message == null) {
            return true;
        }
        if (WorkerProtocol.CANCELLED.equals(WorkerProtocol.string(message, "type"))) {
            return true;
        }
        return !isCancelled(WorkerProtocol.string(message, "request_id"))
                && !isCancelled(WorkerProtocol.string(message, "chat_request_id"));
    }

    void clear() {
        cancelled.clear();
    }

    int sizeForTest() {
        purgeExpired();
        return cancelled.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cancelled.forEach((requestId, expiresAt) -> {
            if (expiresAt == null || expiresAt <= now) {
                cancelled.remove(requestId, expiresAt);
            }
        });
    }

    private void trimToCapacity() {
        while (cancelled.size() > MAX_ENTRIES) {
            String oldest = cancelled.entrySet().stream()
                    .min(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null || cancelled.remove(oldest) == null) {
                return;
            }
        }
    }
}
