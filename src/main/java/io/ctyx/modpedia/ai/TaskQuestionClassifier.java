package io.ctyx.modpedia.ai;

import java.util.Locale;

/** 轻量任务问题路由器；只用于首轮工具选择，不替代模型对任务内容的判断。 */
public final class TaskQuestionClassifier {
    private static final String[] CHINESE_MARKERS = {
            "任务", "进度", "主线", "下一步", "前置任务", "任务要求", "任务奖励",
            "卡住", "阻塞", "完成了吗", "未完成", "章节"
    };
    private static final String[] ENGLISH_MARKERS = {
            "ftb quests", "ftbq", "questline", "quest", "quests", "task", "tasks",
            "progress", "next quest", "prerequisite quest", "quest requirement",
            "quest reward", "blocked", "objective", "chapter"
    };

    private TaskQuestionClassifier() {
    }

    public static boolean isTaskQuestion(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        for (String marker : CHINESE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        for (String marker : ENGLISH_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
