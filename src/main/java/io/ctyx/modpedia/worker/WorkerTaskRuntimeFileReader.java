package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.task.FtbQuestIdCodec;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.TaskTimelineEntry;
import io.ctyx.modpedia.task.TaskTimelineEventType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker 端的本地 FTB Quests 运行时文件读取器。
 *
 * <p>1.21.1 的单机存档把玩家任务状态保存为
 * {@code <world>/ftbquests/<team-uuid>.snbt}。文件通常只有很小的几 KB，
 * 直接读取并解析比把整个任务树或对象图交给游戏线程更轻。这里不打开
 * {@code knowledge.db}，也不写入任何文件。FTB Library 的 SNBT 写入先写同目录
 * 临时文件，再用原子替换（不支持时回退为替换移动）；读取器因此只需要确认
 * 本次读取前后的文件指纹一致，不需要文件锁或等待写入完成。</p>
 */
public final class WorkerTaskRuntimeFileReader {
    // 不按 Map 迭代顺序截断运行时状态；那会把目标任务静默变成“无进度”。
    // 单机文件本身仍受 MAX_FILE_BYTES 限制，查询级过滤由 TaskQuery 负责。
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_SNAPSHOT_ATTEMPTS = 3;

    private WorkerTaskRuntimeFileReader() {
    }

    public static Optional<TaskRuntimeSnapshot> read(TaskRuntimeFileDescriptor descriptor) {
        return read(descriptor, null);
    }

    /**
     * 按当前任务查询读取运行时状态。指定 quest_id 时只返回该任务的开始/完成
     * 状态；未指定时完整读取文件中的运行时索引，避免用无依据的前缀 limit 丢失
     * 大型任务书中的目标状态。
     */
    public static Optional<TaskRuntimeSnapshot> read(
            TaskRuntimeFileDescriptor descriptor,
            TaskQuery query
    ) {
        if (descriptor == null || !descriptor.usable()) {
            return Optional.empty();
        }
        final UUID playerUuid;
        try {
            playerUuid = UUID.fromString(descriptor.playerUuid());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        Path worldRoot;
        try {
            worldRoot = Path.of(descriptor.worldRoot()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        for (Path file : candidateFiles(worldRoot, playerUuid)) {
            Optional<TaskRuntimeSnapshot> snapshot = readFile(file, descriptor, worldRoot, query);
            if (snapshot.isPresent()) {
                return snapshot;
            }
        }
        return Optional.empty();
    }

    private static List<Path> candidateFiles(Path worldRoot, UUID playerUuid) {
        Path quests = worldRoot.resolve("ftbquests");
        List<Path> result = new ArrayList<>();

        // TeamData 的文件名通常是 team UUID，而单人队伍恰好等于玩家 UUID。
        // 先读取 FTB Teams 的玩家映射，兼容多人组队后的本地单机存档。
        Path playerTeamFile = worldRoot.resolve("ftbteams").resolve("player")
                .resolve(playerUuid + ".snbt").normalize();
        try {
            Optional<String> raw = readStableText(playerTeamFile);
            if (raw.isPresent()) {
                Map<String, Object> team = WorkerSnbtParser.compound(WorkerSnbtParser.parse(raw.get()));
                String teamId = WorkerSnbtParser.text(team, "id");
                if (isUuid(teamId)) {
                    result.add(quests.resolve(teamId + ".snbt").normalize());
                }
            }
        } catch (RuntimeException ignored) {
            // 映射文件缺失或正在刷新时，继续尝试玩家 UUID 文件。
        }
        result.add(quests.resolve(playerUuid + ".snbt").normalize());
        return result.stream().filter(path -> path.startsWith(worldRoot)).distinct().toList();
    }

    private static Optional<TaskRuntimeSnapshot> readFile(
            Path file,
            TaskRuntimeFileDescriptor descriptor,
            Path worldRoot,
            TaskQuery query
    ) {
        if (!file.startsWith(worldRoot)) {
            return Optional.empty();
        }
        // FTBQ 的进度文件很小，但可能在查询时被快速重写。读取前后比较
        // 文件指纹：读到完整旧文件或完整新文件都可以直接使用；只有写入
        // 穿过本次读取，或者 SNBT 仍处于半写入状态时，才立即无休眠重试。
        // 这样不会轮询文件，也不会让这次查询回到游戏线程。
        for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            Optional<String> raw = readStableText(file);
            if (raw.isEmpty()) {
                continue;
            }
            try {
                return Optional.of(readSnapshot(raw.get(), descriptor, query));
            } catch (RuntimeException exception) {
                // 稳定但仍是半写入内容时，继续同一请求内的无阻塞重试。
                Thread.onSpinWait();
            }
        }
        return Optional.empty();
    }

    /**
     * 尽量零等待地读取一个稳定文件快照。
     *
     * <p>不使用锁、不 sleep、不写临时文件。FTBQ 的写入通常是一次很小的
     * SNBT 写入；如果恰好撞上写入窗口，下一次循环会立即拿到新快照。</p>
     */
    private static Optional<String> readStableText(Path file) {
        for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            FileStamp before = stamp(file);
            if (before == null || before.size() > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_FILE_BYTES) {
                    return Optional.empty();
                }
                FileStamp after = stamp(file);
                if (after != null && before.equals(after)) {
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (IOException exception) {
                // 文件可能刚好被原子替换或暂时不存在；立即重试，不阻塞游戏。
            }
            Thread.onSpinWait();
        }
        return Optional.empty();
    }

    private static FileStamp stamp(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file,
                    BasicFileAttributes.class
            );
            if (!attributes.isRegularFile()) {
                return null;
            }
            return new FileStamp(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey()
            );
        } catch (IOException exception) {
            return null;
        }
    }

    private record FileStamp(long size, FileTime modifiedTime, Object fileKey) {
    }

    private static TaskRuntimeSnapshot readSnapshot(
            String raw,
            TaskRuntimeFileDescriptor descriptor,
            TaskQuery query
    ) {
        Map<String, Object> root = WorkerSnbtParser.compound(WorkerSnbtParser.parse(raw));
        List<TaskTimelineEntry> timeline = new ArrayList<>();
        timeline.addAll(timeline(root.get("started"), TaskTimelineEventType.STARTED, query));
        timeline.addAll(timeline(root.get("completed"), TaskTimelineEventType.COMPLETED, query));
        return new TaskRuntimeSnapshot(
                valueOr(descriptor.sourceKey(), "ftbquests:local"),
                valueOr(descriptor.scopeKey(), "player:" + descriptor.playerUuid()),
                valueOr(descriptor.version(), "unknown"),
                keys(root.get("started"), query),
                keys(root.get("completed"), query),
                progress(root),
                timeline
        );
    }

    private static List<TaskTimelineEntry> timeline(
            Object value,
            TaskTimelineEventType eventType,
            TaskQuery query
    ) {
        Map<String, Object> map = WorkerSnbtParser.compound(value);
        String requestedQuestId = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
        List<TaskTimelineEntry> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String questId = FtbQuestIdCodec.fromSnbtKey(entry.getKey());
            if (questId.isBlank() || !requestedQuestId.isBlank() && !requestedQuestId.equals(questId)) {
                continue;
            }
            result.add(new TaskTimelineEntry(
                    questId,
                    eventType,
                    timestamp(entry.getValue()),
                    null,
                    eventType == TaskTimelineEventType.COMPLETED ? 1D : null
            ));
        }
        return result;
    }

    private static List<String> keys(Object value, TaskQuery query) {
        Map<String, Object> map = WorkerSnbtParser.compound(value);
        String requestedQuestId = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
        return map.keySet().stream()
                .filter(key -> key != null && !key.isBlank())
                .map(FtbQuestIdCodec::fromSnbtKey)
                .filter(key -> !key.isBlank())
                .filter(key -> requestedQuestId.isBlank() || requestedQuestId.equals(key))
                .distinct()
                .toList();
    }

    private static Map<String, Double> progress(Map<String, Object> root) {
        Object value = root.get("task_progress");
        if (value == null) {
            value = root.get("taskProgress");
        }
        Map<String, Object> map = WorkerSnbtParser.compound(value);
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            double number = number(entry.getValue());
            if (!Double.isFinite(number)) {
                continue;
            }
            String taskId = FtbQuestIdCodec.fromSnbtKey(entry.getKey());
            if (!taskId.isBlank()) {
                result.put(taskId, number);
            }
        }
        return Map.copyOf(result);
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? Double.NaN : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private static long timestamp(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
