package io.ctyx.modpedia.client;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/** 客户端运行时读取的单飞、缓存、队列和失效回归。 */
public final class RuntimeContextCoordinatorSelfTest {
    private RuntimeContextCoordinatorSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try (RuntimeContextCoordinator<String> coordinator = new RuntimeContextCoordinator<>(
                scheduler,
                () -> "unavailable",
                value -> value != null && value.startsWith("snapshot"),
                delivery -> delivered.add(delivery.requestId() + "=" + delivery.value()),
                1,
                2_000L,
                1_000L
        )) {
            check(coordinator.submit("request-1", "chat-1", () -> {
                reads.incrementAndGet();
                release.await(2, TimeUnit.SECONDS);
                return "snapshot-1";
            }), "首个运行时读取应入队");
            check(coordinator.submit("request-2", "chat-1", () -> {
                reads.incrementAndGet();
                return "snapshot-duplicate";
            }), "相同聊天请求应合并到单飞任务");
            release.countDown();
            waitFor(() -> delivered.size() == 2, 2_000L);
            check(reads.get() == 1, "相同聊天请求只能执行一次客户端读取");

            check(coordinator.submit("request-3", "chat-2", () -> {
                reads.incrementAndGet();
                return "snapshot-new";
            }), "短时缓存命中请求应成功返回");
            waitFor(() -> delivered.size() == 3, 500L);
            check(reads.get() == 1, "短时缓存命中不得再次切回客户端线程");

            coordinator.invalidate();
            check(coordinator.pendingCount() == 0, "失效时应清理所有运行时 waiter");
        } finally {
            scheduler.shutdownNow();
        }
        System.out.println("ModPedia runtime context coordinator self-test passed");
    }

    private static void waitFor(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!check.ok() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        if (!check.ok()) {
            throw new AssertionError("等待运行时协调器事件超时");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
