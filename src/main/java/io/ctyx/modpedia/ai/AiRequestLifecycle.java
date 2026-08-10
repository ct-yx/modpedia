package io.ctyx.modpedia.ai;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个 AI 请求的终态闸门。
 *
 * <p>流式回调和阻塞回退运行在不同线程，不能用两个互不关联的布尔值判断谁先完成。
 * 这里明确限制状态迁移：OPEN 只能进入 FALLBACK 或 COMPLETED，FALLBACK 期间忽略
 * 迟到的流式完成回调。</p>
 */
final class AiRequestLifecycle {
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    boolean beginFallback() {
        return state.compareAndSet(State.OPEN, State.FALLBACK);
    }

    boolean complete() {
        return state.compareAndSet(State.OPEN, State.COMPLETED);
    }

    boolean completeFallback() {
        return state.compareAndSet(State.FALLBACK, State.COMPLETED);
    }

    boolean isOpen() {
        return state.get() == State.OPEN;
    }

    private enum State {
        OPEN,
        FALLBACK,
        COMPLETED
    }
}
