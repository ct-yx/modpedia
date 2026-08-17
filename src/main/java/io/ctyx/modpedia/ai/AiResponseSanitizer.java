package io.ctyx.modpedia.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将模型偶尔泄漏的工具 JSON 字段改写为玩家可读的名称。 */
public final class AiResponseSanitizer {
    private static final Pattern BACKTICK_TASK_STATUS = Pattern.compile(
            "`\\s*(blocked_requirement|blocked_dependency|task_static_definition|"
                    + "task_runtime_progress|wiki_reference)\\s*`",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BACKTICK_TASK_VALUE = Pattern.compile(
            "`\\s*(current|required)\\s*:\\s*([^`]+?)\\s*`",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TASK_STATUS = Pattern.compile(
            "(?<![A-Za-z0-9_])(blocked_requirement|blocked_dependency|"
                    + "task_static_definition|task_runtime_progress|wiki_reference)"
                    + "(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TASK_FIELD = Pattern.compile(
            "(?<![A-Za-z0-9_])(current|required|completed|progress_available|"
                    + "data_definition|data_progress|status|quest_id|task_id|target_id|"
                    + "requirements|unmet_dependencies|rewards)(?=\\s*:)" ,
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BACKTICK_FIELD = Pattern.compile(
            "`\\s*(item_context_count|item_context|description_markdown|source_mod|document_id|"
                    + "returned_count|new_source_count|context_chars|matched_terms|has_more|"
                    + "source_path|source_type|content_kind|collection_id|results)\\s*`",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FIELD = Pattern.compile(
            "(?<![A-Za-z0-9_])(item_context_count|item_context|description_markdown|source_mod|document_id|"
                    + "returned_count|new_source_count|context_chars|matched_terms|has_more|"
                    + "source_path|source_type|content_kind|collection_id|results)(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Map<String, String> LABELS = labels();

    private AiResponseSanitizer() {
    }

    public static String sanitize(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        String normalized = replace(BACKTICK_TASK_STATUS.matcher(answer));
        normalized = replaceTaskValues(BACKTICK_TASK_VALUE.matcher(normalized));
        normalized = replace(TASK_STATUS.matcher(normalized));
        normalized = replace(BACKTICK_FIELD.matcher(normalized));
        normalized = replace(TASK_FIELD.matcher(normalized));
        return replace(FIELD.matcher(normalized));
    }

    private static String replaceTaskValues(Matcher matcher) {
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = matcher.group(2).strip();
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(LABELS.getOrDefault(key, "内部字段") + "：" + value)
            );
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replace(Matcher matcher) {
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(LABELS.getOrDefault(key, "内部字段"))
            );
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("item_context_count", "已确认物品数量");
        labels.put("item_context", "已确认物品信息");
        labels.put("description_markdown", "物品简介");
        labels.put("source_mod", "来源模组");
        labels.put("document_id", "文档标识");
        labels.put("returned_count", "返回数量");
        labels.put("new_source_count", "新增来源数量");
        labels.put("context_chars", "上下文字符数");
        labels.put("matched_terms", "匹配词");
        labels.put("has_more", "还有更多结果");
        labels.put("source_path", "来源路径");
        labels.put("source_type", "来源类型");
        labels.put("content_kind", "内容类型");
        labels.put("collection_id", "来源集合");
        labels.put("results", "搜索结果");
        labels.put("blocked_requirement", "任务要求未完成");
        labels.put("blocked_dependency", "前置任务未完成");
        labels.put("task_static_definition", "任务定义");
        labels.put("task_runtime_progress", "玩家当前进度");
        labels.put("wiki_reference", "Wiki 说明");
        labels.put("current", "当前进度");
        labels.put("required", "所需数量");
        labels.put("completed", "是否完成");
        labels.put("progress_available", "实时进度状态");
        labels.put("data_definition", "任务定义来源");
        labels.put("data_progress", "进度来源");
        labels.put("status", "任务状态");
        labels.put("quest_id", "任务 ID");
        labels.put("task_id", "要求 ID");
        labels.put("target_id", "目标");
        labels.put("requirements", "任务要求");
        labels.put("unmet_dependencies", "未完成前置任务");
        labels.put("rewards", "奖励");
        return Map.copyOf(labels);
    }
}
