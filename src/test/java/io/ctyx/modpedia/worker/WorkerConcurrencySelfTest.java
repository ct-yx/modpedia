package io.ctyx.modpedia.worker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Worker AI 有界并发和会话级租约回归。 */
public final class WorkerConcurrencySelfTest {
    private WorkerConcurrencySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        ConversationRequestGate conversations = new ConversationRequestGate();
        check(conversations.tryAcquire("conversation-a", "request-a"),
                "同一会话的第一个请求应取得租约");
        check(!conversations.tryAcquire("conversation-a", "request-b"),
                "同一会话不得同时执行第二个 AI 请求");
        check(conversations.tryAcquire("conversation-b", "request-b"),
                "不同会话仍应允许并行");
        conversations.release("conversation-a", "request-a");
        check(conversations.tryAcquire("conversation-a", "request-c"),
                "前一个请求结束后会话应能再次提交");
        conversations.clear();

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (WorkerAiExecutor executor = new WorkerAiExecutor(1, 1)) {
            FutureTask<Void> first = new FutureTask<>(() -> {
                firstStarted.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
                return null;
            });
            check(executor.execute(first), "第一个 AI 请求应启动");
            check(firstStarted.await(2, TimeUnit.SECONDS), "第一个 AI 请求应进入执行线程");

            FutureTask<Void> queued = new FutureTask<>(() -> null);
            check(executor.execute(queued), "有限队列应接收一个排队请求");
            FutureTask<Void> rejected = new FutureTask<>(() -> null);
            check(!executor.execute(rejected), "超过并发和队列上限时应立即返回忙碌");

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        }
        System.out.println("ModPedia Worker concurrency self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
