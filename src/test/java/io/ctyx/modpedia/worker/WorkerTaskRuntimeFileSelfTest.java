package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskQueryMode;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.TaskTimelineEventType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Worker 直接读取单机 FTBQ 存档进度文件的纯 Java 回归。 */
public final class WorkerTaskRuntimeFileSelfTest {
    private WorkerTaskRuntimeFileSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-ftbq-runtime-file-");
        try {
            String uuid = "dbd5ac05-15d6-46a5-8865-6a8fc9c3f8e6";
            Path file = root.resolve("ftbquests").resolve(uuid + ".snbt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    {
                      version: 1
                      task_progress: {
                        A1B2: 3L
                        C3D4: 1L
                      }
                      started: {
                        0011223344556677: 100L
                        8899AABBCCDDEEFF: 101L
                      }
                      completed: {
                        FFEEDDCCBBAA9988: 99L
                      }
                    }
                    """, StandardCharsets.UTF_8);

            TaskRuntimeFileDescriptor descriptor = new TaskRuntimeFileDescriptor(
                    root.toString(),
                    uuid,
                    "ftbquests:singleplayer",
                    "player:" + uuid + "|world:overworld",
                    "2101.1.29"
            );
            Optional<TaskRuntimeSnapshot> result = WorkerTaskRuntimeFileReader.read(descriptor);
            check(result.isPresent(), "Worker 应能读取本地 FTBQ SNBT");
            TaskRuntimeSnapshot snapshot = result.orElseThrow();
            check(snapshot.startedQuestIds().contains("0011223344556677"), "started 应来自存档文件");
            check(snapshot.completedQuestIds().contains("FFEEDDCCBBAA9988"), "completed 应来自存档文件");
            check(snapshot.taskProgress().get("000000000000A1B2") == 3D, "task_progress 应保留数值");
            check(snapshot.timeline().size() == 3, "started/completed 应生成时间线条目");
            check(snapshot.timeline().stream().anyMatch(entry ->
                            entry.eventType() == TaskTimelineEventType.COMPLETED
                                    && entry.timestampEpochMillis() == 99L),
                    "completed 时间线应保留 SNBT 中的 epoch millis");
            check(snapshot.recentTimeline(1).getFirst().timestampEpochMillis() == 101L,
                    "最近时间线应按时间从新到旧返回");
            check(snapshot.scopeKey().contains(uuid), "运行时作用域应保留玩家标识");
            check(!Files.exists(root.resolve("knowledge.db")), "读取运行时文件不得创建数据库");

            // 大型任务书回归：目标状态位于旧版 32/128/512 前缀之外时，
            // 读取器仍必须保留，不能静默回退到静态默认进度。
            Files.writeString(file, largeRuntimeFile(), StandardCharsets.UTF_8);
            TaskRuntimeSnapshot large = WorkerTaskRuntimeFileReader.read(descriptor).orElseThrow();
            check(large.startedQuestIds().size() == 40, "started 不得按固定前缀截断");
            check(large.completedQuestIds().size() == 140, "completed 不得按固定前缀截断");
            check(large.taskProgress().size() == 520, "task_progress 不得按固定前缀截断");
            check(large.startedQuestIds().contains("0000000000000027"),
                    "超过旧 started 上限的任务仍应可检索");
            check(large.completedQuestIds().contains("000000000000008B"),
                    "超过旧 completed 上限的任务仍应可检索");
            check(large.taskProgress().get("0000000000000207") == 519D,
                    "超过旧 progress 上限的任务仍应保留数值");
            TaskRuntimeSnapshot selected = WorkerTaskRuntimeFileReader.read(descriptor, new TaskQuery(
                    TaskQueryMode.DETAILS, "", "0000000000000027", 8, List.of()
            )).orElseThrow();
            check(selected.startedQuestIds().equals(List.of("0000000000000027")),
                    "指定 quest_id 时应进行查询级状态过滤");
            check(selected.timeline().stream().allMatch(entry ->
                            "0000000000000027".equals(entry.questId())),
                    "指定 quest_id 时时间线也应只保留目标任务");

            List<Long> latencyNanos = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                long started = System.nanoTime();
                check(WorkerTaskRuntimeFileReader.read(descriptor).isPresent(), "稳定文件应可重复读取");
                latencyNanos.add(System.nanoTime() - started);
            }
            latencyNanos.sort(Long::compareTo);
            long p50 = percentile(latencyNanos, 0.50);
            long p95 = percentile(latencyNanos, 0.95);
            long p99 = percentile(latencyNanos, 0.99);
            check(p95 < 50_000_000L, "本地运行时文件读取 p95 不应超过 50ms");
            System.out.printf(
                    "FTBQ runtime file read p50=%.3f ms p95=%.3f ms p99=%.3f ms%n",
                    p50 / 1_000_000D,
                    p95 / 1_000_000D,
                    p99 / 1_000_000D
            );

            // 模拟 FTBQ 快速写入：完整的新文件先落到同目录，再替换旧文件。
            // 读取结果只能是旧快照或新快照，不能因为写入竞争伪造空进度。
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            for (int progress = 4; progress <= 12; progress++) {
                Files.writeString(temporary, runtimeFile(progress), StandardCharsets.UTF_8);
                moveReplacing(temporary, file);
                Optional<TaskRuntimeSnapshot> updated = WorkerTaskRuntimeFileReader.read(descriptor);
                check(updated.isPresent(), "快速替换后应立即得到完整运行时快照");
                check(updated.orElseThrow().taskProgress().get("000000000000A1B2") == progress,
                        "替换后的进度应立即可见");
            }

            Files.writeString(file, "{ task_progress: {", StandardCharsets.UTF_8);
            check(WorkerTaskRuntimeFileReader.read(descriptor).isEmpty(),
                    "损坏的运行时文件应返回空结果，不伪造进度");

            Files.delete(file);
            check(WorkerTaskRuntimeFileReader.read(descriptor).isEmpty(),
                    "缺失的运行时文件应返回空结果");
            System.out.println("ModPedia Worker task runtime file self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String largeRuntimeFile() {
        StringBuilder result = new StringBuilder("{\n  started: {\n");
        for (int index = 0; index < 40; index++) {
            result.append("    ").append(String.format("%016X", index)).append(": 1L\n");
        }
        result.append("  }\n  completed: {\n");
        for (int index = 0; index < 140; index++) {
            result.append("    ").append(String.format("%016X", index + 40)).append(": 1L\n");
        }
        result.append("  }\n  task_progress: {\n");
        for (int index = 0; index < 520; index++) {
            result.append("    ").append(String.format("%016X", index)).append(": ")
                    .append(index).append("L\n");
        }
        return result.append("  }\n}\n").toString();
    }

    private static String runtimeFile(int progress) {
        return """
                {
                  task_progress: { A1B2: %dL }
                  started: { 0011223344556677: 100L }
                  completed: { FFEEDDCCBBAA9988: 99L }
                }
                """.formatted(progress);
    }

    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long percentile(List<Long> sorted, double fraction) {
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * fraction) - 1);
        return sorted.get(Math.max(0, index));
    }
}
