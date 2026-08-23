package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 把 1.12.2 任务文件归一化成 Worker 已支持的静态任务输入。
 *
 * <p>旧版任务文件是一文件一任务，现代 Worker 的导入器是一文件一章节、
 * {@code quests[]}。本类只生成 {@code config/ftbquests/quests/modpedia_legacy}
 * 下的派生文件，原始任务目录从不修改；运行时玩家进度也不经过这里。</p>
 */
public final class LegacyFtbQuestNormalizer {
    private static final String GENERATED_DIRECTORY = "modpedia_legacy";
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

    public Result normalize(Path instanceRoot, Path knowledgeRoot) throws IOException {
        Path sourceRoot = instanceRoot == null ? null : instanceRoot.resolve("config/ftbquests");
        Path outputRoot = instanceRoot == null
                ? null : sourceRoot.resolve("quests").resolve(GENERATED_DIRECTORY);
        if (outputRoot == null) {
            return new Result(0, 0, Collections.singletonList("任务实例目录为空"));
        }
        deleteTree(outputRoot);
        if (!Files.isDirectory(sourceRoot)) {
            return new Result(0, 0, Collections.<String>emptyList());
        }

        List<String> warnings = new ArrayList<String>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                    .filter(path -> !path.toString().contains("/quests/" + GENERATED_DIRECTORY + "/"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        int imported = 0;
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_FILE_BYTES) {
                    warnings.add("跳过过大的任务文件：" + sourceRoot.relativize(file));
                    continue;
                }
                Map<String, Object> task = LegacySnbtParser.compound(
                        LegacySnbtParser.parse(new String(bytes, StandardCharsets.UTF_8))
                );
                if (!looksLikeQuest(task)) {
                    continue;
                }
                String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
                String profile = relative.split("/", 2)[0];
                String chapter = relative.contains("/chapters/")
                        ? relative.substring(relative.indexOf("/chapters/") + "/chapters/".length())
                        : relative;
                String id = stem(file.getFileName().toString());
                Path targetDirectory = outputRoot.resolve(safe(profile)).resolve(safe(chapter));
                Files.createDirectories(targetDirectory);
                Path target = targetDirectory.resolve(safe(id) + ".snbt");
                writeAtomically(target, normalizedChapter(task, id, relative));
                imported++;
            } catch (IOException | RuntimeException exception) {
                warnings.add("解析任务文件失败：" + sourceRoot.relativize(file).toString().replace('\\', '/'));
            }
        }

        if (knowledgeRoot != null) {
            writeManifest(knowledgeRoot.resolve("generated").resolve("ftbquests-manifest.json"),
                    files.size(), imported, warnings);
        }
        return new Result(files.size(), imported, warnings);
    }

    private boolean looksLikeQuest(Map<String, Object> task) {
        return task.containsKey("title") || task.containsKey("tasks") || task.containsKey("rewards");
    }

    private Map<String, Object> normalizedChapter(Map<String, Object> source, String id, String relative) {
        Map<String, Object> quest = new LinkedHashMap<String, Object>();
        quest.put("id", id);
        quest.put("title", LegacySnbtParser.text(source.get("title")));
        quest.put("description", LegacySnbtParser.text(source.get("description")));
        quest.put("dependencies", copyStrings(source.get("dependencies")));
        quest.put("tasks", normalizedTasks(source.get("tasks")));
        quest.put("rewards", normalizedRewards(source.get("rewards")));
        quest.put("raw_legacy_source", relative);

        Map<String, Object> chapter = new LinkedHashMap<String, Object>();
        chapter.put("id", id);
        chapter.put("filename", relative);
        chapter.put("order_index", 0);
        List<Object> quests = new ArrayList<Object>();
        quests.add(quest);
        chapter.put("quests", quests);
        return chapter;
    }

    private List<Object> normalizedTasks(Object value) {
        List<Object> result = new ArrayList<Object>();
        for (Object entry : LegacySnbtParser.list(value)) {
            Map<String, Object> source = LegacySnbtParser.compound(entry);
            if (source.isEmpty()) {
                continue;
            }
            Map<String, Object> task = new LinkedHashMap<String, Object>(source);
            String id = text(source, "uid", text(source, "id", "task"));
            task.put("id", id);
            task.put("type", text(source, "type", "unknown"));
            Object item = firstLegacyItem(source);
            if (item != null) {
                task.put("item", item);
            }
            if (!task.containsKey("count")) {
                task.put("count", firstNumber(source, 1, "amount", "required", "value"));
            }
            result.add(task);
        }
        return result;
    }

    private List<Object> normalizedRewards(Object value) {
        List<Object> result = new ArrayList<Object>();
        for (Object entry : LegacySnbtParser.list(value)) {
            Map<String, Object> source = LegacySnbtParser.compound(entry);
            if (source.isEmpty()) {
                continue;
            }
            Map<String, Object> reward = new LinkedHashMap<String, Object>(source);
            String id = text(source, "uid", text(source, "id", "reward"));
            reward.put("id", id);
            String type = text(source, "type", "unknown");
            reward.put("type", type);
            Object item = firstLegacyItem(source);
            if (item != null) {
                reward.put("item", item);
            }
            if ("loot".equalsIgnoreCase(type) && source.containsKey("table")) {
                reward.put("table_id", "ftbquests:loot_table:" + LegacySnbtParser.text(source.get("table")));
            }
            result.add(reward);
        }
        return result;
    }

    private Object firstLegacyItem(Map<String, Object> source) {
        Object direct = source.get("item");
        if (direct instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<String, Object>(LegacySnbtParser.compound(direct));
            if (!map.containsKey("id") && map.containsKey("item")) {
                map.put("id", map.get("item"));
            }
            return map;
        }
        if (direct != null && !LegacySnbtParser.text(direct).trim().isEmpty()) {
            return itemObject(LegacySnbtParser.text(direct));
        }
        for (Object entry : LegacySnbtParser.list(source.get("items"))) {
            Map<String, Object> item = LegacySnbtParser.compound(entry);
            Object value = item.get("item");
            if (value != null) {
                return itemObject(LegacySnbtParser.text(value));
            }
        }
        return null;
    }

    private Map<String, Object> itemObject(String encoded) {
        String[] parts = encoded.trim().split("\\s+");
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", parts.length == 0 ? encoded : parts[0]);
        if (parts.length > 2) {
            item.put("meta", integer(parts[2], 0));
        }
        return item;
    }

    private List<String> copyStrings(Object value) {
        List<String> result = new ArrayList<String>();
        for (Object entry : LegacySnbtParser.list(value)) {
            String text = LegacySnbtParser.text(entry).trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    private int firstNumber(Map<String, Object> object, int fallback, String... keys) {
        for (String key : keys) {
            if (object.containsKey(key)) {
                return (int) Math.round(LegacySnbtParser.number(object.get(key), fallback));
            }
        }
        return fallback;
    }

    private String text(Map<String, Object> object, String key, String fallback) {
        String value = LegacySnbtParser.text(object.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String snbt(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(key(String.valueOf(entry.getKey()))).append(':').append(snbt(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object entry : (List<?>) value) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(snbt(entry));
            }
            return result.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    private static String key(String value) {
        return value.matches("[A-Za-z0-9_+./-]+") ? value : quote(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safe(String value) {
        String normalized = value == null ? "unknown" : value.replace('\\', '/');
        normalized = normalized.replace('/', '_').replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static void writeAtomically(Path target, Map<String, Object> value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, snbt(value).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeManifest(Path path, int files, int imported, List<String> warnings) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema\": 1,\n  \"source_files\": ").append(files)
                .append(",\n  \"imported_quests\": ").append(imported)
                .append(",\n  \"warnings\": [");
        for (int index = 0; index < warnings.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('\n').append("    \"").append(escape(warnings.get(index))).append('"');
        }
        if (!warnings.isEmpty()) {
            json.append('\n').append("  ");
        }
        json.append("]\n}\n");
        Files.write(path, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    public static final class Result {
        private final int sourceFiles;
        private final int importedQuests;
        private final List<String> warnings;

        public Result(int sourceFiles, int importedQuests, List<String> warnings) {
            this.sourceFiles = sourceFiles;
            this.importedQuests = importedQuests;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        }

        public int getSourceFiles() {
            return sourceFiles;
        }

        public int getImportedQuests() {
            return importedQuests;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
