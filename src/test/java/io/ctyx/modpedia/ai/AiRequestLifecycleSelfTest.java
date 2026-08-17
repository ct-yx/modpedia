package io.ctyx.modpedia.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 流式请求完成/回退竞态回归测试：一次请求只能进入一个终态。 */
public final class AiRequestLifecycleSelfTest {
    private AiRequestLifecycleSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        AiRequestLifecycle lifecycle = new AiRequestLifecycle();
        CountDownLatch start = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        String[] winners = new String[2];
        Thread complete = new Thread(() -> {
            start.countDown();
            await(start);
            winners[0] = lifecycle.complete() ? "complete" : "ignored";
            done.countDown();
        });
        Thread fallback = new Thread(() -> {
            start.countDown();
            await(start);
            winners[1] = lifecycle.beginFallback() ? "fallback" : "ignored";
            done.countDown();
        });
        complete.start();
        fallback.start();
        check(done.await(2, TimeUnit.SECONDS), "竞态夹具未完成");
        check(("complete".equals(winners[0]) ^ "fallback".equals(winners[1])),
                "完成和回退必须只有一个获胜者");
        if ("fallback".equals(winners[1])) {
            check(lifecycle.completeFallback(), "回退获胜后必须能够提交唯一终态");
        }
        check(!lifecycle.beginFallback(), "进入完成态后不能再次启动回退");
        check(!lifecycle.complete(), "进入终态后不能重复完成");
        testFirstRequestToolChoice();
        testTaskQuestionRouting();
        check(TaskQuestionClassifier.isTaskQuestion("当前主线下一步任务是什么？"),
                "中文任务问题应路由到 search_tasks");
        check(TaskQuestionClassifier.isTaskQuestion("What is my next quest progress?"),
                "英文任务问题应路由到 search_tasks");
        check(!TaskQuestionClassifier.isTaskQuestion("压力容器如何启动？"),
                "普通模组问题不应误路由到 search_tasks");
        System.out.println("ModPedia AI request lifecycle self-test passed");
    }

    private static void testFirstRequestToolChoice() {
        AtomicBoolean first = new AtomicBoolean(true);
        ChatRequest original = ChatRequest.builder()
                .messages(UserMessage.from("问题"))
                .build();
        ChatRequest forced = AiAssistantSession.requireSearchOnFirstRequest(original, first);
        check(forced.toolChoice() == ToolChoice.REQUIRED,
                "AI 新问题的第一次模型请求必须强制调用 search_knowledge");
        ChatRequest followUp = AiAssistantSession.requireSearchOnFirstRequest(original, first);
        check(followUp == original,
                "工具结果后的模型请求应恢复自动选择，允许结束或继续补搜");
    }

    private static void testTaskQuestionRouting() {
        ChatRequest original = ChatRequest.builder()
                .messages(UserMessage.from("任务进度"))
                .toolSpecifications(
                        ToolSpecification.builder().name("search_knowledge").build(),
                        ToolSpecification.builder().name("search_tasks").build(),
                        ToolSpecification.builder().name("search_wiki").build()
                )
                .build();
        AtomicBoolean first = new AtomicBoolean(true);
        ChatRequest forced = AiAssistantSession.requireSearchOnFirstRequest(original, first, true);
        check(forced.toolChoice() == ToolChoice.REQUIRED,
                "任务问题首轮必须要求工具调用");
        check(forced.toolSpecifications().size() == 1
                        && "search_tasks".equals(forced.toolSpecifications().getFirst().name()),
                "任务问题首轮只能暴露 search_tasks 工具");
        ChatRequest followUp = AiAssistantSession.requireSearchOnFirstRequest(original, first, true);
        check(followUp == original,
                "任务工具结果后的请求应恢复全部工具并允许模型继续补搜");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
