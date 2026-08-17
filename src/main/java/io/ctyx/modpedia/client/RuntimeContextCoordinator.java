package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 客户端运行时上下文的单飞、有界队列和短时缓存协调器。
 *
 * <p>IPC reader 只负责提交任务；真正的 FTBQ/Minecraft 读取在单独的后台线程中
 * 进行。相同聊天请求的重复读取共享一个任务，短时间内的后续请求直接复用最近
 * 快照。世界切换或客户端退出时由 {@link #invalidate()} 完成所有等待者。</p>
 */
final class RuntimeContextCoordinator<T> implements AutoCloseable {
    static final int DEFAULT_QUEUE_CAPACITY = 4;
    static final long DEFAULT_TIMEOUT_MILLIS = 6_000L;
    static final long DEFAULT_CACHE_MILLIS = 750L;

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final Supplier<T> unavailableValue;
    private final Predicate<T> cacheable;
    private final Consumer<Delivery<T>> delivery;
    private final long timeoutMillis;
    private final long cacheMillis;
    private final Map<String, Group<T>> groups = new ConcurrentHashMap<>();
    private volatile Cached<T> cached;
    private volatile boolean closed;

    RuntimeContextCoordinator(
            ScheduledExecutorService scheduler,
            Supplier<T> unavailableValue,
            Predicate<T> cacheable,
            Consumer<Delivery<T>> delivery
    ) {
        this(
                scheduler,
                unavailableValue,
                cacheable,
                delivery,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_CACHE_MILLIS
        );
    }

    RuntimeContextCoordinator(
            ScheduledExecutorService scheduler,
            Supplier<T> unavailableValue,
            Predicate<T> cacheable,
            Consumer<Delivery<T>> delivery,
            int queueCapacity,
            long timeoutMillis,
            long cacheMillis
    ) {
        this.scheduler = scheduler;
        this.unavailableValue = unavailableValue;
        this.cacheable = cacheable == null ? ignored -> false : cacheable;
        this.delivery = delivery;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
        this.cacheMillis = Math.max(0L, cacheMillis);
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "modpedia-runtime-context");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 提交一次读取。返回 false 表示执行器已关闭或队列已满，调用方应立即返回
     * 一个可读的“运行时读取繁忙”结果。
     */
    boolean submit(
            String requestId,
            String deduplicationKey,
            Work<T> work
    ) {
        String request = normalize(requestId);
        String key = normalize(deduplicationKey);
        if (request.isBlank() || key.isBlank() || closed) {
            return false;
        }

        Cached<T> recent = cached;
        if (recent != null && recent.valid(System.nanoTime())) {
            deliver(request, recent.value());
            return true;
        }

        Group<T> next;
        while (true) {
            Cached<T> refreshed = cached;
            if (refreshed != null && refreshed.valid(System.nanoTime())) {
                deliver(request, refreshed.value());
                return true;
            }
            Group<T> current = groups.get(key);
            if (current != null) {
                if (current.add(request)) {
                    return true;
                }
                // 任务刚刚完成但还未从 map 移除；让本次请求重新走缓存/新任务路径。
                groups.remove(key, current);
                continue;
            }
            next = new Group<>(key);
            if (groups.putIfAbsent(key, next) == null) {
                next.add(request);
                break;
            }
        }

        // 循环中为了解决 putIfAbsent 竞态会多次赋值 next；提交任务后固定住
        // 本轮真正注册成功的 Group，避免后台 lambda 捕获一个非 effectively-final
        // 的局部变量，也让完成/超时回调始终指向同一个 waiter 组。
        Group<T> group = next;
        Future<?> future = null;
        try {
            future = executor.submit(() -> execute(group, work));
            group.future = future;
            if (!group.finished.get()) {
                ScheduledFuture<?> timeout = scheduler.schedule(
                        () -> timeout(group),
                        timeoutMillis,
                        TimeUnit.MILLISECONDS
                );
                group.timeout = timeout;
                if (group.finished.get()) {
                    timeout.cancel(false);
                }
            }
            return true;
        } catch (RejectedExecutionException rejected) {
            if (future != null) {
                future.cancel(true);
            }
            finish(group, unavailableValue.get(), false);
            return false;
        }
    }

    /** 取消某一聊天请求的运行时读取，并立即完成其 Worker waiter。 */
    void cancel(String deduplicationKey) {
        Group<T> group = groups.get(normalize(deduplicationKey));
        if (group == null) {
            return;
        }
        // 先完成 group，再发中断信号。这样取消与客户端读取完成同时发生时，
        // 已经进入取消路径的请求优先交付 unavailable，不会在取消事件之后又
        // 交付一份正常快照；后台任务随后结束时由 finished 闸门抑制重复响应。
        finish(group, unavailableValue.get(), false);
        Future<?> future = group.future;
        if (future != null) {
            future.cancel(true);
        }
    }

    /** 世界切换、退出世界和 Worker 重启时调用。 */
    void invalidate() {
        cached = null;
        for (Group<T> group : List.copyOf(groups.values())) {
            finish(group, unavailableValue.get(), false);
            Future<?> future = group.future;
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    int pendingCount() {
        return groups.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        invalidate();
        executor.shutdownNow();
    }

    private void execute(Group<T> group, Work<T> work) {
        T value;
        boolean shouldCache;
        try {
            value = work.run();
            shouldCache = cacheable.test(value);
        } catch (Throwable failure) {
            value = unavailableValue.get();
            shouldCache = false;
        }
        finish(group, value, shouldCache);
    }

    private void timeout(Group<T> group) {
        finish(group, unavailableValue.get(), false);
        Future<?> future = group.future;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void finish(Group<T> group, T value, boolean shouldCache) {
        List<String> requestIds;
        synchronized (group) {
            if (!group.finished.compareAndSet(false, true)) {
                return;
            }
            requestIds = new ArrayList<>(group.requestIds);
        }
        groups.remove(group.key, group);
        ScheduledFuture<?> timeout = group.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        if (shouldCache && cacheMillis > 0L) {
            cached = new Cached<>(value, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cacheMillis));
        }
        for (String requestId : requestIds) {
            deliver(requestId, value);
        }
    }

    private void deliver(String requestId, T value) {
        if (delivery == null) {
            return;
        }
        try {
            delivery.accept(new Delivery<>(requestId, value));
        } catch (Throwable ignored) {
            // 一个请求的响应发送失败不应破坏协调器的队列和缓存状态。
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    @FunctionalInterface
    interface Work<T> {
        T run() throws Exception;
    }

    record Delivery<T>(String requestId, T value) {
    }

    private static final class Group<T> {
        private final String key;
        private final List<String> requestIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile ScheduledFuture<?> timeout;

        private Group(String key) {
            this.key = key;
        }

        private synchronized boolean add(String requestId) {
            if (finished.get()) {
                return false;
            }
            if (!requestIds.contains(requestId)) {
                requestIds.add(requestId);
            }
            return true;
        }
    }

    private record Cached<T>(T value, long expiresAtNanos) {
        private boolean valid(long now) {
            return now < expiresAtNanos;
        }
    }
}
