package io.ctyx.modpedia.task;

/** 任务运行时快照的世界作用域格式；任务进度跨维度共享。 */
public final class TaskRuntimeScope {
    private TaskRuntimeScope() {
    }

    /** 以玩家/队伍和世界存档身份区分快照，不把 Minecraft 维度放入作用域。 */
    public static String forPlayerAndWorld(String playerOrTeamId, String worldKey) {
        return "player:" + text(playerOrTeamId, "unknown")
                + "|world:" + text(worldKey, "unknown");
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
