package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskRuntimeReadResult;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeReader;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.FtbQuestIdCodec;
import io.ctyx.modpedia.task.CompletedQuestSnapshot;
import io.ctyx.modpedia.task.TaskRuntimeScope;
import io.ctyx.modpedia.task.TaskTimelineEntry;
import io.ctyx.modpedia.task.TaskTimelineEventType;
import io.ctyx.modpedia.task.TaskTimelineTracker;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 可选任务模组的客户端适配器。
 *
 * <p>这里不再轮询任务模组。FTB Quests 的静态任务定义由知识库启动流程保存；
 * 进入世界时只读取一次当前存档的 completed 任务 ID，后续由 FTBQ 的 Quest 完成
 * 事件增量更新这个存档级快照，并保留 FTBQ 的完成时间。只有 AI 明确调用
 * {@code search_tasks} 时，适配器才读取当前玩家 TeamData 中的 started 索引和局部进度。
 * 单机优先只暴露存档路径
 * 给 Worker，多人或本地文件不可用时才读取 TeamData；每个 AI 请求只允许一次
 * 运行时读取。</p>
 *
 * <p>任务模组不进入 ModPedia 的硬依赖。所有入口都通过反射调用，读取失败时
 * 保留静态任务定义并报告未同步状态，不影响助手和 Dedicated Server 加载。</p>
 */
public final class FtbQuestsClientAdapter implements TaskRuntimeReader {
    private static final String MOD_ID = "ftbquests";
    // 不按 TeamData Map 的迭代顺序截断状态；查询级 quest_id 过滤在读取时执行。
    private static final long CLIENT_THREAD_TIMEOUT_SECONDS = 5L;
    private static final long RUNTIME_SNAPSHOT_CACHE_MILLIS = 750L;
    private static final long COMPLETED_SNAPSHOT_RETRY_MILLIS = 1000L;
    private final Object completionListenerLock = new Object();
    private boolean warnedUnavailable;
    private boolean warnedCompletionListener;
    private volatile String cachedScopeKey = "";
    private volatile long cachedAtNanos;
    private volatile TaskRuntimeReadResult cachedResult;
    private volatile Object observedWorldLevel;
    private volatile Object observedWorldPlayer;
    private volatile String observedWorldSaveKey = "";
    private volatile long nextCompletedSnapshotAttemptNanos;
    private volatile CompletedQuestSnapshot completedQuestSnapshot;
    private volatile boolean completedSnapshotInitialised;
    /** 只用于比较两次按需读取的 TeamData；不会落盘。 */
    private final TaskTimelineTracker progressTimelineTracker = new TaskTimelineTracker();
    private volatile Object completedEvent;
    private volatile Object completedEventListener;

    public FtbQuestsClientAdapter() {
    }

    /**
     * 在所有客户端模组完成加载后注册 FTB Quests 的任务完成事件。
     *
     * <p>FTB Quests 使用 Architectury Event，且属于可选依赖，因此这里不直接
     * 引用事件类，而是反射注册 {@code ObjectCompletedEvent.QUEST}。监听器只做
     * 一件事：把当前存档作用域中的已完成任务 ID 增量加入内存快照。</p>
     */
    public void registerCompletionListener() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        synchronized (completionListenerLock) {
            if (completedEventListener != null) {
                return;
            }
            try {
                Class<?> eventType = Class.forName(
                        "dev.ftb.mods.ftbquests.events.ObjectCompletedEvent"
                );
                Field questField = eventType.getField("QUEST");
                Object event = questField.get(null);
                Class<?> actorType = Class.forName("dev.architectury.event.EventActor");
                Class<?> eventResultType = Class.forName("dev.architectury.event.EventResult");
                Method pass = eventResultType.getMethod("pass");
                ClassLoader loader = actorType.getClassLoader();
                if (loader == null) {
                    loader = FtbQuestsClientAdapter.class.getClassLoader();
                }
                Object listener = Proxy.newProxyInstance(
                        loader,
                        new Class<?>[]{actorType},
                        (proxy, method, arguments) -> {
                            if ("act".equals(method.getName())
                                    && arguments != null
                                    && arguments.length == 1) {
                                onQuestCompletedEvent(arguments[0]);
                                return pass.invoke(null);
                            }
                            if ("toString".equals(method.getName())) {
                                return "ModPediaFtbQuestCompletionListener";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxy == arguments[0];
                            }
                            return null;
                        }
                );
                invoke(event, "register", listener);
                completedEvent = event;
                completedEventListener = listener;
                ModPedia.LOGGER.info("已注册 FTB Quests 任务完成快照监听");
            } catch (Throwable failure) {
                if (!warnedCompletionListener) {
                    warnedCompletionListener = true;
                    ModPedia.LOGGER.debug("注册 FTB Quests 任务完成监听失败，继续使用进入世界快照", failure);
                }
            }
        }
    }

    /** 退出游戏时解除 Architectury listener，避免可选模组保留客户端适配器引用。 */
    public void unregisterCompletionListener() {
        synchronized (completionListenerLock) {
            Object event = completedEvent;
            Object listener = completedEventListener;
            completedEvent = null;
            completedEventListener = null;
            if (event == null || listener == null) {
                return;
            }
            try {
                invoke(event, "unregister", listener);
            } catch (Throwable failure) {
                ModPedia.LOGGER.debug("解除 FTB Quests 任务完成监听失败", failure);
            }
        }
    }

    /**
     * ClientTick 中调用。进入新世界时读取一次已完成任务及其时间；后续 tick 只在 FTBQ
     * 客户端数据尚未同步完成时按秒重试，不会持续扫描 TeamData。
     */
    public void observeWorld(Minecraft minecraft) {
        if (!ModList.get().isLoaded(MOD_ID)
                || minecraft == null
                || minecraft.level == null
                || minecraft.player == null) {
            if (observedWorldLevel != null
                    || observedWorldPlayer != null
                    || !observedWorldSaveKey.isBlank()) {
                observedWorldLevel = null;
                observedWorldPlayer = null;
                observedWorldSaveKey = "";
                clearRuntimeCache();
            }
            return;
        }
        boolean levelObjectChanged = observedWorldLevel != minecraft.level;
        boolean playerChanged = observedWorldPlayer != minecraft.player;
        String currentWorldSaveKey = observedWorldSaveKey;
        if (levelObjectChanged || currentWorldSaveKey.isBlank()) {
            currentWorldSaveKey = worldSaveKey(minecraft);
        }
        boolean saveChanged = !currentWorldSaveKey.equals(observedWorldSaveKey);
        // 当世界身份只能降级到 session 时，Level 对象变化仍是区分两个存档的
        // 最后手段；拥有稳定存档/服务器身份时，维度切换只会更换 Level 对象，
        // 不应清空同一份任务快照。
        boolean unknownSessionChanged = "session".equals(currentWorldSaveKey)
                && levelObjectChanged
                && !observedWorldSaveKey.isBlank();
        observedWorldLevel = minecraft.level;
        observedWorldPlayer = minecraft.player;
        observedWorldSaveKey = currentWorldSaveKey;
        if (playerChanged || saveChanged || unknownSessionChanged) {
            completedQuestSnapshot = null;
            completedSnapshotInitialised = false;
            nextCompletedSnapshotAttemptNanos = 0L;
            clearRuntimeQueryCache();
        }
        if (!completedSnapshotInitialised && System.nanoTime() >= nextCompletedSnapshotAttemptNanos) {
            captureCompletedSnapshot(minecraft);
        }
    }

    /** 返回当前存档的已完成任务快照，不触碰 SQLite，也不重新读取任务文件。 */
    public TaskRuntimeSnapshot completedSnapshot(TaskQuery query) {
        CompletedQuestSnapshot snapshot = completedQuestSnapshot;
        return snapshot == null ? null : snapshot.runtimeSnapshot(query);
    }

    /**
     * 由 AI 工具调用的入口。工具通常运行在 AI 工作线程，因此实际 API 读取会
     * 切回 Minecraft 客户端线程；这里只读取实时 started 索引和局部进度，并复用
     * 当前存档的 completed 快照，SQLite 查询由后续任务存储只读完成，不在此处
     * 写入数据库。
     */
    @Override
    public TaskRuntimeReadResult readForQuery(TaskQuery query, String requestKey) {
        Minecraft minecraft = Minecraft.getInstance();
        return readForQuery(minecraft, query, requestKey);
    }

    /** 供客户端集成测试和需要显式 Minecraft 实例的调用方使用。 */
    public TaskRuntimeReadResult readForQuery(
            Minecraft minecraft,
            TaskQuery query,
            String requestKey
    ) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return TaskRuntimeReadResult.unavailable("FTB Quests 未安装，任务运行时读取已跳过");
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return TaskRuntimeReadResult.unavailable("当前不在游戏世界中，暂无任务进度");
        }

        TaskRuntimeReadResult cached = cachedResult(minecraft);
        if (cached != null) {
            return TaskRuntimeReadResult.cached(cached);
        }

        TaskQuery actual = query == null ? TaskQuery.search("") : query;
        TaskRuntimeReadResult result;
        try {
            TaskRuntimeSnapshot snapshot = readOnClientThread(minecraft, actual);
            if (snapshot == null) {
                result = new TaskRuntimeReadResult(true, true, 0, "没有读取到当前玩家的任务进度", snapshot);
            } else {
                result = TaskRuntimeReadResult.read(snapshot);
                ModPedia.LOGGER.info(
                        "任务运行时按需读取完成：started={}, completed={}, progress={}, scope={}, request={}",
                        snapshot.startedQuestIds().size(),
                        snapshot.completedQuestIds().size(),
                        snapshot.taskProgress().size(),
                        snapshot.scopeKey(),
                        requestKey
                );
                cacheResult(minecraft, result);
            }
        } catch (Throwable failure) {
            result = TaskRuntimeReadResult.unavailable(messageOf(failure));
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                ModPedia.LOGGER.warn(
                        "按需读取可选任务模组进度失败，继续使用旧任务数据：{}",
                        result.message()
                );
            }
        }
        return result;
    }

    /** 世界切换和退出时调用，主动丢弃游戏侧的运行时缓存。 */
    public void clearRuntimeCache() {
        clearRuntimeQueryCache();
        completedQuestSnapshot = null;
        completedSnapshotInitialised = false;
        progressTimelineTracker.clear();
    }

    private void clearRuntimeQueryCache() {
        cachedScopeKey = "";
        cachedAtNanos = 0L;
        cachedResult = null;
    }

    private TaskRuntimeReadResult cachedResult(Minecraft minecraft) {
        TaskRuntimeReadResult value = cachedResult;
        String currentScopeKey = runtimeScope(minecraft);
        if (value == null
                || !currentScopeKey.equals(cachedScopeKey)
                || System.nanoTime() - cachedAtNanos
                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(RUNTIME_SNAPSHOT_CACHE_MILLIS)) {
            if (value != null && !currentScopeKey.equals(cachedScopeKey)) {
                clearRuntimeCache();
            }
            return null;
        }
        return value;
    }

    private void cacheResult(Minecraft minecraft, TaskRuntimeReadResult result) {
        if (result == null || !result.available() || !result.read()) {
            return;
        }
        cachedScopeKey = runtimeScope(minecraft);
        cachedAtNanos = System.nanoTime();
        cachedResult = result;
    }

    private void captureCompletedSnapshot(Minecraft minecraft) {
        nextCompletedSnapshotAttemptNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(COMPLETED_SNAPSHOT_RETRY_MILLIS);
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> captureCompletedSnapshot(minecraft));
            return;
        }
        try {
            TaskRuntimeSnapshot snapshot = readCompletedSnapshotOnClientThread(minecraft);
            if (snapshot == null) {
                return;
            }
            CompletedQuestSnapshot captured = new CompletedQuestSnapshot(
                    snapshot.sourceKey(),
                    snapshot.scopeKey(),
                    snapshot.version(),
                    snapshot.completedQuestIds(),
                    snapshot.timeline()
            );
            CompletedQuestSnapshot existing = completedQuestSnapshot;
            if (existing != null && existing.scopeKey().equals(captured.scopeKey())) {
                captured = captured.merge(existing);
            }
            completedQuestSnapshot = captured;
            completedSnapshotInitialised = true;
            ModPedia.LOGGER.info(
                    "进入世界已加载 FTB Quests 完成快照：completed={}, scope={}",
                    completedQuestSnapshot.completedQuestIds().size(),
                    completedQuestSnapshot.scopeKey()
            );
        } catch (Throwable failure) {
            ModPedia.LOGGER.debug("进入世界加载 FTB Quests 完成快照失败，稍后重试", failure);
        }
    }

    private void onQuestCompletedEvent(Object event) {
        Object quest = invokeOne(event, "getQuest");
        Object id = quest == null ? null : invokeOne(quest, "getId");
        if (id == null && quest != null) {
            try {
                id = readField(quest, "id");
            } catch (IllegalAccessException ignored) {
            }
        }
        String questId = FtbQuestIdCodec.fromRuntimeKey(id);
        if (questId.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null
                || !eventBelongsToCurrentPlayer(event, minecraft)) {
            return;
        }
        long timestamp = eventTimestamp(event);
        Runnable update = () -> addCompletedQuest(minecraft, questId, timestamp);
        if (minecraft.isSameThread()) {
            update.run();
        } else {
            minecraft.execute(update);
        }
    }

    private void addCompletedQuest(Minecraft minecraft, String questId) {
        addCompletedQuest(minecraft, questId, System.currentTimeMillis());
    }

    private void addCompletedQuest(Minecraft minecraft, String questId, long timestampEpochMillis) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        String scope = runtimeScope(minecraft);
        CompletedQuestSnapshot current = completedQuestSnapshot;
        if (current == null || !scope.equals(current.scopeKey())) {
            // 事件可能比客户端首次 tick 更早到达；先保留一个当前存档作用域，
            // 后续进入世界捕获成功时会以完整 TeamData 快照替换它。
            completedSnapshotInitialised = false;
            current = new CompletedQuestSnapshot(
                    "ftbquests:" + worldSaveKey(minecraft),
                    scope,
                    modVersion(),
                    List.of()
            );
        }
        CompletedQuestSnapshot updated = current.add(questId, timestampEpochMillis);
        if (updated != current) {
            completedQuestSnapshot = updated;
            clearRuntimeQueryCache();
            ModPedia.LOGGER.debug(
                    "FTB Quests 完成事件已更新存档快照：quest={}, completed={}, scope={}",
                    questId,
                    updated.completedQuestIds().size(),
                    updated.scopeKey()
            );
        }
    }

    private boolean eventBelongsToCurrentPlayer(Object event, Minecraft minecraft) {
        for (String methodName : List.of("getNotifiedPlayers", "getOnlineMembers")) {
            List<?> players = values(invokeOne(event, methodName));
            if (!players.isEmpty()) {
                UUID current = minecraft.player.getUUID();
                return players.stream().map(player -> invokeOne(player, "getUUID"))
                        .anyMatch(current::equals);
            }
        }
        return true;
    }

    /**
     * 描述单机存档中的 FTBQ 运行时文件，让 Worker 在本地直接读取小型 SNBT
     * 快照。这里只解析当前世界路径和玩家 UUID，不读取 TeamData，也不打开数据库。
     * 多人服务器没有本地存档根目录时返回空值，调用方继续使用 TeamData 回退。
     */
    public Optional<TaskRuntimeFileDescriptor> localFileDescriptor(TaskQuery query) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ModList.get().isLoaded(MOD_ID) || minecraft == null) {
            return Optional.empty();
        }
        if (minecraft.isSameThread()) {
            return localFileDescriptorOnClientThread(minecraft);
        }
        CompletableFuture<Optional<TaskRuntimeFileDescriptor>> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(localFileDescriptorOnClientThread(minecraft));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(CLIENT_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException exception) {
            return Optional.empty();
        }
    }

    private Optional<TaskRuntimeFileDescriptor> localFileDescriptorOnClientThread(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        try {
            Object server = invoke(minecraft, "getSingleplayerServer");
            if (server == null) {
                return Optional.empty();
            }
            Class<?> resourceType = Class.forName("net.minecraft.world.level.storage.LevelResource");
            Field rootField;
            try {
                rootField = resourceType.getField("ROOT");
            } catch (NoSuchFieldException ignored) {
                rootField = resourceType.getDeclaredField("ROOT");
                rootField.setAccessible(true);
            }
            Object rootResource = rootField.get(null);
            Object worldPath = invoke(server, "getWorldPath", rootResource);
            if (!(worldPath instanceof Path path)) {
                return Optional.empty();
            }
            String worldKey = worldSaveKey(minecraft);
            return Optional.of(new TaskRuntimeFileDescriptor(
                    path.toAbsolutePath().normalize().toString(),
                    minecraft.player.getUUID().toString(),
                    "ftbquests:" + worldKey,
                    runtimeScope(minecraft),
                    modVersion()
            ));
        } catch (Throwable failure) {
            ModPedia.LOGGER.debug("本地 FTBQ 存档路径不可用，回退 TeamData", failure);
            return Optional.empty();
        }
    }

    private TaskRuntimeSnapshot readOnClientThread(Minecraft minecraft, TaskQuery query) throws Exception {
        if (minecraft.isSameThread()) {
            return readSnapshotForQuery(minecraft, query);
        }
        CompletableFuture<TaskRuntimeSnapshot> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(readSnapshotForQuery(minecraft, query));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(CLIENT_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待客户端线程读取任务进度时被中断", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("等待客户端线程读取任务进度超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause == null ? "客户端任务读取失败" : cause.getMessage(), cause);
        }
    }

    private TaskRuntimeSnapshot readCompletedSnapshotOnClientThread(Minecraft minecraft) throws Exception {
        Class<?> apiClass = Class.forName("dev.ftb.mods.ftbquests.api.FTBQuestsAPI");
        Object api = invokeStatic(apiClass, "api");
        Object questFile = invoke(api, "getQuestFile", true);
        if (questFile == null) {
            return null;
        }
        Object teamData = findTeamData(questFile, minecraft.player.getUUID(), minecraft.player);
        if (teamData == null) {
            return null;
        }
        return new TaskRuntimeSnapshot(
                "ftbquests:" + worldSaveKey(minecraft),
                runtimeScope(minecraft),
                modVersion(),
                List.of(),
                mapKeys(teamData, "completed", null),
                Map.of(),
                mapTimeline(teamData, "completed", TaskTimelineEventType.COMPLETED, null)
        );
    }

    /**
     * 使用已完成任务快照，实时读取 started 和 taskProgress，再由任务存储使用
     * 这些 ID 查询静态定义。不遍历 QuestFile，也不解析所有任务对象和要求。
     */
    private TaskRuntimeSnapshot readSnapshotForQuery(Minecraft minecraft, TaskQuery query) throws Exception {
        Class<?> apiClass = Class.forName("dev.ftb.mods.ftbquests.api.FTBQuestsAPI");
        Object api = invokeStatic(apiClass, "api");
        Object questFile = invoke(api, "getQuestFile", true);
        if (questFile == null) {
            return null;
        }

        Object teamData = findTeamData(questFile, minecraft.player.getUUID(), minecraft.player);
        String sourceKey = "ftbquests:" + worldSaveKey(minecraft);
        String scopeKey = runtimeScope(minecraft);
        CompletedQuestSnapshot completed = completedQuestSnapshot;
        if (completed == null || !scopeKey.equals(completed.scopeKey()) || !completedSnapshotInitialised) {
            CompletedQuestSnapshot captured = new CompletedQuestSnapshot(
                    sourceKey,
                    scopeKey,
                    modVersion(),
                    mapKeys(teamData, "completed", null),
                    mapTimeline(teamData, "completed", TaskTimelineEventType.COMPLETED, null)
            );
            if (completed != null && scopeKey.equals(completed.scopeKey())) {
                captured = captured.merge(completed);
            }
            completed = captured;
            completedQuestSnapshot = completed;
            completedSnapshotInitialised = true;
        }
        Map<String, Double> progress = taskProgress(teamData);
        List<TaskTimelineEntry> timeline = new ArrayList<>();
        timeline.addAll(mapTimeline(teamData, "started", TaskTimelineEventType.STARTED, query));
        timeline.addAll(completed.timeline());
        timeline.addAll(detectProgressChanges(scopeKey, progress, query));
        return new TaskRuntimeSnapshot(
                sourceKey,
                scopeKey,
                modVersion(),
                mapKeys(teamData, "started", query),
                completed.idsFor(query),
                progress,
                timeline
        );
    }

    private List<String> mapKeys(Object teamData, String fieldName, TaskQuery query) {
        if (teamData == null) {
            return List.of();
        }
        try {
            Field field = findField(teamData.getClass(), fieldName);
            if (field == null) {
                return List.of();
            }
            field.setAccessible(true);
            Object map = field.get(teamData);
            Object keySet = invoke(map, "keySet");
            String requestedQuestId = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
            List<String> result = new ArrayList<>();
            for (Object key : values(keySet)) {
                String value = FtbQuestIdCodec.fromRuntimeKey(key);
                if (!value.isBlank() && (requestedQuestId.isBlank() || requestedQuestId.equals(value))) {
                    result.add(value);
                }
            }
            return result.stream().distinct().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Double> taskProgress(Object teamData) {
        if (teamData == null) {
            return Map.of();
        }
        try {
            Field field = findField(teamData.getClass(), "taskProgress");
            if (field == null) {
                return Map.of();
            }
            field.setAccessible(true);
            Object map = field.get(teamData);
            Object keySet = invoke(map, "keySet");
            Map<String, Double> result = new java.util.LinkedHashMap<>();
            for (Object key : values(keySet)) {
                String taskId = FtbQuestIdCodec.fromRuntimeKey(key);
                if (taskId.isBlank()) {
                    continue;
                }
                Object value = invoke(map, "get", key);
                if (value == null) {
                    value = invoke(map, "getLong", key);
                }
                result.put(taskId, number(value, 0));
            }
            return Map.copyOf(result);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<TaskTimelineEntry> mapTimeline(
            Object teamData,
            String fieldName,
            TaskTimelineEventType eventType,
            TaskQuery query
    ) {
        if (teamData == null) {
            return List.of();
        }
        try {
            Field field = findField(teamData.getClass(), fieldName);
            if (field == null) {
                return List.of();
            }
            field.setAccessible(true);
            Object map = field.get(teamData);
            Object keySet = invoke(map, "keySet");
            String requestedQuestId = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
            List<TaskTimelineEntry> result = new ArrayList<>();
            for (Object key : values(keySet)) {
                String questId = FtbQuestIdCodec.fromRuntimeKey(key);
                if (questId.isBlank()
                        || !requestedQuestId.isBlank() && !requestedQuestId.equals(questId)) {
                    continue;
                }
                Object timestampValue = invoke(map, "getLong", key);
                if (timestampValue == null) {
                    timestampValue = invoke(map, "get", key);
                }
                result.add(new TaskTimelineEntry(
                        questId,
                        eventType,
                        timestamp(timestampValue),
                        null,
                        eventType == TaskTimelineEventType.COMPLETED ? 1D : null
                ));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** TeamData 没有进度变化历史；仅在两次查询的内存快照之间检测变化。 */
    private List<TaskTimelineEntry> detectProgressChanges(
            String scopeKey,
            Map<String, Double> current,
            TaskQuery query
    ) {
        String requestedQuestId = query == null ? "" : FtbQuestIdCodec.fromRuntimeKey(query.questId());
        List<TaskTimelineEntry> changes = progressTimelineTracker.detect(scopeKey, current);
        List<TaskTimelineEntry> result = new ArrayList<>();
        for (TaskTimelineEntry change : changes) {
            String taskId = change.questId();
            if (!requestedQuestId.isBlank() && !requestedQuestId.equals(taskId)) {
                // task_progress 的 key 是 task ID，不是 quest ID；精确 quest 查询时
                // 不猜测父任务，避免把不确定的进度事件展示到错误任务下。
                continue;
            }
            result.add(change);
        }
        return result;
    }

    private long eventTimestamp(Object event) {
        Object value = invokeOne(event, "getTime");
        long timestamp = timestamp(value);
        return timestamp > 0L ? timestamp : System.currentTimeMillis();
    }

    private long timestamp(Object value) {
        if (value instanceof java.util.Date date) {
            return Math.max(0L, date.getTime());
        }
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private Object findTeamData(Object questFile, UUID playerId, Object player) {
        for (String name : List.of("getTeamData", "getNullableTeamData", "getData", "getPlayerData")) {
            Object value = invokeOne(questFile, name, player);
            if (value == null) {
                value = invokeOne(questFile, name, playerId);
            }
            value = unwrapOptional(value);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private String modVersion() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(container -> String.valueOf(container.getModInfo().getVersion()))
                    .orElse("unknown");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private String runtimeScope(Minecraft minecraft) {
        return TaskRuntimeScope.forPlayerAndWorld(
                minecraft.player.getUUID().toString(),
                worldSaveKey(minecraft)
        );
    }

    /**
     * 返回任务进度所属的世界/服务器身份。
     *
     * <p>任务进度由世界存档中的 TeamData 共享，跨主世界、下界和末地保持一致，
     * 因此这里刻意不包含维度。维度只属于当前查询上下文，不属于任务快照作用域。</p>
     */
    private String worldSaveKey(Minecraft minecraft) {
        // 优先用当前单机服务端的实际存档根路径建立稳定指纹；路径本身不经 IPC
        // 发送，只留下不可逆的短标识。多人环境再退回服务器地址。
        try {
            Object server = invoke(minecraft, "getSingleplayerServer");
            if (server != null) {
                Class<?> resourceType = Class.forName("net.minecraft.world.level.storage.LevelResource");
                Field rootField;
                try {
                    rootField = resourceType.getField("ROOT");
                } catch (NoSuchFieldException ignored) {
                    rootField = resourceType.getDeclaredField("ROOT");
                    rootField.setAccessible(true);
                }
                Object rootResource = rootField.get(null);
                Object worldPath = invoke(server, "getWorldPath", rootResource);
                if (worldPath instanceof Path path) {
                    return "local:" + stableId(path.toAbsolutePath().normalize().toString());
                }
            }
        } catch (Throwable ignored) {
            // 启动/退出窗口中服务端对象可能暂时不可用。
        }
        try {
            Object serverData = invoke(minecraft, "getCurrentServer");
            // ServerData.ip 在 1.21.1 是字段而不是无参方法；此前通过 invoke
            // 读取会稳定得到空字符串，多人服务器因此退回临时会话作用域。
            // 保留方法回退以兼容少数映射/版本差异，但字段优先。
            Object addressValue = readField(serverData, "ip");
            if (addressValue == null) {
                addressValue = invoke(serverData, "ip");
            }
            String address = text(addressValue);
            if (!address.isBlank()) {
                return "server:" + stableId(address);
            }
        } catch (Throwable ignored) {
            // 继续使用临时会话作用域作为最后的兼容回退。
        }
        return "session";
    }

    private String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private Object invokeStatic(Class<?> type, String name, Object... arguments) throws Exception {
        Method method = findCompatibleMethod(type, name, arguments);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private Object invoke(Object target, String name, Object... arguments) throws Exception {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, arguments);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private Object invokeOne(Object target, String name, Object argument) {
        try {
            return invoke(target, name, argument);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeOne(Object target, String name) {
        try {
            return invoke(target, name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::text).filter(item -> !item.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("").strip();
        }
        try {
            Method getString = findMethod(value.getClass(), "getString", 0);
            if (getString != null) {
                Object string = getString.invoke(value);
                if (string != null) {
                    return string.toString().strip();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return value.toString().strip();
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private List<?> values(Object value) {
        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return List.copyOf(map.values());
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        if (value instanceof Stream<?> stream) {
            try (stream) {
                return stream.toList();
            }
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        return List.of();
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续查找父类。
            }
        }
        return null;
    }

    private Object readField(Object target, String name) throws IllegalAccessException {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        return field.get(target);
    }

    private Method findCompatibleMethod(Class<?> type, String name, Object[] arguments) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                    continue;
                }
                boolean compatible = true;
                Class<?>[] parameters = method.getParameterTypes();
                for (int index = 0; index < parameters.length; index++) {
                    if (arguments[index] != null && !wrap(parameters[index]).isInstance(arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            boolean compatible = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int index = 0; index < parameters.length; index++) {
                if (arguments[index] != null && !wrap(parameters[index]).isInstance(arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    private String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

}
