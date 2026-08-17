package io.ctyx.modpedia.worker;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 请求专用的有界执行器。
 *
 * <p>模型请求是长耗时任务，不能使用 {@code newCachedThreadPool()}。队列满时由
 * 调用方立即返回忙碌状态，而不是继续创建线程或让 IPC reader 无限堆积请求。</p>
 */
final class WorkerAiExecutor implements AutoCloseable {
    static final int DEFAULT_MAX_CONCURRENCY = 2;
    static final int DEFAULT_QUEUE_CAPACITY = 4;

    private final ThreadPoolExecutor executor;

    WorkerAiExecutor() {
        this(
                Integer.getInteger("modpedia.worker.ai.max_concurrency", DEFAULT_MAX_CONCURRENCY),
                Integer.getInteger("modpedia.worker.ai.queue_capacity", DEFAULT_QUEUE_CAPACITY)
        );
    }

    WorkerAiExecutor(int maxConcurrency, int queueCapacity) {
        int concurrency = Math.max(1, Math.min(8, maxConcurrency));
        int capacity = Math.max(1, Math.min(64, queueCapacity));
        this.executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "modpedia-worker-ai");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    boolean execute(FutureTask<?> task) {
        if (task == null) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
