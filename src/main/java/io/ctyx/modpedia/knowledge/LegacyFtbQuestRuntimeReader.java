package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 1.12.2 FTBQ 运行时进度读取器。
 *
 * <p>旧版 FTB Library 把团队任务状态保存为
 * {@code <world>/data/ftb_lib/teams/<team-id>/ftbquests.dat} 的压缩 NBT，
 * 不是现代版本的 {@code ftbquests/*.snbt}。本类同时保留后者作为兼容回退，
 * 只在 Worker 请求任务查询时读取文件，不写 knowledge.db，也不把快照落盘。</p>
 */
public final class LegacyFtbQuestRuntimeReader {
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_SNAPSHOT_ATTEMPTS = 3;
    private static final Object LOCK = new Object();
    private static volatile Context context;
    private static volatile JsonObject remoteSnapshot;
    private static volatile String remoteSnapshotScope = "";
    private static final Map<String, SnapshotState> previous = new LinkedHashMap<String, SnapshotState>();
    private static final Map<String, JsonObject> serverSnapshots = new LinkedHashMap<String, JsonObject>();

    private LegacyFtbQuestRuntimeReader() {
    }

    public static void setWorldContext(Path worldRoot, String playerUuid) {
        setWorldContext(worldRoot, playerUuid, "");
    }

    public static void setWorldContext(Path worldRoot, String playerUuid, String playerName) {
        Context next = worldRoot == null || playerUuid == null || playerUuid.trim().isEmpty()
                ? null : new Context(
                worldRoot.toAbsolutePath().normalize(),
                playerUuid.trim(),
                playerName == null ? "" : playerName.trim()
        );
        Context old = context;
        context = next;
        if (next != null) {
            clearRemoteSnapshot();
        }
        if (old == null || next == null || !old.equals(next)) {
            synchronized (LOCK) {
                previous.clear();
            }
        }
    }

    public static void clear() {
        context = null;
        clearRemoteSnapshot();
        synchronized (LOCK) {
            previous.clear();
            serverSnapshots.clear();
        }
    }

    /** 只清理本地单机文件上下文，保留远程服务器通过网络同步的快照。 */
    public static void clearLocalContext() {
        context = null;
        synchronized (LOCK) {
            previous.clear();
        }
    }

    /** 接收服务端同步的当前玩家快照；数据只保存在运行时内存。 */
    public static void applyRemoteSnapshot(String scopeKey, JsonObject snapshot) {
        if (scopeKey == null || scopeKey.trim().isEmpty() || snapshot == null
                || !isAvailable(snapshot)) {
            clearRemoteSnapshot();
            return;
        }
        context = null;
        remoteSnapshotScope = scopeKey.trim();
        remoteSnapshot = copy(snapshot);
        synchronized (LOCK) {
            previous.clear();
        }
    }

    /** 断开服务器或退出世界时清理远程玩家快照。 */
    public static void clearRemoteSnapshot() {
        remoteSnapshot = null;
        remoteSnapshotScope = "";
    }

    /** 返回 Worker runtime_context_response 需要的 JSON 对象。 */
    public static JsonObject readSnapshot() {
        Context current = context;
        if (current == null) {
            JsonObject remote = remoteSnapshot;
            return remote == null ? null : copy(remote);
        }
        return readSnapshot(current);
    }

    /** 服务端或纯 Java 测试使用的无全局上下文读取入口。 */
    public static JsonObject readSnapshot(Path worldRoot, String playerUuid, String playerName) {
        Context current = createContext(worldRoot, playerUuid, playerName);
        return current == null ? null : readSnapshot(current);
    }

    /** 进入世界时捕获一次服务端快照，并缓存到当前 JVM 内存。 */
    public static JsonObject captureServerSnapshot(
            Path worldRoot,
            String playerUuid,
            String playerName
    ) {
        Context current = createContext(worldRoot, playerUuid, playerName);
        if (current == null) {
            return null;
        }
        JsonObject snapshot = readSnapshot(current);
        if (snapshot == null) {
            return null;
        }
        String scope = scopeKey(current);
        synchronized (LOCK) {
            serverSnapshots.put(scope, copy(snapshot));
        }
        return copy(snapshot);
    }

    /**
     * 任务完成事件的增量入口。只更新内存中的 completed ID 和 timeline，
     * 不重新遍历任务定义，也不写入存档或 knowledge.db。
     */
    public static JsonObject addServerCompletion(
            Path worldRoot,
            String playerUuid,
            String playerName,
            String questId,
            long timestampEpochMillis
    ) {
        Context current = createContext(worldRoot, playerUuid, playerName);
        String normalized = normalizeId(questId);
        if (current == null || normalized.isEmpty()) {
            return null;
        }
        String scope = scopeKey(current);
        JsonObject base;
        synchronized (LOCK) {
            base = serverSnapshots.get(scope);
        }
        if (base == null) {
            base = readSnapshot(current);
        }
        if (base == null) {
            base = new JsonObject();
            base.addProperty("source_key", "ftbquests:legacy:server-event");
            base.addProperty("scope_key", scope);
            base.addProperty("version", "event");
            base.add("started_quest_ids", new JsonArray());
            base.add("task_progress", new JsonObject());
            base.add("timeline", new JsonArray());
        }
        JsonObject updated = appendCompletion(base, normalized, timestampEpochMillis);
        synchronized (LOCK) {
            serverSnapshots.put(scope, copy(updated));
        }
        return copy(updated);
    }

    /**
     * 将服务端运行时已经完成的任务合并进首次快照；这是进入世界时的状态同步，
     * 不生成“刚刚完成”的时间线事件。
     */
    public static JsonObject mergeServerCompletions(
            Path worldRoot,
            String playerUuid,
            String playerName,
            Collection<String> questIds
    ) {
        Context current = createContext(worldRoot, playerUuid, playerName);
        if (current == null || questIds == null || questIds.isEmpty()) {
            return readServerSnapshot(current);
        }
        String scope = scopeKey(current);
        JsonObject base = readServerSnapshot(current);
        if (base == null) {
            base = new JsonObject();
            base.addProperty("source_key", "ftbquests:legacy:server-runtime");
            base.addProperty("scope_key", scope);
            base.addProperty("version", "runtime");
            base.add("started_quest_ids", new JsonArray());
            base.add("task_progress", new JsonObject());
            base.add("timeline", new JsonArray());
        }
        JsonObject updated = copy(base);
        JsonArray completed = arrayOrEmpty(updated, "completed_quest_ids");
        for (String questId : questIds) {
            String normalized = normalizeId(questId);
            if (!normalized.isEmpty() && !containsString(completed, normalized)) {
                completed.add(normalized);
            }
        }
        updated.add("completed_quest_ids", completed);
        updated.addProperty("available", true);
        updated.addProperty("snapshot_mode", "initial_runtime");
        synchronized (LOCK) {
            serverSnapshots.put(scope, copy(updated));
        }
        return copy(updated);
    }

    public static void clearServerSnapshot(Path worldRoot, String playerUuid) {
        Context current = createContext(worldRoot, playerUuid, "");
        if (current != null) {
            synchronized (LOCK) {
                serverSnapshots.remove(scopeKey(current));
            }
        }
    }

    public static void clearServerSnapshots() {
        synchronized (LOCK) {
            serverSnapshots.clear();
        }
    }

    private static JsonObject readServerSnapshot(Context current) {
        if (current == null) {
            return null;
        }
        synchronized (LOCK) {
            JsonObject cached = serverSnapshots.get(scopeKey(current));
            if (cached != null) {
                return copy(cached);
            }
        }
        JsonObject snapshot = readSnapshot(current);
        if (snapshot != null) {
            synchronized (LOCK) {
                serverSnapshots.put(scopeKey(current), copy(snapshot));
            }
        }
        return copy(snapshot);
    }

    private static JsonObject readSnapshot(Context current) {
        for (Path file : candidateFiles(current)) {
            try {
                byte[] bytes = readStable(file);
                if (bytes == null) {
                    continue;
                }
                Map<String, Object> root = parseRoot(bytes);
                if (!root.isEmpty()) {
                    return toJson(current, file, root);
                }
            } catch (IOException | RuntimeException ignored) {
                // 文件可能正在被 FTBQ 原子替换；当前请求继续尝试下一个候选。
            }
        }
        return null;
    }

    private static Context createContext(Path worldRoot, String playerUuid, String playerName) {
        return worldRoot == null || playerUuid == null || playerUuid.trim().isEmpty()
                ? null : new Context(
                worldRoot.toAbsolutePath().normalize(),
                playerUuid.trim(),
                playerName == null ? "" : playerName.trim()
        );
    }

    private static List<Path> candidateFiles(Context current) {
        Path world = current.worldRoot;
        Path teamRoot = world.resolve("data/ftb_lib/teams").normalize();
        Path playerRoot = world.resolve("data/ftb_lib/players").normalize();
        LinkedHashSet<String> teamIds = new LinkedHashSet<String>();
        List<Path> result = new ArrayList<Path>();

        // ForgePlayer 的文件名是玩家名小写；如果启动器没有提供名称，再扫描
        // 很小的 players 目录并用 UUID 字段确认，避免把别人的团队进度读进来。
        if (!current.playerName.isEmpty()) {
            Path named = playerRoot.resolve(current.playerName.toLowerCase(Locale.ROOT) + ".dat");
            addTeamFromPlayer(named, current, teamIds);
        }
        if (Files.isDirectory(playerRoot)) {
            try (Stream<Path> stream = Files.list(playerRoot)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(LegacyFtbQuestRuntimeReader::isDataFile)
                        .sorted().collect(Collectors.toList())) {
                    if (teamIds.isEmpty()) {
                        addTeamFromPlayer(file, current, teamIds);
                    }
                }
            } catch (IOException ignored) {
                // 回退到单人团队和旧版路径。
            }
        }

        // 单机 FTB Library 通常使用 singleplayer；有玩家映射时它只作为最后回退。
        teamIds.add("singleplayer");
        for (String teamId : teamIds) {
            if (!safeComponent(teamId)) {
                continue;
            }
            result.add(teamRoot.resolve(teamId).resolve("ftbquests.dat").normalize());
            result.add(teamRoot.resolve(teamId + ".ftbquests.dat").normalize());
        }

        // 兼容较早或被整合包改名的 FTBQ 目录；正常 1.12.2 存档优先走上面的
        // 二进制 NBT 路径，不会读取整个世界目录。
        Path legacyRoot = world.resolve("ftbquests").normalize();
        result.add(legacyRoot.resolve(current.playerUuid + ".snbt").normalize());
        if (Files.isDirectory(legacyRoot)) {
            try (Stream<Path> stream = Files.list(legacyRoot)) {
                result.addAll(stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".snbt"))
                        .sorted().collect(Collectors.toList()));
            } catch (IOException ignored) {
                // 目录消失时保持已知候选。
            }
        }
        return result.stream()
                .filter(path -> path.normalize().startsWith(world))
                .distinct()
                .collect(Collectors.toList());
    }

    private static void addTeamFromPlayer(Path file, Context current, Set<String> teamIds) {
        try {
            byte[] bytes = readStable(file);
            if (bytes == null) {
                return;
            }
            Map<String, Object> player = parseRoot(bytes);
            String uuid = text(first(player, "UUID", "uuid", "PlayerUUID"));
            if (!uuid.isEmpty() && !current.playerUuid.equalsIgnoreCase(uuid)) {
                return;
            }
            String team = text(first(player, "TeamID", "team_id", "team", "Team"));
            if (!team.isEmpty()) {
                teamIds.add(team);
            }
        } catch (IOException | RuntimeException ignored) {
            // 单个玩家文件损坏不影响其它候选。
        }
    }

    private static JsonObject toJson(Context current, Path file, Map<String, Object> root) {
        String scope = scopeKey(current);
        Map<String, Object> started = mapOrEmpty(first(root, "started", "Started"));
        Map<String, Object> completed = mapOrEmpty(first(root, "completed", "Completed"));
        Map<String, Double> progress = progress(root);
        JsonObject result = new JsonObject();
        result.addProperty("available", true);
        result.addProperty("source_key", "ftbquests:legacy:" + relativeOrName(current.worldRoot, file));
        result.addProperty("scope_key", scope);
        result.addProperty("version", version(file));
        result.add("started_quest_ids", keys(started));
        result.add("completed_quest_ids", keys(completed));
        JsonObject progressObject = new JsonObject();
        for (Map.Entry<String, Double> entry : progress.entrySet()) {
            progressObject.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("task_progress", progressObject);
        result.addProperty("progress_source", root.containsKey("Tasks") || root.containsKey("tasks")
                ? "ftb_lib_team_tasks" : "legacy_snapshot");
        result.add("timeline", timeline(scope, started, completed, progress));
        return result;
    }

    private static String scopeKey(Context current) {
        return "player:" + current.playerUuid + "@world:" + shortHash(current.worldRoot.toString());
    }

    private static JsonObject appendCompletion(JsonObject base, String questId, long timestamp) {
        JsonObject result = copy(base);
        result.addProperty("available", true);

        JsonArray completed = arrayOrEmpty(result, "completed_quest_ids");
        if (!containsString(completed, questId)) {
            completed.add(questId);
        }
        result.add("completed_quest_ids", completed);

        JsonArray timeline = arrayOrEmpty(result, "timeline");
        if (!containsCompletedEvent(timeline, questId)) {
            timeline.add(event(questId, "COMPLETED", timestamp, null, 1D));
        }
        result.add("timeline", timeline);
        result.addProperty("snapshot_mode", "incremental_event");
        result.addProperty("last_event_epoch_ms", timestamp);
        return result;
    }

    private static JsonArray arrayOrEmpty(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static boolean containsString(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value != null && value.isJsonPrimitive()
                    && expected.equals(normalizeId(value.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCompletedEvent(JsonArray values, String questId) {
        for (JsonElement value : values) {
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            JsonElement type = object.get("event_type");
            JsonElement id = object.has("quest_id") ? object.get("quest_id") : object.get("entry_id");
            if (type != null && id != null && "COMPLETED".equalsIgnoreCase(type.getAsString())
                    && questId.equals(normalizeId(id.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAvailable(JsonObject snapshot) {
        JsonElement value = snapshot.get("available");
        return value == null || !value.isJsonPrimitive() || value.getAsBoolean();
    }

    private static JsonObject copy(JsonObject value) {
        if (value == null) {
            return null;
        }
        try {
            return new JsonParser().parse(value.toString()).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static JsonArray keys(Map<String, Object> object) {
        JsonArray result = new JsonArray();
        for (String key : object.keySet()) {
            String normalized = normalizeId(key);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static Map<String, Double> progress(Map<String, Object> root) {
        Object value = first(root, "task_progress", "taskProgress", "Tasks", "tasks");
        Map<String, Object> values = mapOrEmpty(value);
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            double number = LegacySnbtParser.number(entry.getValue(), Double.NaN);
            if (!Double.isNaN(number) && !Double.isInfinite(number)) {
                String id = normalizeId(entry.getKey());
                if (!id.isEmpty()) {
                    result.put(id, number);
                }
            }
        }
        return result;
    }

    private static JsonArray timeline(
            String scope,
            Map<String, Object> started,
            Map<String, Object> completed,
            Map<String, Double> progress
    ) {
        long now = System.currentTimeMillis();
        SnapshotState current = new SnapshotState(
                new LinkedHashSet<String>(normalizedKeys(started)),
                new LinkedHashSet<String>(normalizedKeys(completed)),
                new LinkedHashMap<String, Double>(progress)
        );
        SnapshotState old;
        synchronized (LOCK) {
            old = previous.put(scope, current);
        }
        JsonArray result = new JsonArray();
        if (old == null) {
            return result;
        }
        for (String id : current.completed) {
            if (!old.completed.contains(id)) {
                result.add(event(id, "COMPLETED", now, null, 1D));
            }
        }
        for (String id : current.started) {
            if (!old.started.contains(id)) {
                result.add(event(id, "STARTED", now, null, 1D));
            }
        }
        for (Map.Entry<String, Double> entry : current.progress.entrySet()) {
            Double before = old.progress.get(entry.getKey());
            if (before == null || Double.compare(before, entry.getValue()) != 0) {
                result.add(event(entry.getKey(), "PROGRESS", now, before, entry.getValue()));
            }
        }
        return result;
    }

    private static List<String> normalizedKeys(Map<String, Object> values) {
        List<String> result = new ArrayList<String>();
        for (String key : values.keySet()) {
            String value = normalizeId(key);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static JsonObject event(String id, String type, long timestamp, Double before, Double after) {
        JsonObject event = new JsonObject();
        event.addProperty("entry_id", id);
        event.addProperty("quest_id", id);
        event.addProperty("event_type", type);
        event.addProperty("timestamp_epoch_ms", timestamp);
        if (before != null) {
            event.addProperty("previous_progress", before);
        }
        if (after != null) {
            event.addProperty("current_progress", after);
        }
        return event;
    }

    private static Map<String, Object> parseRoot(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            return LegacySnbtParser.compound(LegacySnbtParser.parse(text));
        }
        NBTTagCompound compound;
        try {
            compound = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
        } catch (IOException compressedFailure) {
            compound = CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        }
        return compound(compound);
    }

    private static Map<String, Object> compound(NBTTagCompound value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value == null) {
            return result;
        }
        for (String key : value.getKeySet()) {
            result.put(key, plain(value.getTag(key)));
        }
        return result;
    }

    private static Object plain(NBTBase value) {
        if (value instanceof NBTTagCompound) {
            return compound((NBTTagCompound) value);
        }
        if (value instanceof NBTTagList) {
            List<Object> result = new ArrayList<Object>();
            NBTTagList list = (NBTTagList) value;
            for (int index = 0; index < list.tagCount(); index++) {
                result.add(plain(list.get(index)));
            }
            return result;
        }
        if (value instanceof NBTTagString) {
            return ((NBTTagString) value).getString();
        }
        if (value instanceof NBTPrimitive) {
            return ((NBTPrimitive) value).getLong();
        }
        return value == null ? "" : value.toString();
    }

    private static byte[] readStable(Path file) throws IOException {
        for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            FileStamp before = stamp(file);
            if (before == null || before.size > MAX_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) {
                return null;
            }
            FileStamp after = stamp(file);
            if (before.equals(after)) {
                return bytes;
            }
        }
        return null;
    }

    private static FileStamp stamp(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.isRegularFile()
                    ? new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
                    : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map ? (Map<String, Object>) value
                : new LinkedHashMap<String, Object>();
    }

    private static String text(Object value) {
        return LegacySnbtParser.text(value).trim();
    }

    private static long version(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String relativeOrName(Path world, Path file) {
        try {
            return world.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException exception) {
            return file.getFileName().toString();
        }
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isDataFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".dat") || name.endsWith(".snbt");
    }

    private static boolean safeComponent(String value) {
        return value != null && !value.isEmpty() && !".".equals(value) && !"..".equals(value)
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static String shortHash(String value) {
        return Integer.toHexString(value == null ? 0 : value.hashCode());
    }

    private static final class FileStamp {
        private final long size;
        private final FileTime modifiedTime;
        private final Object fileKey;

        private FileStamp(long size, FileTime modifiedTime, Object fileKey) {
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.fileKey = fileKey;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileStamp)) {
                return false;
            }
            FileStamp value = (FileStamp) other;
            return size == value.size && modifiedTime.equals(value.modifiedTime)
                    && (fileKey == null ? value.fileKey == null : fileKey.equals(value.fileKey));
        }

        @Override
        public int hashCode() {
            int result = Long.valueOf(size).hashCode();
            result = 31 * result + modifiedTime.hashCode();
            return 31 * result + (fileKey == null ? 0 : fileKey.hashCode());
        }
    }

    private static final class Context {
        private final Path worldRoot;
        private final String playerUuid;
        private final String playerName;

        private Context(Path worldRoot, String playerUuid, String playerName) {
            this.worldRoot = worldRoot;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Context)) {
                return false;
            }
            Context value = (Context) other;
            return worldRoot.equals(value.worldRoot) && playerUuid.equals(value.playerUuid)
                    && playerName.equals(value.playerName);
        }

        @Override
        public int hashCode() {
            int result = 31 * worldRoot.hashCode() + playerUuid.hashCode();
            return 31 * result + playerName.hashCode();
        }
    }

    private static final class SnapshotState {
        private final Set<String> started;
        private final Set<String> completed;
        private final Map<String, Double> progress;

        private SnapshotState(Set<String> started, Set<String> completed, Map<String, Double> progress) {
            this.started = started;
            this.completed = completed;
            this.progress = progress;
        }
    }
}
