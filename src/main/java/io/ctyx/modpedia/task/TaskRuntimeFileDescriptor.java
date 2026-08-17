package io.ctyx.modpedia.task;

/**
 * Worker 读取本地单机存档中的 FTB Quests 运行时文件所需的最小描述。
 *
 * <p>描述只包含路径和作用域元数据，不包含玩家进度；实际文件读取、SNBT
 * 解析和快照构建全部发生在 Worker JVM。多人服务器没有本地存档描述时，
 * 继续使用游戏侧的 TeamData 回退链路。</p>
 */
public record TaskRuntimeFileDescriptor(
        String worldRoot,
        String playerUuid,
        String sourceKey,
        String scopeKey,
        String version
) {
    public TaskRuntimeFileDescriptor {
        worldRoot = text(worldRoot);
        playerUuid = text(playerUuid);
        sourceKey = text(sourceKey);
        scopeKey = text(scopeKey);
        version = text(version);
    }

    public boolean usable() {
        return !worldRoot.isBlank() && !playerUuid.isBlank();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
