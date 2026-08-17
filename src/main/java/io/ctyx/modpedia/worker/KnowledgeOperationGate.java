package io.ctyx.modpedia.worker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保证会改写知识库的 Worker 操作串行执行，并把运行期间的重复请求合并为
 * 一个最新的后续操作。这个小状态机不依赖 Socket 或 SQLite，便于锁定启动
 * 构建、Wiki 更新和 F9 连按时的调度语义。
 */
final class KnowledgeOperationGate {
    private final Object lock = new Object();
    private boolean running;
    /** 每个可合并类别只保留最新请求；不同类别不能互相丢弃。 */
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    Submission submit(String requestId, Operation operation) {
        return submit(requestId, "default", operation);
    }

    Submission submit(String requestId, String coalesceKey, Operation operation) {
        if (requestId == null || requestId.isBlank() || operation == null) {
            throw new IllegalArgumentException("知识库操作请求不能为空");
        }
        String key = coalesceKey == null || coalesceKey.isBlank() ? "default" : coalesceKey;
        synchronized (lock) {
            if (running) {
                Pending previous = pending.put(key, new Pending(requestId, key, operation));
                String supersededRequestId = previous == null ? "" : previous.requestId();
                return new Submission(false, supersededRequestId);
            }
            running = true;
            return new Submission(true, "");
        }
    }

    /**
     * 当前操作结束后取出最新的 pending 操作；没有 pending 时释放运行状态。
     */
    Pending finish() {
        synchronized (lock) {
            Pending next = pending.isEmpty()
                    ? null
                    : pending.entrySet().iterator().next().getValue();
            if (next != null) {
                pending.remove(next.coalesceKey());
            }
            if (next == null) {
                running = false;
            }
            return next;
        }
    }

    boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    @FunctionalInterface
    interface Operation {
        void run() throws Exception;
    }

    record Submission(boolean started, String supersededRequestId) {
        boolean superseded() {
            return supersededRequestId != null && !supersededRequestId.isBlank();
        }
    }

    record Pending(String requestId, String coalesceKey, Operation operation) {
        Pending(String requestId, Operation operation) {
            this(requestId, "default", operation);
        }
    }
}
