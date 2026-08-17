package io.ctyx.modpedia.client;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.knowledge.KnowledgeStatus;
import io.ctyx.modpedia.protocol.WorkerProtocol;

/** 任务 Wiki 同步完成后不得让客户端知识状态永久停留在 updating。 */
public final class KnowledgeStatusSelfTest {
    private KnowledgeStatusSelfTest() {
    }

    public static void main(String[] args) {
        JsonObject changed = WorkerProtocol.message(WorkerProtocol.COMPLETED, "wiki-changed");
        changed.addProperty("operation", WorkerProtocol.TASK_WIKI_SYNC);
        changed.addProperty("changed", true);
        changed.addProperty("source_count", 9);
        changed.addProperty("document_count", 512);

        KnowledgeStatus completed = ModPediaBridge.completedKnowledgeStatus(
                new KnowledgeStatus(true, 2, 3, "old", ""),
                changed
        );
        check(!completed.updating(), "Wiki 重建完成后 updating 必须清除");
        check(completed.sourceCount() == 9 && completed.documentCount() == 512,
                "Wiki 触发重建时应采用 Worker 返回的新计数");
        check(completed.error().isEmpty(), "成功完成事件不应保留旧错误");

        JsonObject unchanged = WorkerProtocol.message(WorkerProtocol.COMPLETED, "wiki-unchanged");
        unchanged.addProperty("operation", WorkerProtocol.TASK_WIKI_SYNC);
        unchanged.addProperty("changed", false);
        KnowledgeStatus reused = ModPediaBridge.completedKnowledgeStatus(completed, unchanged);
        check(!reused.updating(), "未变化的 Wiki 同步完成后 updating 必须清除");
        check(reused.sourceCount() == 9 && reused.documentCount() == 512,
                "未触发重建时应保留上一份知识库计数");

        System.out.println("ModPedia knowledge status self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
