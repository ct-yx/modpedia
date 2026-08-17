package io.ctyx.modpedia.task;

/** 一次任务工具查询可展示给玩家的三类数量，运行时部分只存在于当前请求。 */
public record TaskSearchSummary(
        int taskDefinitionCount,
        int runtimeStateCount,
        int progressItemCount,
        boolean runtimeProgressAvailable,
        int timelineEntryCount
) {
    /** 保留旧版四字段构造方式。 */
    public TaskSearchSummary(
            int taskDefinitionCount,
            int runtimeStateCount,
            int progressItemCount,
            boolean runtimeProgressAvailable
    ) {
        this(taskDefinitionCount, runtimeStateCount, progressItemCount,
                runtimeProgressAvailable, 0);
    }

    public TaskSearchSummary {
        taskDefinitionCount = Math.max(0, taskDefinitionCount);
        runtimeStateCount = Math.max(0, runtimeStateCount);
        progressItemCount = Math.max(0, progressItemCount);
        timelineEntryCount = Math.max(0, timelineEntryCount);
    }

    public static TaskSearchSummary empty() {
        return new TaskSearchSummary(0, 0, 0, false);
    }

    public static TaskSearchSummary from(
            int taskDefinitionCount,
            int runtimeStateCount,
            int progressItemCount,
            boolean runtimeProgressAvailable
    ) {
        return new TaskSearchSummary(
                taskDefinitionCount,
                runtimeStateCount,
                progressItemCount,
                runtimeProgressAvailable,
                0
        );
    }

    public static TaskSearchSummary from(
            int taskDefinitionCount,
            int runtimeStateCount,
            int progressItemCount,
            boolean runtimeProgressAvailable,
            int timelineEntryCount
    ) {
        return new TaskSearchSummary(
                taskDefinitionCount,
                runtimeStateCount,
                progressItemCount,
                runtimeProgressAvailable,
                timelineEntryCount
        );
    }

    public boolean visible() {
        return runtimeProgressAvailable
                || taskDefinitionCount > 0
                || runtimeStateCount > 0
                || progressItemCount > 0
                || timelineEntryCount > 0;
    }
}
