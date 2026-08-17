package io.ctyx.modpedia.client;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.KnowledgeStatus;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskRuntimeReadResult;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏 JVM 到 ModPedia Worker JVM 的本地桥接。
 *
 * <p>UI 只接触这里的事件；AI、历史、SQLite 和网络不在游戏进程执行。Worker
 * 不可用时保留明确的 UI 错误状态，而不是偷偷回到游戏线程执行重活。</p>
 */
public final class ModPediaBridge {
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final long START_TIMEOUT_SECONDS = 15L;
    /** 大型整合包首轮扫描可能超过连接握手时间，但这段等待发生在启动异步线程。 */
    private static final long KNOWLEDGE_BUILD_TIMEOUT_SECONDS = 120L;
    private static final long RECONNECT_DELAY_SECONDS = 2L;
    private static final long HEARTBEAT_SECONDS = 10L;
    private static final String WORKER_MAIN_ENTRY = "io/ctyx/modpedia/worker/WorkerMain.class";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ModPediaBridge INSTANCE = new ModPediaBridge();

    /** 原始监听器明确运行在 IPC reader 线程，只供非 UI 诊断/协议处理使用。 */
    private final List<Consumer<JsonObject>> rawListeners = new CopyOnWriteArrayList<>();
    /** 客户端监听器由 Bridge 统一切回 Minecraft 线程。 */
    private final List<Consumer<JsonObject>> clientListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<JsonObject>> requestListeners = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> responses = new ConcurrentHashMap<>();
    private final Map<String, Boolean> knowledgeRequests = new ConcurrentHashMap<>();
    /**
     * 物品目录通过文件传输时，载荷的生命周期不能依赖调用方线程是否等到
     * 响应。超时、合并和断线都可能发生在 requestAndWait 返回之后，因此按
     * request_id 保存路径，并只在 Worker 终态或连接已经销毁后清理。
     */
    private final Map<String, Path> itemPayloads = new ConcurrentHashMap<>();
    private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-worker-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    /** 大批量物品目录不通过主线程构造一条超大的 JSONL IPC 消息。 */
    private final ExecutorService itemPayloadWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-item-payload");
        thread.setDaemon(true);
        return thread;
    });
    /** 任务运行时回退读取由单飞、有界队列和短时缓存协调。 */
    private final RuntimeContextCoordinator<RuntimeContextResult> runtimeContextCoordinator;
    /** 启动、关闭和重连共享同一生命周期状态锁；不能让关闭期间重新拉起 Worker。 */
    private final Object lifecycleLock = new Object();
    /** writer、socket 和 ready 的检查与实际写入共享同一发送边界。 */
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile ServerSocket startupServer;
    private volatile boolean ready;
    private volatile boolean starting;
    private volatile boolean shuttingDown;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile RuntimeContextHandler runtimeContextHandler;
    private volatile KnowledgeStatus knowledgeStatus = KnowledgeStatus.initial();
    private volatile Object observedLevel;
    private volatile Object observedPlayer;
    private volatile boolean worldObserved;

    private ModPediaBridge() {
        runtimeContextCoordinator = new RuntimeContextCoordinator<>(
                lifecycle,
                () -> RuntimeContextResult.unavailable("客户端运行时读取已取消"),
                RuntimeContextResult::cacheable,
                this::deliverRuntimeContext
        );
    }

    public static ModPediaBridge get() {
        return INSTANCE;
    }

    public boolean startBeforeMainMenu() {
        synchronized (lifecycleLock) {
            if (shuttingDown || ready) {
                return ready;
            }
            if (starting) {
                return false;
            }
            starting = true;
        }
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (shuttingDown) {
                    break;
                }
                if (startOnce()) {
                    synchronized (lifecycleLock) {
                        if (!shuttingDown && ready) {
                            return true;
                        }
                    }
                    stopProcess();
                    break;
                }
                if (attempt < 3 && !shuttingDown) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // 初始握手失败不能让 Worker 永久停在“未启动”状态。重连仍在
            // lifecycle 线程执行，调用方（尤其是启动异步链）不需要阻塞游戏线程。
            if (!shuttingDown) {
                scheduleReconnect();
            }
            return false;
        } finally {
            synchronized (lifecycleLock) {
                starting = false;
            }
            if (shuttingDown && ready) {
                stopProcess();
            }
        }
    }

    private boolean startOnce() {
        if (shuttingDown || ready) {
            return ready;
        }
        try {
            Path configDirectory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize();
            Path workerDirectory = configDirectory.resolve("modpedia").resolve("worker");
            Files.createDirectories(workerDirectory);
            cleanupItemPayloads(workerDirectory);
            String token = randomToken();
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST));
            synchronized (lifecycleLock) {
                if (shuttingDown) {
                    closeQuietly(server);
                    return false;
                }
                startupServer = server;
            }
            try (server) {
                server.setSoTimeout(250);
                int port = server.getLocalPort();
                Process launchedProcess = launchWorker(port, LOOPBACK_HOST, token, configDirectory, workerDirectory);
                synchronized (writeLock) {
                    if (shuttingDown) {
                        destroyQuietly(launchedProcess);
                        return false;
                    }
                    process = launchedProcess;
                }
                ModPedia.LOGGER.info(
                        "worker_launch host={} port={} pid={}",
                        LOOPBACK_HOST,
                        port,
                        launchedProcess.pid()
                );
                Socket accepted = acceptWorker(server, launchedProcess);
                if (shuttingDown) {
                    closeQuietly(accepted);
                    return false;
                }
                accepted.setTcpNoDelay(true);
                BufferedWriter acceptedWriter = new BufferedWriter(new OutputStreamWriter(
                        accepted.getOutputStream(), StandardCharsets.UTF_8
                ));
                synchronized (writeLock) {
                    if (shuttingDown) {
                        closeQuietly(accepted);
                        return false;
                    }
                    socket = accepted;
                    writer = acceptedWriter;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        accepted.getInputStream(), StandardCharsets.UTF_8
                ));
                String helloId = UUID.randomUUID().toString();
                JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, helloId);
                hello.addProperty("auth_token", token);
                send(hello);
                JsonObject ack = WorkerProtocol.read(reader);
                if (ack == null
                        || !WorkerProtocol.HELLO_ACK.equals(WorkerProtocol.string(ack, "type"))
                        || !WorkerProtocol.bool(ack, "accepted", false)) {
                    throw new IOException("Worker 握手失败：" + WorkerProtocol.string(ack, "error"));
                }
                synchronized (lifecycleLock) {
                    if (shuttingDown) {
                        throw new IOException("Worker 启动已被关闭取消");
                    }
                    ready = true;
                }
                Thread readerThread = new Thread(
                        () -> readEvents(reader, accepted, launchedProcess),
                        "modpedia-worker-ipc-reader"
                );
                readerThread.setDaemon(true);
                readerThread.start();
                scheduleHeartbeat();
                notifyWorkerReady();
                ModPedia.LOGGER.info("ModPedia Worker 已启动：pid={}", WorkerProtocol.longValue(ack, "worker_pid", -1));
                return true;
            } finally {
                synchronized (lifecycleLock) {
                    if (startupServer == server) {
                        startupServer = null;
                    }
                }
            }
        } catch (SocketTimeoutException exception) {
            stopProcess();
            if (!shuttingDown) {
                ModPedia.LOGGER.warn("ModPedia Worker 启动超时");
            }
            return false;
        } catch (Throwable failure) {
            stopProcess();
            if (!shuttingDown) {
                ModPedia.LOGGER.warn("ModPedia Worker 启动失败：{}", messageOf(failure));
            }
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public KnowledgeStatus knowledgeStatus() {
        return knowledgeStatus;
    }

    public void addRawListener(Consumer<JsonObject> listener) {
        if (listener != null) {
            rawListeners.add(listener);
        }
    }

    public void removeRawListener(Consumer<JsonObject> listener) {
        rawListeners.remove(listener);
    }

    public void addClientListener(Consumer<JsonObject> listener) {
        if (listener != null) {
            clientListeners.add(listener);
        }
    }

    public void removeClientListener(Consumer<JsonObject> listener) {
        clientListeners.remove(listener);
    }

    /** 兼容早期调用方；新的 UI 代码应使用 addClientListener。 */
    @Deprecated
    public void addListener(Consumer<JsonObject> listener) {
        addClientListener(listener);
    }

    /** 兼容早期调用方；新的 UI 代码应使用 removeClientListener。 */
    @Deprecated
    public void removeListener(Consumer<JsonObject> listener) {
        removeClientListener(listener);
    }

    /**
     * 注册一次性的 Worker ready 回调。用于启动阶段首轮握手失败后的自动恢复；
     * 回调只表示 IPC 已握手，不表示知识库已经构建完成。
     */
    public void whenReady(Runnable listener) {
        if (listener == null) {
            return;
        }
        boolean invokeNow;
        synchronized (this) {
            invokeNow = ready;
            if (!invokeNow && !shuttingDown) {
                readyListeners.add(listener);
            }
        }
        if (invokeNow) {
            try {
                listener.run();
            } catch (Throwable failure) {
                ModPedia.LOGGER.debug("Worker ready 回调失败", failure);
            }
        }
    }

    private void notifyWorkerReady() {
        List<Runnable> pending = new ArrayList<>(readyListeners);
        readyListeners.clear();
        for (Runnable listener : pending) {
            try {
                listener.run();
            } catch (Throwable failure) {
                ModPedia.LOGGER.debug("Worker ready 回调失败", failure);
            }
        }
    }

    public void setRuntimeContextHandler(RuntimeContextHandler handler) {
        runtimeContextHandler = handler;
    }

    /**
     * ClientTick 中调用的轻量生命周期观察。世界或玩家对象变化时立即取消旧的
     * 运行时读取，避免旧存档快照穿透到新世界。
     */
    public void observeClientWorld(Object level, Object player) {
        boolean changed;
        synchronized (this) {
            changed = worldObserved && (observedLevel != level || observedPlayer != player);
            observedLevel = level;
            observedPlayer = player;
            worldObserved = true;
        }
        if (changed) {
            runtimeContextCoordinator.invalidate();
        }
    }

    public boolean rebuildKnowledgeBeforeMainMenu(Path modsDirectory, boolean forceRebuild) {
        if (!ready || modsDirectory == null) {
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        JsonObject request = WorkerProtocol.message(WorkerProtocol.KNOWLEDGE_REBUILD, requestId);
        request.addProperty("mods_directory", modsDirectory.toAbsolutePath().normalize().toString());
        request.addProperty("force_rebuild", forceRebuild);
        knowledgeRequests.put(requestId, Boolean.TRUE);
        setKnowledgeUpdating();
        return requestAndWait(requestId, request, KNOWLEDGE_BUILD_TIMEOUT_SECONDS);
    }

    /** F9/远程 Wiki 更新使用的非阻塞重建入口；数据库仍只由 Worker 打开。 */
    public boolean rebuildKnowledgeAsync(Path modsDirectory, boolean forceRebuild) {
        if (!ready || modsDirectory == null) {
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        JsonObject request = WorkerProtocol.message(WorkerProtocol.KNOWLEDGE_REBUILD, requestId);
        request.addProperty("mods_directory", modsDirectory.toAbsolutePath().normalize().toString());
        request.addProperty("force_rebuild", forceRebuild);
        knowledgeRequests.put(requestId, Boolean.TRUE);
        setKnowledgeUpdating();
        return sendIfReady(request);
    }

    /**
     * 请求 Worker 更新任务 Wiki；客户端只传递模组目录，网络和文件处理留在 Worker。
     */
    public boolean syncTaskWikiAsync(Path modsDirectory) {
        if (!ready || modsDirectory == null) {
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        JsonObject request = WorkerProtocol.message(WorkerProtocol.TASK_WIKI_SYNC, requestId);
        request.addProperty("mods_directory", modsDirectory.toAbsolutePath().normalize().toString());
        request.addProperty(
                "url",
                System.getProperty("modpedia.taskWikiUrl", "").strip()
        );
        // Wiki 同步在内容变化时会继续触发一次知识库重建，因此也必须纳入
        // 客户端知识状态跟踪。否则 Worker 已经完成重建，但 UI 会永久停留在
        // “更新中”。
        knowledgeRequests.put(requestId, Boolean.TRUE);
        setKnowledgeUpdating();
        return sendIfReady(request);
    }

    public boolean syncItems(String language, List<io.ctyx.modpedia.search.ItemCatalogEntry> entries) {
        if (!ready) {
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        Path payload;
        try {
            payload = stageItemCatalogPayload(requestId, entries);
        } catch (IOException exception) {
            ModPedia.LOGGER.warn("物品目录批量载荷准备失败；保留上一份目录", exception);
            return false;
        }
        JsonObject request = WorkerProtocol.message(WorkerProtocol.KNOWLEDGE_ITEMS_SYNC, requestId);
        request.addProperty("language", language == null ? "zh_cn" : language);
        request.addProperty("items_file", payload.toString());
        request.addProperty("items_format", "item_catalog_jsonl_v1");
        request.addProperty("item_count", entries == null ? 0 : entries.size());
        itemPayloads.put(requestId, payload);
        boolean completed = requestAndWait(requestId, request, KNOWLEDGE_BUILD_TIMEOUT_SECONDS);
        // 终态事件通常已经由 dispatch() 清理。这里仅处理同步响应已经返回、
        // 但 reader 线程尚未来得及派发监听器的成功路径；超时不能立即删除，
        // 因为 Worker 可能仍在读取文件。
        if (completed) {
            cleanupItemPayload(requestId);
        } else if (!ready) {
            // sendIfReady() 失败或连接已经销毁时，Worker 不可能再读取该文件。
            cleanupItemPayload(requestId);
        }
        return completed;
    }

    public boolean startChat(
            String requestId,
            String conversationId,
            String prompt,
            String language,
            Consumer<JsonObject> callback
    ) {
        return startChat(requestId, conversationId, prompt, language, false, callback);
    }

    public boolean startChat(
            String requestId,
            String conversationId,
            String prompt,
            String language,
            boolean retry,
            Consumer<JsonObject> callback
    ) {
        if (callback != null) {
            Consumer<JsonObject> listener = event -> {
                if (requestId.equals(WorkerProtocol.string(event, "request_id"))) {
                    callback.accept(event);
                }
            };
            requestListeners.put(requestId, listener);
            addClientListener(listener);
        }
        JsonObject request = WorkerProtocol.message(WorkerProtocol.CHAT_START, requestId);
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        request.addProperty("prompt", prompt == null ? "" : prompt);
        request.addProperty("language", language == null ? "auto" : language);
        request.addProperty("retry", retry);
        if (!sendIfReady(request)) {
            JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, requestId);
            error.addProperty("message", "ModPedia Worker 不可用");
            if (callback != null) {
                notifyClientListener(callback, error);
            }
            removeRequestListener(requestId);
            return false;
        }
        return true;
    }

    public void cancel(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        runtimeContextCoordinator.cancel(requestId);
        JsonObject request = WorkerProtocol.message(WorkerProtocol.CHAT_CANCEL, requestId);
        sendIfReady(request);
    }

    /** 读取由 Worker 独占保存的 AI 设置。 */
    public boolean loadSettings(Consumer<JsonObject> callback) {
        return requestOperation(
                WorkerProtocol.SETTINGS_LOAD,
                UUID.randomUUID().toString(),
                null,
                callback
        );
    }

    /** 保存设置页当前值；保存和回读校验在 Worker 中完成。 */
    public boolean saveSettings(AiSettings settings, Consumer<JsonObject> callback) {
        JsonObject payload = new JsonObject();
        payload.add("settings", WorkerPayloadCodec.aiSettings(settings));
        return requestOperation(
                WorkerProtocol.SETTINGS_SAVE,
                UUID.randomUUID().toString(),
                payload,
                callback
        );
    }

    public boolean fetchModels(AiSettings settings, Consumer<JsonObject> callback) {
        JsonObject payload = new JsonObject();
        payload.add("settings", WorkerPayloadCodec.aiSettings(settings));
        return requestOperation(
                WorkerProtocol.AI_MODELS_FETCH,
                UUID.randomUUID().toString(),
                payload,
                callback
        );
    }

    public boolean testConnection(AiSettings settings, Consumer<JsonObject> callback) {
        JsonObject payload = new JsonObject();
        payload.add("settings", WorkerPayloadCodec.aiSettings(settings));
        return requestOperation(
                WorkerProtocol.AI_CONNECTION_TEST,
                UUID.randomUUID().toString(),
                payload,
                callback
        );
    }

    public boolean testAllModels(AiSettings settings, Consumer<JsonObject> callback) {
        JsonObject payload = new JsonObject();
        payload.add("settings", WorkerPayloadCodec.aiSettings(settings));
        return requestOperation(
                WorkerProtocol.AI_COMPATIBILITY_TEST,
                UUID.randomUUID().toString(),
                payload,
                callback
        );
    }

    public boolean command(String type, String requestId, Map<String, String> fields) {
        JsonObject request = WorkerProtocol.message(type, requestId);
        if (fields != null) {
            fields.forEach(request::addProperty);
        }
        return sendIfReady(request);
    }

    /** 发送一个需要终态回调的 Worker 操作。回调统一在 Minecraft 客户端线程触发。 */
    public boolean requestOperation(
            String type,
            String requestId,
            JsonObject payload,
            Consumer<JsonObject> callback
    ) {
        String actualRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId;
        JsonObject request = WorkerProtocol.message(type, actualRequestId);
        if (payload != null) {
            for (var entry : payload.entrySet()) {
                request.add(entry.getKey(), entry.getValue());
            }
        }
        if (callback != null) {
            Consumer<JsonObject> listener = event -> {
                if (actualRequestId.equals(WorkerProtocol.string(event, "request_id"))) {
                    callback.accept(event);
                }
            };
            requestListeners.put(actualRequestId, listener);
            addClientListener(listener);
        }
        if (sendIfReady(request)) {
            return true;
        }
        if (callback != null) {
            JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, actualRequestId);
            error.addProperty("message", "ModPedia Worker 不可用");
            notifyClientListener(callback, error);
            removeRequestListener(actualRequestId);
        }
        return false;
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            closeQuietly(startupServer);
            startupServer = null;
        }
        readyListeners.clear();
        cancelLifecycleTasks();
        if (ready) {
            sendIfReady(WorkerProtocol.message(WorkerProtocol.SHUTDOWN, UUID.randomUUID().toString()));
        }
        stopProcess();
        cleanupAllItemPayloads();
        runtimeContextCoordinator.invalidate();
        itemPayloadWriter.shutdownNow();
        runtimeContextCoordinator.close();
        lifecycle.shutdownNow();
    }

    /**
     * 把物品目录写成同机原子 JSONL 文件，再只通过 IPC 传递文件路径。
     *
     * <p>Tooltip 文本可能让数万条记录达到数 MB。直接把整个数组挂到 JsonObject
     * 会在游戏线程完成对象树构造、转义和 socket flush；这里把这段工作放到
     * 独立 I/O 线程，Worker 读取完整文件后才开始 SQLite 事务。游戏 JVM 不打开
     * knowledge.db，数据库写入仍完全由 Worker 执行。</p>
     */
    private Path stageItemCatalogPayload(
            String requestId,
            List<io.ctyx.modpedia.search.ItemCatalogEntry> entries
    ) throws IOException {
        Path workerDirectory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize()
                .resolve("modpedia").resolve("worker");
        Path payloadDirectory = workerDirectory.resolve("payloads");
        Path temporary = payloadDirectory.resolve("items-" + requestId + ".jsonl.tmp");
        Path target = payloadDirectory.resolve("items-" + requestId + ".jsonl");
        Future<Path> future = null;
        try {
            future = itemPayloadWriter.submit(() -> {
                Files.createDirectories(payloadDirectory);
                try {
                    try (BufferedWriter output = Files.newBufferedWriter(
                            temporary,
                            StandardCharsets.UTF_8
                    )) {
                        if (entries != null) {
                            for (io.ctyx.modpedia.search.ItemCatalogEntry entry : entries) {
                                if (entry == null) {
                                    continue;
                                }
                                output.write(WorkerPayloadCodec.item(entry).toString());
                                output.newLine();
                            }
                        }
                    }
                    try {
                        Files.move(
                                temporary,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(
                                temporary,
                                target,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                    return target;
                } catch (IOException | RuntimeException failure) {
                    deleteQuietly(temporary);
                    throw failure;
                }
            });
            return future.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            cancelItemPayloadWrite(future, temporary, target);
            Thread.currentThread().interrupt();
            throw new IOException("物品目录批量载荷写入被中断", exception);
        } catch (ExecutionException exception) {
            cancelItemPayloadWrite(future, temporary, target);
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("物品目录批量载荷写入失败", cause);
        } catch (java.util.concurrent.TimeoutException exception) {
            // Future.get 超时不会自动中断写线程；显式取消并清理两个确定路径，
            // 否则慢速磁盘可能在调用方返回后又生成一个孤儿 JSONL。
            cancelItemPayloadWrite(future, temporary, target);
            throw new IOException("物品目录批量载荷写入超时", exception);
        }
    }

    private void cancelItemPayloadWrite(Future<?> future, Path temporary, Path target) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        deleteQuietly(temporary);
        deleteQuietly(target);
    }

    private void cleanupItemPayloads(Path workerDirectory) {
        Path payloadDirectory = workerDirectory.resolve("payloads");
        if (!Files.isDirectory(payloadDirectory)) {
            return;
        }
        try (var paths = Files.list(payloadDirectory)) {
            paths.filter(path -> path.getFileName().toString().startsWith("items-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl")
                            || path.getFileName().toString().endsWith(".jsonl.tmp"))
                    .forEach(this::deleteQuietly);
        } catch (IOException exception) {
            ModPedia.LOGGER.debug("清理旧物品目录批量载荷失败", exception);
        }
    }

    private void cleanupItemPayload(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        Path payload = itemPayloads.remove(requestId);
        deleteQuietly(payload);
    }

    private void cleanupAllItemPayloads() {
        itemPayloads.keySet().forEach(this::cleanupItemPayload);
        // stageItemCatalogPayload() 的写线程可能正处在 atomic move 前后；按
        // request_id 逐个清理已知路径后，再清理目录中的残留，避免重连后把旧
        // 载荷带入下一次启动。
        Path workerDirectory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize()
                .resolve("modpedia").resolve("worker");
        cleanupItemPayloads(workerDirectory);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            ModPedia.LOGGER.debug("删除物品目录批量载荷失败: {}", path, exception);
        }
    }

    private void scheduleHeartbeat() {
        synchronized (lifecycleLock) {
            if (shuttingDown || !ready) {
                return;
            }
            ScheduledFuture<?> previous = heartbeatFuture;
            if (previous != null && !previous.isDone()) {
                return;
            }
            heartbeatFuture = lifecycle.scheduleAtFixedRate(
                    this::heartbeat,
                    HEARTBEAT_SECONDS,
                    HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    private void heartbeat() {
        if (shuttingDown) {
            return;
        }
        if (!ready) {
            scheduleReconnect();
            return;
        }
        String requestId = UUID.randomUUID().toString();
        JsonObject ping = WorkerProtocol.message(WorkerProtocol.PING, requestId);
        if (!requestAndWait(requestId, ping, 3L)) {
            ModPedia.LOGGER.warn("ModPedia Worker 心跳超时，准备重连");
            closeConnectionForRecovery();
        }
    }

    private void scheduleReconnect() {
        synchronized (lifecycleLock) {
            if (shuttingDown || ready) {
                return;
            }
            ScheduledFuture<?> previous = reconnectFuture;
            if (previous != null && !previous.isDone()) {
                return;
            }
            reconnectFuture = lifecycle.schedule(() -> {
                synchronized (lifecycleLock) {
                    // 当前定时任务开始执行后已经不再是“排队中”；否则失败时
                    // startBeforeMainMenu() 内部再次 scheduleReconnect() 会看到
                    // 自己尚未完成，从而丢失后续重试。
                    reconnectFuture = null;
                }
                if (!shuttingDown && !ready && startBeforeMainMenu()) {
                    ModPedia.LOGGER.info("ModPedia Worker 已自动重连");
                }
            }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void closeConnectionForRecovery() {
        Socket current = socket;
        Process currentProcess = process;
        handleConnectionLost(current, currentProcess, "ModPedia Worker 心跳超时，正在准备重连");
    }

    private void cancelLifecycleTasks() {
        synchronized (lifecycleLock) {
            ScheduledFuture<?> reconnect = reconnectFuture;
            if (reconnect != null) {
                reconnect.cancel(false);
                reconnectFuture = null;
            }
            ScheduledFuture<?> heartbeat = heartbeatFuture;
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeatFuture = null;
            }
        }
    }

    private boolean requestAndWait(String requestId, JsonObject request, long timeoutSeconds) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        responses.put(requestId, future);
        if (!sendIfReady(request)) {
            responses.remove(requestId);
            return false;
        }
        try {
            JsonObject response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (WorkerProtocol.PONG.equals(WorkerProtocol.string(response, "type"))) {
                return true;
            }
            if (!WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(response, "type"))) {
                return false;
            }
            // 合并事件只是说明该请求被排到下一次构建，不能让启动门禁把
            // “尚未完成”误判为成功并继续导入物品目录。
            return !WorkerProtocol.KNOWLEDGE_REBUILD_COALESCED.equals(
                    WorkerProtocol.string(response, "operation"));
        } catch (Exception exception) {
            return false;
        } finally {
            responses.remove(requestId);
        }
    }

    private Socket acceptWorker(ServerSocket server, Process launchedProcess) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
        while (true) {
            try {
                return server.accept();
            } catch (SocketTimeoutException timeout) {
                if (!launchedProcess.isAlive()) {
                    throw new IOException("Worker 在握手前退出，exit_code=" + launchedProcess.exitValue());
                }
                if (System.nanoTime() >= deadline) {
                    throw timeout;
                }
            }
        }
    }

    private Process launchWorker(
            int port,
            String host,
            String token,
            Path configDirectory,
            Path workerDirectory
    ) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dmodpedia.worker=true");
        command.add("-cp");
        command.add(workerClasspath(workerDirectory));
        command.add("io.ctyx.modpedia.worker.WorkerMain");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--host");
        command.add(host);
        command.add("--config");
        command.add(configDirectory.toString());
        command.add("--knowledge");
        command.add(configDirectory.resolve("modpedia").resolve("knowledge").toString());
        command.add("--conversations");
        command.add(configDirectory.resolve("modpedia").resolve("conversations").toString());
        command.add("--settings");
        command.add(configDirectory.resolve("modpedia").resolve("ai.json").toString());
        Path log = workerDirectory.resolve("worker.log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        // Token 不再出现在 ps/Activity Monitor 的命令行参数中；只通过子进程
        // 环境变量传递，并且 Worker 仍会在握手阶段再次校验。
        builder.environment().put("MODPEDIA_WORKER_TOKEN", token);
        return builder.start();
    }

    private String workerClasspath(Path workerDirectory) throws IOException {
        String override = System.getProperty("modpedia.worker.classpath", "").strip();
        if (!override.isBlank()) {
            return override;
        }
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String current = System.getProperty("java.class.path", "");
        if (!current.isBlank()) {
            for (String entry : current.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        // SLF4J 由 NeoForge 的模块层提供，不能从 ModPedia JAR 再提取一份。
        // Worker 是普通 classpath JVM，需要显式加入游戏侧 API 的实际代码来源。
        addClassLocation(entries, "org.slf4j.LoggerFactory");
        // Gson 同样由 Minecraft/NeoForge 提供；这里只把游戏侧代码来源加入
        // Worker classpath，不从 ModPedia JAR 提取第二份模块。
        addClassLocation(entries, "com.google.gson.Gson");
        try {
            Path codeSource = Path.of(ModPediaBridge.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            entries.add(codeSource.toString());
            if (Files.isRegularFile(codeSource) && codeSource.toString().endsWith(".jar")) {
                entries.addAll(extractNestedJars(codeSource, workerDirectory.resolve("lib")));
            }
        } catch (Exception exception) {
            ModPedia.LOGGER.debug("Worker classpath code source unavailable", exception);
        }
        // NeoForge 的模块/联合类加载器经常返回 union:/... 代码来源，不能直接
        // 作为普通 JVM 的 -cp。回到当前实例的 mods 目录，寻找真正包含 WorkerMain
        // 的发布 JAR；这是生产环境独立 Worker 能启动的必要兜底。
        try {
            Path installedArchive = findWorkerArchive(
                    FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("mods")
            );
            if (installedArchive != null) {
                entries.add(installedArchive.toString());
                entries.addAll(extractNestedJars(installedArchive, workerDirectory.resolve("lib")));
            } else {
                ModPedia.LOGGER.warn("未找到包含 {} 的 ModPedia 发布 JAR", WORKER_MAIN_ENTRY);
            }
        } catch (Exception exception) {
            ModPedia.LOGGER.warn("扫描 ModPedia Worker 发布 JAR 失败", exception);
        }
        return String.join(File.pathSeparator, entries);
    }

    /** 仅检查当前实例的模组归档，不解析模组类或启动客户端类。 */
    static Path findWorkerArchive(Path modsDirectory) {
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            return null;
        }
        try (var paths = Files.list(modsDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jar") || name.endsWith(".zip");
                    })
                    .sorted()
                    .filter(ModPediaBridge::containsWorkerMain)
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            ModPedia.LOGGER.debug("读取模组目录失败：{}", modsDirectory, exception);
            return null;
        }
    }

    private static boolean containsWorkerMain(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.getEntry(WORKER_MAIN_ENTRY) != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private void addClassLocation(LinkedHashSet<String> entries, String className) {
        try {
            Class<?> type = Class.forName(className, false, ModPediaBridge.class.getClassLoader());
            if (type.getProtectionDomain().getCodeSource() == null) {
                return;
            }
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                entries.add(location.toString());
            }
        } catch (Exception exception) {
            ModPedia.LOGGER.debug("Worker 侧运行时 API 路径不可用：{}", className, exception);
        }
    }

    private List<String> extractNestedJars(Path jar, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        List<String> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith("META-INF/jarjar/")
                            && entry.getName().endsWith(".jar"))
                    .toList();
            for (ZipEntry entry : entries) {
                Path target = outputDirectory.resolve(Path.of(entry.getName()).getFileName().toString());
                if (!Files.isRegularFile(target)) {
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                result.add(target.toString());
            }
        }
        return result;
    }

    private void readEvents(BufferedReader reader, Socket observedSocket, Process observedProcess) {
        try {
            while (ready) {
                JsonObject event = WorkerProtocol.read(reader);
                if (event == null) {
                    break;
                }
                dispatch(event);
            }
        } catch (Throwable failure) {
            if (!shuttingDown) {
                ModPedia.LOGGER.warn("ModPedia Worker 连接断开：{}", messageOf(failure));
            }
        } finally {
            if (!shuttingDown) {
                handleConnectionLost(observedSocket, observedProcess,
                        "ModPedia Worker 连接已断开，正在准备重连");
            }
        }
    }

    /**
     * 只处理仍然属于当前连接的断线；旧 reader 线程不能破坏已经成功重连的
     * 新连接。该方法同时结束旧 Worker，避免断线后遗留孤儿 JVM。
     */
    private void handleConnectionLost(
            Socket observedSocket,
            Process observedProcess,
            String message
    ) {
        if (shuttingDown) {
            return;
        }
        synchronized (writeLock) {
            if (observedSocket == null || socket != observedSocket) {
                return;
            }
            ready = false;
            socket = null;
            writer = null;
            if (process == observedProcess) {
                process = null;
            }
        }
        closeQuietly(observedSocket);
        destroyQuietly(observedProcess);
        // Worker 已经无法再消费运行时快照；先完成并取消客户端侧 waiter，避免
        // 重连后旧请求把过期的世界/玩家进度发送到新连接。
        runtimeContextCoordinator.invalidate();
        notifyPendingRequests(message);
        responses.values().forEach(future -> future.completeExceptionally(
                new IOException("Worker 连接断开")
        ));
        responses.clear();
        requestListeners.clear();
        cleanupAllItemPayloads();
        if (!knowledgeRequests.isEmpty()) {
            knowledgeRequests.clear();
            KnowledgeStatus previous = knowledgeStatus;
            knowledgeStatus = new KnowledgeStatus(
                    false,
                    previous.sourceCount(),
                    previous.documentCount(),
                    previous.lastUpdated(),
                    message
            );
        }
        JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, "");
        error.addProperty("message", message);
        notifyRawListeners(error);
        notifyClientListeners(error);
        scheduleReconnect();
    }

    private void closeQuietly(ServerSocket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }

    private void destroyQuietly(Process value) {
        if (value == null || !value.isAlive()) {
            return;
        }
        value.destroy();
        try {
            if (!value.waitFor(2, TimeUnit.SECONDS)) {
                value.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            value.destroyForcibly();
        }
    }

    private void notifyPendingRequests(String message) {
        for (Map.Entry<String, Consumer<JsonObject>> entry : requestListeners.entrySet()) {
            JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, entry.getKey());
            error.addProperty("message", message);
            notifyClientListener(entry.getValue(), error);
            removeRequestListener(entry.getKey());
        }
    }

    private void notifyRawListeners(JsonObject event) {
        rawListeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Throwable failure) {
                ModPedia.LOGGER.debug("Worker 原始事件监听器失败", failure);
            }
        });
    }

    private void notifyClientListeners(JsonObject event) {
        clientListeners.forEach(listener -> notifyClientListener(listener, event));
    }

    private void notifyClientListener(Consumer<JsonObject> listener, JsonObject event) {
        if (listener == null) {
            return;
        }
        Runnable action = () -> {
            try {
                listener.accept(event);
            } catch (Throwable failure) {
                ModPedia.LOGGER.debug("Worker 客户端事件监听器失败", failure);
            }
        };
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                // client listener 的线程契约不能在 Minecraft 实例不可用时退化为
                // IPC reader 线程执行；关闭/早期启动阶段直接丢弃该 UI 事件。
                ModPedia.LOGGER.debug("Minecraft 实例不可用，丢弃 Worker 客户端事件");
                return;
            }
            if (!minecraft.isSameThread()) {
                minecraft.execute(action);
                return;
            }
            action.run();
        } catch (Throwable failure) {
            ModPedia.LOGGER.debug("切回 Minecraft 线程失败，丢弃 Worker 客户端事件", failure);
        }
    }

    private void dispatch(JsonObject event) {
        String type = WorkerProtocol.string(event, "type");
        String requestId = WorkerProtocol.string(event, "request_id");
        updateKnowledgeStatus(type, requestId, event);
        if (WorkerProtocol.RUNTIME_CONTEXT_REQUEST.equals(type)) {
            handleRuntimeContextRequest(event);
        }
        CompletableFuture<JsonObject> response = responses.get(requestId);
        if (response != null && (WorkerProtocol.COMPLETED.equals(type)
                || WorkerProtocol.ERROR.equals(type)
                || WorkerProtocol.PONG.equals(type))) {
            response.complete(event);
        }
        notifyRawListeners(event);
        notifyClientListeners(event);
        if (WorkerProtocol.COMPLETED.equals(type)
                || WorkerProtocol.ERROR.equals(type)
                || WorkerProtocol.CANCELLED.equals(type)) {
            cleanupItemPayload(requestId);
            removeRequestListener(requestId);
        }
    }

    private void removeRequestListener(String requestId) {
        Consumer<JsonObject> listener = requestListeners.remove(requestId);
        if (listener != null) {
            clientListeners.remove(listener);
        }
    }

    private void updateKnowledgeStatus(String type, String requestId, JsonObject event) {
        if (WorkerProtocol.STATUS.equals(type)
                && ("knowledge_rebuild".equals(WorkerProtocol.string(event, "phase"))
                || "task_wiki_sync".equals(WorkerProtocol.string(event, "phase")))) {
            setKnowledgeUpdating();
            return;
        }
        if (WorkerProtocol.COMPLETED.equals(type)
                && WorkerProtocol.KNOWLEDGE_REBUILD_COALESCED.equals(
                WorkerProtocol.string(event, "operation"))) {
            // 被合并的请求只结束自己的等待者；真正的构建仍在运行，不能把
            // knowledgeStatus 从 updating 改回完成。
            knowledgeRequests.remove(requestId);
            ModPedia.LOGGER.debug("Knowledge rebuild request coalesced: {}", requestId);
            return;
        }
        if (WorkerProtocol.COMPLETED.equals(type)
                && (WorkerProtocol.KNOWLEDGE_REBUILD.equals(WorkerProtocol.string(event, "operation"))
                || WorkerProtocol.TASK_WIKI_SYNC.equals(WorkerProtocol.string(event, "operation")))) {
            knowledgeRequests.remove(requestId);
            ModPedia.LOGGER.info(
                    "Knowledge operation completed: operation={} changed={} mods={} archives={} resources={} documents={} warnings={}",
                    WorkerProtocol.string(event, "operation"),
                    WorkerProtocol.bool(event, "changed", false),
                    WorkerProtocol.string(event, "mods_directory"),
                    WorkerProtocol.integer(event, "archive_count", -1),
                    WorkerProtocol.integer(event, "resource_count", -1),
                    WorkerProtocol.integer(event, "document_count", -1),
                    WorkerProtocol.integer(event, "warning_count", -1)
            );
            knowledgeStatus = completedKnowledgeStatus(knowledgeStatus, event);
            return;
        }
        if (WorkerProtocol.ERROR.equals(type) && knowledgeRequests.remove(requestId) != null) {
            KnowledgeStatus previous = knowledgeStatus;
            knowledgeStatus = new KnowledgeStatus(
                    false,
                    previous.sourceCount(),
                    previous.documentCount(),
                    previous.lastUpdated(),
                    WorkerProtocol.string(event, "message")
            );
        }
    }

    /**
     * 将 Worker 的知识操作完成事件转换为客户端展示快照。
     *
     * <p>任务 Wiki 未变化时不会携带构建计数，因此沿用上一份计数；发生变化
     * 并完成重建时则使用事件中的新计数。这个纯函数也作为 Wiki 状态回归测试
     * 的边界，避免再次把“同步完成”与“重建完成”混为两个不一致状态。</p>
     */
    static KnowledgeStatus completedKnowledgeStatus(KnowledgeStatus previous, JsonObject event) {
        KnowledgeStatus current = previous == null ? KnowledgeStatus.initial() : previous;
        return new KnowledgeStatus(
                false,
                WorkerProtocol.integer(event, "source_count", current.sourceCount()),
                WorkerProtocol.integer(event, "document_count", current.documentCount()),
                java.time.Instant.now().toString(),
                ""
        );
    }

    private void setKnowledgeUpdating() {
        KnowledgeStatus previous = knowledgeStatus;
        knowledgeStatus = new KnowledgeStatus(
                true,
                previous.sourceCount(),
                previous.documentCount(),
                previous.lastUpdated(),
                ""
        );
    }

    private void handleRuntimeContextRequest(JsonObject event) {
        RuntimeContextHandler handler = runtimeContextHandler;
        if (handler == null) {
            sendRuntimeContext(event, null, TaskRuntimeReadResult.unavailable("未注册任务运行时读取器"));
            return;
        }
        try {
            TaskQuery query = WorkerPayloadCodec.taskQuery(event.getAsJsonObject("query"));
            RuntimeContextRequest request = new RuntimeContextRequest(
                    WorkerProtocol.string(event, "request_id"), query
            );
            String chatRequestId = WorkerProtocol.string(event, "chat_request_id");
            String deduplicationKey = chatRequestId.isBlank()
                    ? request.requestId()
                    : chatRequestId;
            boolean submitted = runtimeContextCoordinator.submit(
                    request.requestId(),
                    deduplicationKey,
                    () -> handler.read(request)
            );
            if (!submitted) {
                sendRuntimeContext(
                        request.requestId(),
                        null,
                        TaskRuntimeReadResult.unavailable("客户端运行时读取繁忙，请稍后重试")
                );
            }
        } catch (Throwable failure) {
            sendRuntimeContext(event, null, TaskRuntimeReadResult.unavailable(messageOf(failure)));
        }
    }

    private void deliverRuntimeContext(RuntimeContextCoordinator.Delivery<RuntimeContextResult> delivery) {
        if (delivery == null) {
            return;
        }
        sendRuntimeContext(delivery.requestId(), delivery.value());
    }

    private void sendRuntimeContext(String requestId, RuntimeContextResult value) {
        RuntimeContextResult actual = value == null
                ? RuntimeContextResult.unavailable("未取得客户端运行时上下文")
                : value;
        TaskRuntimeReadResult result = actual.result();
        TaskRuntimeSnapshot snapshot = actual.snapshot() != null
                ? actual.snapshot()
                : result.runtimeSnapshot();
        JsonObject response = WorkerProtocol.message(
                WorkerProtocol.RUNTIME_CONTEXT_RESPONSE,
                requestId
        );
        response.addProperty("available", result.available());
        response.addProperty("read", result.read());
        response.addProperty("message", result.message());
        response.add("runtime_context", WorkerPayloadCodec.runtimeSnapshot(snapshot));
        if (actual.runtimeFile() != null) {
            response.add("runtime_file", WorkerPayloadCodec.runtimeFileDescriptor(actual.runtimeFile()));
        }
        sendIfReady(response);
    }

    public void sendRuntimeContext(
            JsonObject request,
            TaskRuntimeSnapshot snapshot,
            TaskRuntimeReadResult result
    ) {
        sendRuntimeContext(
                WorkerProtocol.string(request, "request_id"),
                snapshot,
                result
        );
    }

    /**
     * 把游戏 JVM 刚刚读取到的临时任务快照返回给 Worker。
     *
     * <p>这个方法只发送运行时数据，不打开数据库。它是单机存档文件不可用时的
     * 回退路径；正常单机链路由 Worker 直接读取 FTBQ SNBT。Worker 收到响应后才
     * 会执行 {@code TaskKnowledgeStore.query(...)}，因此顺序固定为：先取得玩家
     * 当前运行时进度，再查静态任务定义。</p>
     */
    public void sendRuntimeContext(
            String requestId,
            TaskRuntimeSnapshot snapshot,
            TaskRuntimeReadResult result
    ) {
        TaskRuntimeReadResult actual = result == null
                ? TaskRuntimeReadResult.unavailable("未取得客户端运行时上下文")
                : result;
        sendRuntimeContext(
                requestId,
                new RuntimeContextResult(snapshot, actual, null)
        );
    }

    /**
     * 把单机存档位置交给 Worker，由 Worker 直接读取当前玩家的 FTBQ SNBT。
     * 游戏侧只发送路径元数据，不读取任务进度，也不接触 knowledge.db。
     */
    public void sendRuntimeFileContext(
            String requestId,
            TaskRuntimeFileDescriptor descriptor
    ) {
        sendRuntimeContext(requestId, RuntimeContextResult.file(descriptor));
    }

    private boolean sendIfReady(JsonObject message) {
        Socket observedSocket = null;
        Process observedProcess = null;
        try {
            synchronized (writeLock) {
                // writer 的快照和实际写入必须在同一锁内完成；断线处理不能在
                // “检查非空”与“使用 writer”之间把它置空。
                if (!ready || writer == null) {
                    return false;
                }
                observedSocket = socket;
                observedProcess = process;
                WorkerProtocol.write(writer, message);
                return true;
            }
        } catch (IOException exception) {
            handleConnectionLost(observedSocket, observedProcess,
                    "ModPedia Worker 写入失败，正在准备重连");
            return false;
        }
    }

    private void send(JsonObject message) throws IOException {
        synchronized (writeLock) {
            BufferedWriter current = writer;
            if (current == null) {
                throw new IOException("Worker 输出未初始化");
            }
            WorkerProtocol.write(current, message);
        }
    }

    private String javaExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void stopProcess() {
        cancelLifecycleTasks();
        Socket currentSocket;
        Process currentProcess;
        synchronized (writeLock) {
            ready = false;
            currentSocket = socket;
            currentProcess = process;
            socket = null;
            writer = null;
            process = null;
        }
        closeQuietly(currentSocket);
        destroyQuietly(currentProcess);
    }

    private String messageOf(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure == null ? "未知错误" : failure.getClass().getSimpleName()
                : failure.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    public record RuntimeContextRequest(String requestId, TaskQuery query) {
    }

    @FunctionalInterface
    public interface RuntimeContextHandler {
        RuntimeContextResult read(RuntimeContextRequest request);
    }

    /** 游戏侧一次运行时读取的不可变结果；不会写入 knowledge.db。 */
    public record RuntimeContextResult(
            TaskRuntimeSnapshot snapshot,
            TaskRuntimeReadResult result,
            TaskRuntimeFileDescriptor runtimeFile
    ) {
        public RuntimeContextResult {
            result = result == null
                    ? TaskRuntimeReadResult.unavailable("未取得客户端运行时上下文")
                    : result;
        }

        public static RuntimeContextResult snapshot(TaskRuntimeReadResult result) {
            TaskRuntimeReadResult actual = result == null
                    ? TaskRuntimeReadResult.unavailable("未取得客户端运行时上下文")
                    : result;
            return new RuntimeContextResult(actual.runtimeSnapshot(), actual, null);
        }

        public static RuntimeContextResult file(TaskRuntimeFileDescriptor descriptor) {
            return new RuntimeContextResult(
                    null,
                    TaskRuntimeReadResult.unavailable("Worker 将直接读取当前单机存档中的任务进度"),
                    descriptor
            );
        }

        /**
         * 单机 Worker 仍从存档读取实时 started/progress，但复用客户端在进入
         * 当前存档时建立、并由 FTBQ 完成事件增量更新的 completed 快照。
         */
        public static RuntimeContextResult file(
                TaskRuntimeFileDescriptor descriptor,
                TaskRuntimeSnapshot completedSnapshot
        ) {
            if (completedSnapshot == null) {
                return file(descriptor);
            }
            return new RuntimeContextResult(
                    completedSnapshot,
                    TaskRuntimeReadResult.read(completedSnapshot),
                    descriptor
            );
        }

        public static RuntimeContextResult unavailable(String message) {
            return new RuntimeContextResult(
                    null,
                    TaskRuntimeReadResult.unavailable(message),
                    null
            );
        }

        public boolean cacheable() {
            return runtimeFile != null && runtimeFile.usable()
                    || result.available() && (snapshot != null || result.runtimeSnapshot() != null);
        }
    }
}
