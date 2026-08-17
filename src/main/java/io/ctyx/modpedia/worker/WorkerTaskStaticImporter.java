package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.task.TaskSnapshot;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Worker 端的静态任务定义导入器。
 *
 * <p>只读取任务定义和语言文件，不读取玩家 TeamData；运行时进度由客户端在查询
 * 时单独发送。未知 SNBT 字段通过 rawJson 保留，图片路径也作为原始文本的一部分
 * 保留，不尝试把图片复制进数据库。</p>
 */
public final class WorkerTaskStaticImporter {
    private static final String SOURCE_PREFIX = "ftbquests:static:";
    private static final long MAX_TASK_FILE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_LANGUAGE_FILE_BYTES = 4L * 1024L * 1024L;

    public ImportResult importDirectory(Path questsRoot) throws IOException {
        Path root = questsRoot == null ? null : questsRoot.toAbsolutePath().normalize();
        if (root == null || !Files.isDirectory(root)) {
            // sourcePresent=false 仍然是一次完整的“当前来源为空”扫描；上层可以
            // 用空集合清理已经移除的静态任务章节，而解析中断则使用 complete=false
            // 保护上一版本。
            return new ImportResult(false, true, List.of(), List.of());
        }

        Map<String, String> english = loadLanguage(root, "en_us");
        Map<String, String> chinese = loadLanguage(root, "zh_cn");
        Map<String, String> localized = new LinkedHashMap<>(english);
        localized.putAll(chinese);

        List<Path> chapterFiles;
        Path chaptersRoot = root.resolve("chapters");
        if (!Files.isDirectory(chaptersRoot)) {
            return new ImportResult(true, true, List.of(), List.of());
        }
        try (Stream<Path> files = Files.walk(chaptersRoot)) {
            chapterFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                    .sorted()
                    .toList();
        }

        List<TaskSnapshot> snapshots = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean complete = true;
        for (Path file : chapterFiles) {
            try {
                snapshots.add(parseChapter(root, file, localized));
            } catch (RuntimeException | IOException exception) {
                complete = false;
                warnings.add("Worker 解析任务章节失败：" + root.relativize(file).toString().replace('\\', '/'));
            }
        }
        return new ImportResult(true, complete, snapshots, warnings);
    }

    private TaskSnapshot parseChapter(Path root, Path file, Map<String, String> localized) throws IOException {
        byte[] bytes = readLimitedBytes(file, MAX_TASK_FILE_BYTES);
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Map<String, Object> chapter = WorkerSnbtParser.compound(WorkerSnbtParser.parse(raw));
        String relative = root.relativize(file).toString().replace('\\', '/');
        String chapterId = text(chapter, "id", file.getFileName().toString());
        String filename = text(chapter, "filename", file.getFileName().toString());
        String sourceKey = SOURCE_PREFIX + relative;
        String snapshotId = "ftbquests-static-" + sha256(relative.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        String scopeKey = "ftbquests:chapter:" + filename;
        int chapterOrder = WorkerSnbtParser.integer(chapter, "order_index", 0);
        List<TaskSnapshot.TaskQuest> quests = new ArrayList<>();
        List<Object> questValues = WorkerSnbtParser.list(chapter.get("quests"));
        for (int index = 0; index < questValues.size(); index++) {
            Map<String, Object> quest = WorkerSnbtParser.compound(questValues.get(index));
            if (quest.isEmpty()) {
                continue;
            }
            String questId = text(quest, "id", "quest-" + index);
            String title = localized(localized, "quest." + questId + ".title",
                    text(quest, "title", questId));
            String subtitle = localized(localized, "quest." + questId + ".quest_subtitle", "");
            String description = localized(localized, "quest." + questId + ".quest_desc",
                    text(quest, "description", ""));
            quests.add(new TaskSnapshot.TaskQuest(
                    questId,
                    chapterId,
                    title,
                    markdown(subtitle),
                    markdown(description),
                    WorkerSnbtParser.bool(quest, "optional", false),
                    !WorkerSnbtParser.bool(quest, "hidden", false),
                    false,
                    false,
                    chapterOrder * 100_000 + index,
                    strings(quest.get("dependencies")),
                    requirements(quest.get("tasks"), localized),
                    rewards(quest.get("rewards"), localized),
                    WorkerSnbtParser.toSnbt(quest)
            ));
        }
        return new TaskSnapshot(
                snapshotId,
                sourceKey,
                sha256(bytes),
                scopeKey,
                "ftbquests-static",
                raw,
                quests
        );
    }

    private List<TaskSnapshot.TaskRequirement> requirements(Object value, Map<String, String> localized) {
        List<TaskSnapshot.TaskRequirement> result = new ArrayList<>();
        List<Object> values = WorkerSnbtParser.list(value);
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> task = WorkerSnbtParser.compound(values.get(index));
            if (task.isEmpty()) {
                continue;
            }
            String taskId = text(task, "id", "task-" + index);
            String type = text(task, "type", "unknown");
            String target = targetId(task);
            String title = localized(localized, "task." + taskId + ".title", target.isBlank() ? type : target);
            double required = firstNumber(task, 1, "count", "amount", "required", "value", "xp", "xp_levels");
            result.add(new TaskSnapshot.TaskRequirement(
                    taskId,
                    type,
                    title,
                    target,
                    0,
                    required,
                    false,
                    WorkerSnbtParser.toSnbt(task)
            ));
        }
        return List.copyOf(result);
    }

    private List<TaskSnapshot.TaskReward> rewards(Object value, Map<String, String> localized) {
        List<TaskSnapshot.TaskReward> result = new ArrayList<>();
        List<Object> values = WorkerSnbtParser.list(value);
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> reward = WorkerSnbtParser.compound(values.get(index));
            if (reward.isEmpty()) {
                continue;
            }
            String rewardId = text(reward, "id", "reward-" + index);
            String type = text(reward, "type", "unknown");
            boolean random = isRandom(type);
            String title = localized(localized, "reward." + rewardId + ".title", type);
            List<String> candidates = rewardCandidates(reward);
            boolean guaranteed = !random && !"choice".equalsIgnoreCase(type);
            result.add(new TaskSnapshot.TaskReward(
                    rewardId,
                    type,
                    title,
                    guaranteed,
                    candidates,
                    WorkerSnbtParser.toSnbt(reward)
            ));
        }
        return List.copyOf(result);
    }

    private List<String> rewardCandidates(Map<String, Object> reward) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectItemIds(reward.get("item"), result);
        collectItemIds(reward.get("items"), result);
        collectItemIds(reward.get("choices"), result);
        if (reward.containsKey("table_id")) {
            result.add("loot_table:" + WorkerSnbtParser.text(reward.get("table_id")));
        }
        return List.copyOf(result);
    }

    private void collectItemIds(Object value, Set<String> output) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> object = WorkerSnbtParser.compound(value);
            String id = text(object, "id", "");
            if (!id.isBlank()) {
                output.add(id);
            }
            object.values().forEach(child -> collectItemIds(child, output));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectItemIds(child, output));
        }
    }

    private String targetId(Map<String, Object> task) {
        for (String key : List.of("item", "block", "entity", "entity_type", "advancement",
                "biome", "dimension", "structure", "location", "id")) {
            Object value = task.get(key);
            if (value instanceof Map<?, ?> map) {
                String id = text(WorkerSnbtParser.compound(map), "id", "");
                if (!id.isBlank()) {
                    return id;
                }
            } else if (value != null && !WorkerSnbtParser.text(value).isBlank()) {
                return WorkerSnbtParser.text(value);
            }
        }
        return "";
    }

    private double firstNumber(Map<String, Object> object, double fallback, String... keys) {
        for (String key : keys) {
            if (object.containsKey(key)) {
                return WorkerSnbtParser.number(object, key, fallback);
            }
        }
        return fallback;
    }

    private boolean isRandom(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return normalized.contains("random") || normalized.contains("loot");
    }

    private Map<String, String> loadLanguage(Path root, String language) throws IOException {
        Path languageRoot = root.resolve("lang");
        if (!Files.isDirectory(languageRoot)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Path flat = languageRoot.resolve(language + ".snbt");
        if (Files.isRegularFile(flat)) {
            mergeLanguage(result, readLimited(flat, MAX_LANGUAGE_FILE_BYTES));
        }
        Path nested = languageRoot.resolve(language);
        if (Files.isDirectory(nested)) {
            try (Stream<Path> files = Files.walk(nested)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                        .sorted().toList()) {
                    mergeLanguage(result, readLimited(file, MAX_LANGUAGE_FILE_BYTES));
                }
            }
        }
        return Map.copyOf(result);
    }

    private byte[] readLimitedBytes(Path file, long limit) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("任务资源超过大小上限：" + file);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String readLimited(Path file, long limit) throws IOException {
        return new String(readLimitedBytes(file, limit), StandardCharsets.UTF_8);
    }

    private void mergeLanguage(Map<String, String> output, String raw) {
        try {
            Map<String, Object> values = WorkerSnbtParser.compound(WorkerSnbtParser.parse(raw));
            values.forEach((key, value) -> output.put(key, localizedValue(value)));
        } catch (RuntimeException ignored) {
            // 单个语言文件损坏时保留其它文件和英文回退。
        }
    }

    private String localizedValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(WorkerSnbtParser::text).reduce((first, next) -> first + "\n" + next).orElse("");
        }
        return WorkerSnbtParser.text(value);
    }

    private String localized(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String markdown(String value) {
        return value == null ? "" : value.replace("\\n", "\n").strip();
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : WorkerSnbtParser.list(value)) {
            String text = WorkerSnbtParser.text(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private String text(Map<String, Object> object, String key, String fallback) {
        String value = WorkerSnbtParser.text(object.get(key));
        return value.isBlank() ? fallback : value;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public static String sourcePrefix() {
        return SOURCE_PREFIX;
    }

    public record ImportResult(
            boolean sourcePresent,
            boolean complete,
            List<TaskSnapshot> snapshots,
            List<String> warnings
    ) {
        public ImportResult {
            snapshots = List.copyOf(snapshots == null ? List.of() : snapshots);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }
}
