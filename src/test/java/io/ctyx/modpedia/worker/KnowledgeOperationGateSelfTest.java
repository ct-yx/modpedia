package io.ctyx.modpedia.worker;

import java.util.ArrayList;
import java.util.List;

/** 知识库构建合并状态机回归；不启动游戏、不访问网络或真实模型。 */
public final class KnowledgeOperationGateSelfTest {
    private KnowledgeOperationGateSelfTest() {
    }

    public static void main(String[] args) {
        KnowledgeOperationGate gate = new KnowledgeOperationGate();
        List<String> executed = new ArrayList<>();

        KnowledgeOperationGate.Submission first = gate.submit(
                "first", () -> executed.add("first"));
        check(first.started() && !first.superseded(), "首个构建请求应立即开始");

        KnowledgeOperationGate.Submission second = gate.submit(
                "second", () -> executed.add("second"));
        check(!second.started() && !second.superseded(), "首个 pending 请求应被合并但未替代旧请求");

        KnowledgeOperationGate.Submission third = gate.submit(
                "third", () -> executed.add("third"));
        check(!third.started() && "second".equals(third.supersededRequestId()),
                "连续请求应只保留最新请求，并标记被替代的 pending 请求");

        KnowledgeOperationGate.Pending next = gate.finish();
        check(next != null && "third".equals(next.requestId()),
                "首个构建结束后应取出最新 pending 请求");
        check(gate.isRunning(), "存在 pending 请求时 gate 仍应保持运行状态");

        check(gate.finish() == null, "最新构建结束后不应再产生额外构建");
        check(!gate.isRunning(), "没有 pending 请求时 gate 应恢复空闲");

        // Wiki 和重建必须各自合并；Wiki 请求不能把一次待执行的重建静默
        // 覆盖掉，反之亦然。finish() 按首次排队顺序取出两类操作。
        KnowledgeOperationGate.Submission running = gate.submit(
                "running", "rebuild", () -> { });
        check(running.started(), "不同类别回归的首个操作应立即开始");
        gate.submit("rebuild-pending", "rebuild", () -> { });
        gate.submit("wiki-pending", "wiki", () -> { });
        KnowledgeOperationGate.Pending rebuildPending = gate.finish();
        check(rebuildPending != null && "rebuild-pending".equals(rebuildPending.requestId()),
                "同类重建请求应保留最新重建 pending");
        KnowledgeOperationGate.Pending wikiPending = gate.finish();
        check(wikiPending != null && "wiki-pending".equals(wikiPending.requestId()),
                "不同类别 Wiki 请求不得被重建请求覆盖");
        check(gate.finish() == null && !gate.isRunning(),
                "多类别 pending 全部消费后 gate 应恢复空闲");

        KnowledgeOperationGate.Submission fourth = gate.submit(
                "fourth", () -> executed.add("fourth"));
        check(fourth.started(), "完成一轮构建后新请求应可以立即开始");
        gate.finish();
        System.out.println("ModPedia knowledge operation gate self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
