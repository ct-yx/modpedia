package io.ctyx.modpedia.worker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AiSettingsStore;
import io.ctyx.modpedia.ai.AiClient;
import io.ctyx.modpedia.ai.AiModelCompatibilityTester;
import io.ctyx.modpedia.ai.ConversationRecord;
import io.ctyx.modpedia.ai.ConversationStore;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.TaskTimelineEntry;
import io.ctyx.modpedia.task.TaskTimelineTracker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker 端 JSONL 服务端。
 *
 * <p>读 socket 的线程只负责协议分发；AI、SQLite、Markdown 和会话任务全部提交到
 * Worker 自己的执行器，绝不占用游戏线程。</p>
 */
public final class WorkerServer {
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");
    private static final Gson JSON = new Gson();
    private static final long RUNTIME_CONTEXT_TIMEOUT_SECONDS = 15L;
    private static final int MAX_ITEM_CATALOG_ENTRIES = 250_000;

    private final Socket socket;
    private final String expectedToken;
    private final Path configDirectory;
    private final Path knowledgeRoot;
    private final Path conversationsRoot;
    private final Path settingsPath;
    /** 非 AI 的短操作也使用有界队列，避免设置/诊断请求在断线时无限增长。 */
    private final ExecutorService operations = new ThreadPoolExecutor(
            1,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "modpedia-worker-operation");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    /** AI 请求是长耗时任务，必须和知识库执行器隔离并设置硬并发/队列上限。 */
    private final WorkerAiExecutor aiOperations = new WorkerAiExecutor();
    /** 所有会改写 knowledge.db 的操作共用一个队列，避免 Wiki 重建请求与首轮构建竞态。 */
    private final ExecutorService knowledgeOperations = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-worker-knowledge");
        thread.setDaemon(true);
        return thread;
    });
    /** 同一时间只保留一个运行中的知识库构建，并把重复请求合并为一次后续构建。 */
    private final KnowledgeOperationGate knowledgeOperationGate = new KnowledgeOperationGate();
    private final ConversationRequestGate conversationRequestGate = new ConversationRequestGate();
    private final Map<String, Future<?>> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, ChatRequestHandle> activeChatRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> runtimeWaiters = new ConcurrentHashMap<>();
    /** 只保留当前存档的上一次进度，用于给无历史日志的 task_progress 补时间线。 */
    private final TaskTimelineTracker runtimeTimelineTracker = new TaskTimelineTracker();
    private final WorkerRequestCancellation requestCancellation = new WorkerRequestCancellation();
    private final Object writeLock = new Object();
    private volatile boolean running = true;
    private BufferedWriter writer;
    private ConversationStore conversationStore;
    private WorkerChatService chatService;
    private AiSettingsStore settingsStore;

    public WorkerServer(
            Socket socket,
            String expectedToken,
            Path configDirectory,
            Path knowledgeRoot,
            Path conversationsRoot,
            Path settingsPath
    ) {
        this.socket = socket;
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.conversationsRoot = conversationsRoot.toAbsolutePath().normalize();
        this.settingsPath = settingsPath.toAbsolutePath().normalize();
    }

    public void run() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8
        ));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8
             ))) {
            writer = output;
            if (!handshake(reader)) {
                return;
            }
            conversationStore = new ConversationStore(conversationsRoot);
            settingsStore = new AiSettingsStore(settingsPath);
            chatService = new WorkerChatService(
                    knowledgeRoot,
                    conversationStore,
                    settingsStore,
                    this::send,
                    this::requestRuntimeContext,
                    this::isCancelled
            );
            sendConversationState("startup", "");
            while (running) {
                JsonObject message = WorkerProtocol.read(reader);
                if (message == null) {
                    break;
                }
                dispatch(message);
            }
        } finally {
            running = false;
            activeRequests.values().forEach(future -> future.cancel(true));
            activeRequests.clear();
            runtimeWaiters.values().forEach(future -> future.completeExceptionally(
                    new IOException("Worker 连接已关闭")
            ));
            runtimeWaiters.clear();
            requestCancellation.clear();
            knowledgeOperations.shutdownNow();
            operations.shutdownNow();
            aiOperations.close();
            conversationRequestGate.clear();
        }
    }

    private boolean handshake(BufferedReader reader) throws IOException {
        JsonObject hello = WorkerProtocol.read(reader);
        if (hello == null || !WorkerProtocol.HELLO.equals(WorkerProtocol.string(hello, "type"))) {
            return false;
        }
        boolean valid = WorkerProtocol.integer(hello, "protocol_version", -1) == WorkerProtocol.VERSION
                && expectedToken.equals(WorkerProtocol.string(hello, "auth_token"));
        JsonObject response = WorkerProtocol.message(
                WorkerProtocol.HELLO_ACK,
                WorkerProtocol.string(hello, "request_id")
        );
        response.addProperty("accepted", valid);
        response.addProperty("worker_pid", ProcessHandle.current().pid());
        if (!valid) {
            response.addProperty("error", "Worker 认证失败或协议版本不匹配");
        }
        send(response);
        return valid;
    }

    private void dispatch(JsonObject message) {
        String type = WorkerProtocol.string(message, "type");
        String requestId = WorkerProtocol.string(message, "request_id");
        if (!WorkerProtocol.isCurrentVersion(message)) {
            if (WorkerProtocol.RUNTIME_CONTEXT_RESPONSE.equals(type)) {
                // 让等待中的任务查询尽快结束；不能把版本错误的快照当作当前进度。
                completeRuntimeContext(message, requestId);
            } else {
                sendProtocolError(requestId, "Worker 协议版本不匹配");
            }
            return;
        }
        switch (type) {
            case WorkerProtocol.PING -> {
                JsonObject pong = WorkerProtocol.message(WorkerProtocol.PONG, requestId);
                pong.addProperty("conversation_id", WorkerProtocol.string(message, "conversation_id"));
                pong.addProperty("worker_pid", ProcessHandle.current().pid());
                sendQuietly(pong);
            }
            case WorkerProtocol.CHAT_START -> submitChat(message, requestId);
            case WorkerProtocol.CHAT_CANCEL -> cancel(requestId);
            case WorkerProtocol.RUNTIME_CONTEXT_RESPONSE -> completeRuntimeContext(message, requestId);
            case WorkerProtocol.KNOWLEDGE_REBUILD -> submitKnowledgeOperation(
                    requestId,
                    "rebuild",
                    () -> rebuildKnowledge(message, requestId)
            );
            case WorkerProtocol.TASK_WIKI_SYNC -> submitKnowledgeOperation(
                    requestId,
                    "wiki",
                    () -> syncTaskWiki(message, requestId)
            );
            case WorkerProtocol.KNOWLEDGE_ITEMS_SYNC -> submitKnowledgeWrite(
                    requestId,
                    () -> syncItems(message, requestId)
            );
            case WorkerProtocol.SETTINGS_LOAD -> submitOperation(
                    requestId,
                    () -> loadSettings(requestId)
            );
            case WorkerProtocol.SETTINGS_SAVE -> submitOperation(
                    requestId,
                    () -> saveSettings(message, requestId)
            );
            case WorkerProtocol.AI_MODELS_FETCH -> submitOperation(
                    requestId,
                    () -> fetchModels(message, requestId)
            );
            case WorkerProtocol.AI_CONNECTION_TEST -> submitOperation(
                    requestId,
                    () -> testConnection(message, requestId)
            );
            case WorkerProtocol.AI_COMPATIBILITY_TEST -> submitOperation(
                    requestId,
                    () -> testAllModels(message, requestId)
            );
            case WorkerProtocol.CONVERSATION_LIST -> sendConversationState("list", requestId);
            case WorkerProtocol.CONVERSATION_NEW -> submitOperation(requestId, () -> {
                conversationStore.create();
                sendConversationState("new", requestId);
            });
            case WorkerProtocol.CONVERSATION_SELECT -> submitOperation(requestId, () -> {
                conversationStore.select(WorkerProtocol.string(message, "conversation_id"));
                sendConversationState("select", requestId);
            });
            case WorkerProtocol.CONVERSATION_RENAME -> submitOperation(requestId, () -> {
                conversationStore.rename(
                        WorkerProtocol.string(message, "conversation_id"),
                        WorkerProtocol.string(message, "title")
                );
                sendConversationState("rename", requestId);
            });
            case WorkerProtocol.CONVERSATION_DELETE -> submitOperation(requestId, () -> {
                conversationStore.delete(WorkerProtocol.string(message, "conversation_id"));
                sendConversationState("delete", requestId);
            });
            case WorkerProtocol.CONVERSATION_CLEAR -> submitOperation(requestId, () -> {
                conversationStore.create();
                sendConversationState("clear", requestId);
            });
            case WorkerProtocol.SHUTDOWN -> {
                sendQuietly(WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId));
                running = false;
            }
            default -> sendError(requestId, "未知 Worker 操作：" + type);
        }
    }

    private void submitChat(JsonObject message, String requestId) {
        if (chatService == null) {
            sendError(requestId, "Worker 尚未初始化");
            return;
        }
        String conversationId = WorkerProtocol.string(message, "conversation_id");
        if (conversationId.isBlank() && conversationStore != null) {
            conversationId = conversationStore.activeId();
        }
        if (!conversationRequestGate.tryAcquire(conversationId, requestId)) {
            sendWorkerBusy(requestId, "当前会话已有 AI 请求正在处理，请稍后重试。");
            return;
        }
        String actualConversationId = conversationId;
        AtomicBoolean started = new AtomicBoolean();
        JsonObject chatMessage = message.deepCopy();
        chatMessage.addProperty("conversation_id", actualConversationId);
        FutureTask<Void> future = new FutureTask<>(() -> {
            started.set(true);
            try {
                chatService.handle(chatMessage);
            } catch (Throwable failure) {
                // AI/网关异常也必须经过统一脱敏，不能把上游 JSON 直接送到 GUI。
                String apiKey = settingsStore == null ? "" : settingsStore.load().effectiveApiKey();
                sendError(requestId, AiClient.friendlyError(failure, apiKey));
            } finally {
                chatService.releaseRequest(requestId);
                conversationRequestGate.release(actualConversationId, requestId);
                activeChatRequests.remove(requestId);
                activeRequests.remove(requestId);
            }
            return null;
        });
        ChatRequestHandle handle = new ChatRequestHandle(actualConversationId, started, future);
        if (activeRequests.putIfAbsent(requestId, future) != null) {
            conversationRequestGate.release(actualConversationId, requestId);
            sendError(requestId, "请求标识已存在，请稍后重试。");
            return;
        }
        activeChatRequests.put(requestId, handle);
        if (!aiOperations.execute(future)) {
            activeChatRequests.remove(requestId, handle);
            activeRequests.remove(requestId, future);
            conversationRequestGate.release(actualConversationId, requestId);
            sendWorkerBusy(requestId, "Worker 忙，请稍后重试。");
        }
    }

    private void cancel(String requestId) {
        boolean firstCancellation = requestCancellation.cancel(requestId);
        Future<?> future = activeRequests.get(requestId);
        if (future != null) {
            future.cancel(true);
            // FutureTask 在尚未开始执行时不会进入 callable 的 finally；这里主动
            // 移除，避免快速取消的请求长期留在 activeRequests 中。
            activeRequests.remove(requestId, future);
        }
        ChatRequestHandle chat = activeChatRequests.get(requestId);
        if (chat != null && future != null && !chat.started().get()) {
            activeChatRequests.remove(requestId, chat);
            conversationRequestGate.release(chat.conversationId(), requestId);
        }
        if (!firstCancellation) {
            return;
        }
        JsonObject cancelled = WorkerProtocol.message(WorkerProtocol.CANCELLED, requestId);
        cancelled.addProperty("message", "请求已取消");
        sendQuietly(cancelled);
    }

    private void completeRuntimeContext(JsonObject message, String requestId) {
        CompletableFuture<JsonObject> waiter = runtimeWaiters.remove(requestId);
        if (waiter != null) {
            waiter.complete(message);
        }
    }

    /** Worker 搜索工具在真正命中任务问题时调用；调用返回后才会查询 task_* 表。 */
    private TaskRuntimeSnapshot requestRuntimeContext(
            String requestId,
            String conversationId,
            TaskQuery query
    ) {
        // 任务查询的顺序不能与静态知识检索混淆：单机先把存档文件描述交给
        // Worker，由 Worker 直接读取极小的 FTBQ SNBT；只有本地文件不可用时，
        // 游戏 JVM 才回退读取 TeamData。收到临时快照后才继续执行 task_* SQL。
        sendStatus(requestId, "task_runtime_read", "正在读取玩家当前任务进度……");
        String contextRequestId = requestId + ":runtime:" + UUID.randomUUID();
        CompletableFuture<JsonObject> waiter = new CompletableFuture<>();
        runtimeWaiters.put(contextRequestId, waiter);
        JsonObject request = WorkerProtocol.message(
                WorkerProtocol.RUNTIME_CONTEXT_REQUEST,
                contextRequestId
        );
        request.addProperty("chat_request_id", requestId);
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        request.add("query", io.ctyx.modpedia.protocol.WorkerPayloadCodec.taskQuery(query));
        sendQuietly(request);
        try {
            JsonObject response = waiter.get(RUNTIME_CONTEXT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            TaskRuntimeSnapshot snapshot = WorkerPayloadCodec.runtimeSnapshot(
                    response.getAsJsonObject("runtime_context")
            );
            TaskRuntimeFileDescriptor descriptor = WorkerPayloadCodec.runtimeFileDescriptor(
                    response.has("runtime_file") && response.get("runtime_file").isJsonObject()
                            ? response.getAsJsonObject("runtime_file")
                            : null
            );
            if (descriptor != null) {
                sendStatus(requestId, "task_runtime_file_read", "正在读取本地 FTBQ 任务存档……");
                TaskRuntimeSnapshot fileSnapshot = WorkerTaskRuntimeFileReader.read(descriptor, query)
                        .orElse(null);
                snapshot = mergeRuntimeSnapshots(snapshot, fileSnapshot);
            }
            snapshot = appendProgressTimeline(snapshot);
            sendStatus(
                    requestId,
                    "task_database_query",
                    snapshot == null
                            ? "当前进度未取得，正在查询静态任务数据库……"
                            : "当前任务进度已读取，正在查询静态任务数据库……"
            );
            return snapshot;
        } catch (Exception exception) {
            sendStatus(requestId, "task_database_query", "读取进度超时，正在查询静态任务数据库……");
            return null;
        } finally {
            runtimeWaiters.remove(contextRequestId);
        }
    }

    /**
     * 单机存档文件提供本次查询的实时 started/progress，客户端快照提供进入
     * 世界后缓存并由完成事件增量更新的 completed。两者合并后再进入 task_* 查询。
     */
    private TaskRuntimeSnapshot mergeRuntimeSnapshots(
            TaskRuntimeSnapshot completedSnapshot,
            TaskRuntimeSnapshot fileSnapshot
    ) {
        if (completedSnapshot == null) {
            return fileSnapshot;
        }
        if (fileSnapshot == null) {
            return completedSnapshot;
        }
        LinkedHashSet<String> completed = new LinkedHashSet<>(completedSnapshot.completedQuestIds());
        completed.addAll(fileSnapshot.completedQuestIds());
        List<TaskTimelineEntry> timeline = new ArrayList<>(completedSnapshot.timeline());
        fileSnapshot.timeline().forEach(entry -> {
            if (!timeline.contains(entry)) {
                timeline.add(entry);
            }
        });
        return new TaskRuntimeSnapshot(
                fileSnapshot.sourceKey().isBlank() ? completedSnapshot.sourceKey() : fileSnapshot.sourceKey(),
                fileSnapshot.scopeKey().isBlank() ? completedSnapshot.scopeKey() : fileSnapshot.scopeKey(),
                fileSnapshot.version().isBlank() ? completedSnapshot.version() : fileSnapshot.version(),
                fileSnapshot.startedQuestIds(),
                List.copyOf(completed),
                fileSnapshot.taskProgress(),
                timeline
        );
    }

    /**
     * FTBQ 的 task_progress 只有当前值，没有历史时间。Worker 只保留当前
     * scope 的上一份小型 Map，在两次任务查询之间检测变化并记录检测时间；
     * 这份状态随 Worker 生命周期结束，不进入 knowledge.db。
     */
    private TaskRuntimeSnapshot appendProgressTimeline(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.scopeKey().isBlank()) {
            return snapshot;
        }
        List<TaskTimelineEntry> progressChanges = runtimeTimelineTracker.detect(
                snapshot.scopeKey(), snapshot.taskProgress());
        if (progressChanges.isEmpty()) {
            return snapshot;
        }
        List<TaskTimelineEntry> timeline = new ArrayList<>(snapshot.timeline());
        progressChanges.forEach(entry -> {
            if (!timeline.contains(entry)) {
                timeline.add(entry);
            }
        });
        return new TaskRuntimeSnapshot(
                snapshot.sourceKey(),
                snapshot.scopeKey(),
                snapshot.version(),
                snapshot.startedQuestIds(),
                snapshot.completedQuestIds(),
                snapshot.taskProgress(),
                timeline
        );
    }

    private void rebuildKnowledge(JsonObject message, String requestId) {
        sendStatus(requestId, "knowledge_rebuild", "正在 Worker 中构建本地知识库");
        try {
            Path modsDirectory = Path.of(WorkerProtocol.string(message, "mods_directory"));
            boolean force = WorkerProtocol.bool(message, "force_rebuild", false);
            WorkerKnowledgeService.BuildResult result = new WorkerKnowledgeService(
                    configDirectory,
                    knowledgeRoot
            ).rebuild(modsDirectory, force);
            if (!result.successful()) {
                sendError(requestId, result.failureMessage().isBlank()
                        ? "知识库构建未完成"
                        : result.failureMessage());
                return;
            }
            new WorkerTaskWikiService(knowledgeRoot).clearPendingRebuild();
            JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
            completed.addProperty("operation", "knowledge.rebuild");
            completed.addProperty("source_count", result.sourceCount());
            completed.addProperty("document_count", result.documentCount());
            completed.addProperty("warning_count", result.warningCount());
            completed.addProperty("mods_directory", result.modsDirectory());
            completed.addProperty("archive_count", result.archiveCount());
            completed.addProperty("resource_count", result.resourceCount());
            send(completed);
        } catch (Throwable failure) {
            sendError(requestId, "知识库构建失败：" + messageOf(failure));
        }
    }

    private void syncTaskWiki(JsonObject message, String requestId) {
        sendStatus(requestId, "task_wiki_sync", "正在由 Worker 更新任务 Wiki");
        try {
            WorkerTaskWikiService.SyncResult wiki = new WorkerTaskWikiService(knowledgeRoot)
                    .synchronize(WorkerProtocol.string(message, "url"));
            WorkerKnowledgeService.BuildResult build = null;
            if (wiki.rebuildRequired()) {
                sendStatus(requestId, "knowledge_rebuild", "任务 Wiki 已变化，正在重建本地知识库");
                Path modsDirectory = Path.of(WorkerProtocol.string(message, "mods_directory"));
                build = new WorkerKnowledgeService(configDirectory, knowledgeRoot)
                        .rebuild(modsDirectory, false);
            }
            if (build != null && !build.successful()) {
                // 本次 Wiki 文件已经准备好，但导入/SQLite 失败。把“需要再
                // 试一次”写在 Wiki 来源旁；下一次 Worker bootstrap 或 Wiki
                // 请求会重新进入构建，而不是因内容指纹未变化被静默跳过。
                new WorkerTaskWikiService(knowledgeRoot).markPendingRebuild();
                sendError(requestId, build.failureMessage().isBlank()
                        ? "任务 Wiki 已更新，但知识库未完成重建"
                        : build.failureMessage());
                return;
            }
            if (build != null) {
                new WorkerTaskWikiService(knowledgeRoot).clearPendingRebuild();
            }
            JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
            completed.addProperty("operation", WorkerProtocol.TASK_WIKI_SYNC);
            completed.addProperty("changed", wiki.changed());
            completed.addProperty("downloaded", wiki.downloaded());
            completed.addProperty("message", wiki.message());
            if (build != null) {
                completed.addProperty("source_count", build.sourceCount());
                completed.addProperty("document_count", build.documentCount());
                completed.addProperty("warning_count", build.warningCount());
                completed.addProperty("mods_directory", build.modsDirectory());
                completed.addProperty("archive_count", build.archiveCount());
                completed.addProperty("resource_count", build.resourceCount());
            }
            send(completed);
        } catch (Throwable failure) {
            sendError(requestId, "任务 Wiki 同步失败：" + messageOf(failure));
        }
    }

    private void syncItems(JsonObject message, String requestId) {
        Path payload = null;
        try {
            long readStarted = System.nanoTime();
            String payloadPath = WorkerProtocol.string(message, "items_file");
            List<ItemCatalogEntry> entries;
            if (!payloadPath.isBlank()) {
                payload = Path.of(payloadPath).toAbsolutePath().normalize();
                Path payloadRoot = configDirectory.resolve("modpedia").resolve("worker")
                        .resolve("payloads").toAbsolutePath().normalize();
                if (!payload.startsWith(payloadRoot)) {
                    throw new IOException("物品目录批量载荷路径不在 Worker payload 目录内");
                }
                entries = readItemCatalogPayload(
                        payload,
                        WorkerProtocol.integer(message, "item_count", -1)
                );
            } else {
                // 保留旧的内联形式，便于早期开发夹具和同版本回滚；生产客户端
                // 使用 items_file，避免把数万条 Tooltip 拼成一条超大 IPC 消息。
                JsonArray values = WorkerPayloadCodec.array(message, "items");
                entries = new ArrayList<>();
                for (JsonElement element : values) {
                    if (element.isJsonObject()) {
                        entries.add(WorkerPayloadCodec.item(element.getAsJsonObject()));
                    }
                }
            }
            long payloadReadMillis = elapsedMillis(readStarted);
            long databaseStarted = System.nanoTime();
            KnowledgeDatabase.ItemCatalogSyncResult result = KnowledgeDatabase.syncItemCatalog(
                    knowledgeRoot,
                    WorkerProtocol.string(message, "language"),
                    entries
            );
            long databaseWriteMillis = elapsedMillis(databaseStarted);
            JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
            completed.addProperty("operation", WorkerProtocol.KNOWLEDGE_ITEMS_SYNC);
            completed.addProperty("language", result.language());
            completed.addProperty("item_count", result.itemCount());
            completed.addProperty("updated_count", result.updatedCount());
            completed.addProperty("reused_count", result.reusedCount());
            completed.addProperty("removed_count", result.removedCount());
            completed.addProperty("payload_read_ms", payloadReadMillis);
            completed.addProperty("database_write_ms", databaseWriteMillis);
            send(completed);
            LOG.info(
                    "Item catalog sync completed: items=" + result.itemCount()
                            + ", payload_read_ms=" + payloadReadMillis
                            + ", database_write_ms=" + databaseWriteMillis
            );
        } catch (Throwable failure) {
            sendError(requestId, "物品目录同步失败：" + messageOf(failure));
        } finally {
            if (payload != null) {
                try {
                    Files.deleteIfExists(payload);
                } catch (IOException cleanupFailure) {
                    LOG.log(Level.FINE, "物品目录批量载荷清理失败: " + payload, cleanupFailure);
                }
            }
        }
    }

    private List<ItemCatalogEntry> readItemCatalogPayload(Path payload, int expectedCount) throws IOException {
        if (!Files.isRegularFile(payload)) {
            throw new IOException("物品目录批量载荷不存在: " + payload);
        }
        if (expectedCount > MAX_ITEM_CATALOG_ENTRIES) {
            throw new IOException("物品目录批量载荷条目数超过上限: " + expectedCount);
        }
        List<ItemCatalogEntry> entries = new ArrayList<>(Math.max(0, expectedCount));
        try (BufferedReader input = Files.newBufferedReader(payload, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = input.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject item = WorkerProtocol.parse(line);
                    entries.add(WorkerPayloadCodec.item(item));
                    if (entries.size() > MAX_ITEM_CATALOG_ENTRIES) {
                        throw new IOException("物品目录批量载荷条目数超过上限");
                    }
                } catch (RuntimeException failure) {
                    throw new IOException("物品目录批量载荷第 " + lineNumber + " 行损坏", failure);
                }
            }
        }
        if (expectedCount >= 0 && entries.size() != expectedCount) {
            throw new IOException(
                    "物品目录批量载荷数量不匹配: expected=" + expectedCount
                            + ", actual=" + entries.size()
            );
        }
        return entries;
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private void loadSettings(String requestId) throws IOException {
        AiSettings settings = settingsStore.load();
        JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
        completed.addProperty("operation", WorkerProtocol.SETTINGS_LOAD);
        completed.add("settings", WorkerPayloadCodec.aiSettings(settings));
        send(completed);
    }

    private void saveSettings(JsonObject message, String requestId) throws IOException {
        AiSettings settings = WorkerPayloadCodec.aiSettings(
                object(message, "settings")
        );
        if (!settingsStore.save(settings)) {
            sendError(requestId, "设置保存失败，请检查当前实例的配置目录");
            return;
        }
        JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
        completed.addProperty("operation", WorkerProtocol.SETTINGS_SAVE);
        completed.add("settings", WorkerPayloadCodec.aiSettings(settings));
        send(completed);
    }

    private void fetchModels(JsonObject message, String requestId) {
        AiSettings settings = WorkerPayloadCodec.aiSettings(object(message, "settings"));
        AiClient.fetchModels(settings, result -> {
            JsonObject completed = WorkerProtocol.message(
                    result.failed() ? WorkerProtocol.ERROR : WorkerProtocol.COMPLETED,
                    requestId
            );
            completed.addProperty("operation", WorkerProtocol.AI_MODELS_FETCH);
            completed.addProperty("failed", result.failed());
            completed.addProperty("message", result.message());
            JsonArray models = new JsonArray();
            result.models().forEach(model -> {
                JsonObject value = new JsonObject();
                value.addProperty("id", model.id());
                value.addProperty("owned_by", model.ownedBy());
                models.add(value);
            });
            completed.add("models", models);
            sendQuietly(completed);
        });
    }

    private void testConnection(JsonObject message, String requestId) {
        AiSettings settings = WorkerPayloadCodec.aiSettings(object(message, "settings"));
        AiClient.testConnection(settings, result -> {
            JsonObject completed = WorkerProtocol.message(
                    result.failed() ? WorkerProtocol.ERROR : WorkerProtocol.COMPLETED,
                    requestId
            );
            completed.addProperty("operation", WorkerProtocol.AI_CONNECTION_TEST);
            completed.addProperty("failed", result.failed());
            completed.addProperty("message", result.message());
            completed.addProperty("status_code", result.statusCode());
            completed.addProperty("attempts", result.attempts());
            sendQuietly(completed);
        });
    }

    private void testAllModels(JsonObject message, String requestId) {
        AiSettings settings = WorkerPayloadCodec.aiSettings(object(message, "settings"));
        Path reportDirectory = configDirectory.resolve("modpedia").resolve("diagnostics");
        AiClient.testAllModels(settings, reportDirectory, 2, result -> {
            JsonObject completed = WorkerProtocol.message(
                    result.failed() ? WorkerProtocol.ERROR : WorkerProtocol.COMPLETED,
                    requestId
            );
            completed.addProperty("operation", WorkerProtocol.AI_COMPATIBILITY_TEST);
            completed.addProperty("failed", result.failed());
            completed.addProperty("message", result.message());
            completed.addProperty("report_path", result.reportPath());
            if (result.report() != null) {
                completed.add("report", JSON.toJsonTree(result.report()));
            }
            sendQuietly(completed);
        });
    }

    private JsonObject object(JsonObject value, String name) {
        JsonElement element = value == null ? null : value.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private void submitOperation(String requestId, ThrowingRunnable operation) {
        submitOperation(operations, requestId, operation);
    }

    private void submitKnowledgeOperation(
            String requestId,
            String coalesceKey,
            ThrowingRunnable operation
    ) {
        KnowledgeOperationGate.Submission submission = knowledgeOperationGate.submit(
                requestId,
                coalesceKey,
                operation::run
        );
        if (!submission.started()) {
            if (submission.superseded()) {
                // 合并规则：保留最新路径/请求参数，向旧请求发送已合并终态，
                // 避免 F9 连按或 Wiki 更新把完整扫描排成长队。
                sendKnowledgeSuperseded(submission.supersededRequestId());
            }
            sendKnowledgeCoalesced(requestId);
            return;
        }
        submitKnowledgeOperationToExecutor(requestId, operation::run);
    }

    /**
     * 物品目录不是“可丢弃的重复重建请求”。它必须在当前知识写入完成后
     * 依次执行，因此进入同一个单线程 executor，但不进入只保留一个 pending
     * 的 rebuild/wiki gate。
     */
    private void submitKnowledgeWrite(String requestId, ThrowingRunnable operation) {
        submitOperation(knowledgeOperations, requestId, operation);
    }

    private void submitKnowledgeOperationToExecutor(
            String requestId,
            KnowledgeOperationGate.Operation operation
    ) {
        submitOperation(knowledgeOperations, requestId, () -> {
            try {
                operation.run();
            } finally {
                submitPendingKnowledgeOperation();
            }
        });
    }

    private void submitPendingKnowledgeOperation() {
        KnowledgeOperationGate.Pending next = knowledgeOperationGate.finish();
        if (next == null) {
            return;
        }
        submitKnowledgeOperationToExecutor(next.requestId(), next.operation());
    }

    private void sendKnowledgeCoalesced(String requestId) {
        JsonObject status = WorkerProtocol.message(WorkerProtocol.STATUS, requestId);
        status.addProperty("phase", "knowledge_rebuild_coalesced");
        status.addProperty("message", "已有知识库构建正在进行，已合并为下一次构建");
        sendQuietly(status);
    }

    private void sendKnowledgeSuperseded(String requestId) {
        JsonObject completed = WorkerProtocol.message(WorkerProtocol.COMPLETED, requestId);
        // 这是被后续请求替代的排队请求，不代表当前正在执行的构建完成。
        // 客户端必须保留 updating 状态，直到真正运行的请求发出
        // knowledge.rebuild 完成事件。
        completed.addProperty("operation", WorkerProtocol.KNOWLEDGE_REBUILD_COALESCED);
        completed.addProperty("coalesced", true);
        completed.addProperty("message", "重复知识库构建请求已合并");
        sendQuietly(completed);
    }

    private void submitOperation(
            ExecutorService executor,
            String requestId,
            ThrowingRunnable operation
    ) {
        FutureTask<Void> future = new FutureTask<>(() -> {
            try {
                operation.run();
            } catch (Throwable failure) {
                sendError(requestId, messageOf(failure));
            }
            finally {
                activeRequests.remove(requestId);
            }
            return null;
        });
        if (activeRequests.putIfAbsent(requestId, future) != null) {
            sendError(requestId, "请求标识已存在，请稍后重试。");
            return;
        }
        try {
            executor.execute(future);
        } catch (RejectedExecutionException rejected) {
            activeRequests.remove(requestId, future);
            sendWorkerBusy(requestId, "Worker 忙，请稍后重试。");
        }
    }

    private void sendConversationState(String operation, String requestId) {
        if (conversationStore == null) {
            return;
        }
        ConversationRecord active = conversationStore.active();
        JsonObject event = WorkerProtocol.message(WorkerProtocol.CONVERSATION_STATE, requestId);
        event.addProperty("operation", operation);
        event.addProperty("active_conversation_id", conversationStore.activeId());
        event.addProperty("active_title", active.title());
        JsonArray messages = new JsonArray();
        active.messages().forEach(message -> messages.add(WorkerPayloadCodec.chatMessage(message)));
        event.add("messages", messages);
        JsonArray summaries = new JsonArray();
        conversationStore.summaries().forEach(summary -> summaries.add(WorkerPayloadCodec.summary(summary)));
        event.add("conversations", summaries);
        sendQuietly(event);
    }

    void send(JsonObject message) throws IOException {
        if (!requestCancellation.allows(message)) {
            return;
        }
        synchronized (writeLock) {
            if (writer == null) {
                throw new IOException("Worker 输出未初始化");
            }
            WorkerProtocol.write(writer, message);
        }
    }

    private void sendQuietly(JsonObject message) {
        try {
            send(message);
        } catch (IOException exception) {
            running = false;
            LOG.log(Level.FINE, "Worker 输出失败", exception);
        }
    }

    private void sendStatus(String requestId, String phase, String message) {
        JsonObject status = WorkerProtocol.message(WorkerProtocol.STATUS, requestId);
        status.addProperty("phase", phase);
        status.addProperty("message", message);
        sendQuietly(status);
    }

    private void sendError(String requestId, String message) {
        JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, requestId);
        error.addProperty("message", message == null || message.isBlank() ? "Worker 请求失败" : message);
        sendQuietly(error);
    }

    private void sendWorkerBusy(String requestId, String message) {
        JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, requestId);
        error.addProperty("code", "WORKER_BUSY");
        error.addProperty("retryable", true);
        error.addProperty("message", message == null || message.isBlank()
                ? "Worker 忙，请稍后重试。"
                : message);
        sendQuietly(error);
    }

    private void sendProtocolError(String requestId, String message) {
        JsonObject error = WorkerProtocol.message(WorkerProtocol.ERROR, requestId);
        error.addProperty("code", "PROTOCOL_VERSION_MISMATCH");
        error.addProperty("expected_protocol_version", WorkerProtocol.VERSION);
        error.addProperty("message", message);
        sendQuietly(error);
    }

    private boolean isCancelled(String requestId) {
        return requestCancellation.isCancelled(requestId);
    }

    private String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? failure == null ? "未知错误" : failure.getClass().getSimpleName()
                : current.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record ChatRequestHandle(
            String conversationId,
            AtomicBoolean started,
            FutureTask<Void> future
    ) {
    }
}
