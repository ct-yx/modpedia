package io.ctyx.modpedia.ai;

/** 模型泄漏工具字段时的玩家可读回答回归测试。 */
public final class AiResponseSanitizerSelfTest {
    private AiResponseSanitizerSelfTest() {
    }

    public static void main(String[] args) {
        String result = AiResponseSanitizer.sanitize(
                "已确认的物品信息是：雪属于轻型方块。`item_context` 只有基础 Tooltip；"
                        + "item_context_count=1，results 为空。"
        );
        check(!result.contains("item_context"), "回答正文不应泄漏 item_context 字段名");
        check(!result.contains("item_context_count"), "回答正文不应泄漏计数字段名");
        check(result.contains("已确认物品信息"), "字段应改写为玩家可读名称");
        check(result.contains("搜索结果"), "results 应改写为玩家可读名称");

        String taskResult = AiResponseSanitizer.sanitize(
                "候选任务状态为 `blocked_requirement`，其中 `current: 0`、`required: 1`，"
                        + "progress_available: false，data_definition: task_static_definition。"
        );
        check(!taskResult.contains("blocked_requirement"),
                "任务要求阻塞状态不应泄漏内部枚举值");
        check(!taskResult.contains("current:") && !taskResult.contains("required:"),
                "任务进度字段不应以内部 JSON 字段形式展示");
        check(taskResult.contains("任务要求未完成"),
                "任务阻塞状态应改写为玩家可读文本");
        check(taskResult.contains("当前进度") && taskResult.contains("所需数量"),
                "任务当前值和要求值应改写为玩家可读文本");
        check(taskResult.contains("任务定义"),
                "任务定义来源类型应改写为玩家可读文本");
        System.out.println("ModPedia AI response sanitizer self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
