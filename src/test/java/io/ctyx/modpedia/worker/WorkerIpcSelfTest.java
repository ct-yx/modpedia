package io.ctyx.modpedia.worker;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ctyx.modpedia.compat.WorkerCompatibility;
import io.ctyx.modpedia.compat.WorkerLibraryVerifier;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.storage.ModPediaPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/**
 * 实际启动独立 Worker JVM 的 JSONL 协议回归；不调用模型、不访问外网。
 *
 * <p>测试使用最终 JAR 和其中的 Jar-in-Jar 依赖，避免只验证游戏开发环境的
 * classpath。Worker 启动日志同时用于检查客户端 UI 类没有被 Worker 解析。</p>
 */
public final class WorkerIpcSelfTest {
    private static final int START_TIMEOUT_MILLIS = 8_000;
    private static final int PING_SAMPLES = 200;
    private static final int ITEM_SYNC_ENTRIES = 20_000;
    private static final String CONVERSATION_ID = "ipc-self-test-conversation";

    private WorkerIpcSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-worker-ipc-");
        boolean passed = false;
        try (AiFixture aiFixture = AiFixture.start()) {
            checkEnvelope();
            checkCancellationGate();
            verifyPublishedJarDoesNotBundleRuntimeGson();
            verifyRejectedHandshake(root.resolve("bad-token"), "token-ok", "token-bad", false);
            verifyRejectedHandshake(root.resolve("bad-version"), "token-version", "token-version", true);
            verifyRejectedCompatibilityHandshake(root.resolve("bad-baseline"));

            try (Harness harness = Harness.start(root.resolve("valid"), "token-valid")) {
                JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "hello-valid");
                hello.addProperty("auth_token", "token-valid");
                WorkerCompatibility.addClientHello(hello);
                hello.addProperty("conversation_id", CONVERSATION_ID);
                harness.send(hello);
                JsonObject ack = harness.read(event -> WorkerProtocol.HELLO_ACK.equals(
                        WorkerProtocol.string(event, "type")));
                check(WorkerProtocol.bool(ack, "accepted", false), "正确 Token 应通过握手");
                check(WorkerProtocol.isCurrentVersion(ack), "握手响应应带当前协议版本");
                check(CONVERSATION_ID.equals(WorkerProtocol.string(hello, "conversation_id")),
                        "握手请求应保留 conversation_id");

                JsonObject startup = harness.read(event -> WorkerProtocol.CONVERSATION_STATE.equals(
                        WorkerProtocol.string(event, "type")));
                check(WorkerProtocol.isCurrentVersion(startup), "启动事件应带当前协议版本");

                JsonObject settings = WorkerProtocol.message(WorkerProtocol.SETTINGS_SAVE, "settings-search-only");
                settings.addProperty("conversation_id", CONVERSATION_ID);
                JsonObject settingsPayload = new JsonObject();
                settingsPayload.addProperty("mode", "SEARCH_ONLY");
                settingsPayload.addProperty("endpoint", "");
                settingsPayload.addProperty("model", "");
                settingsPayload.addProperty("api_key", "");
                settingsPayload.addProperty("streaming", false);
                settingsPayload.addProperty("intensity", "FAST");
                settingsPayload.addProperty("max_rounds", 1);
                settingsPayload.addProperty("max_results", 4);
                settingsPayload.addProperty("max_context_chars", 8_000);
                settingsPayload.addProperty("timeout_seconds", 10);
                settings.add("settings", settingsPayload);
                harness.send(settings);
                JsonObject settingsSaved = harness.read(event ->
                        WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                                && "settings-search-only".equals(WorkerProtocol.string(event, "request_id")));
                check(WorkerProtocol.SETTINGS_SAVE.equals(
                        WorkerProtocol.string(settingsSaved, "operation")),
                        "Worker 应能在独立 JVM 保存仅搜索设置");

                // 先由 Worker 自己构建测试知识库，再开始聊天。仅搜索查询不会
                // 凭空创建数据库；这样既避免测试误把回退路径当成 Worker 数据层，
                // 也保持生产链路中的“Worker 构建/打开数据库”边界。
                JsonObject rebuild = WorkerProtocol.message(
                        WorkerProtocol.KNOWLEDGE_REBUILD,
                        "knowledge-rebuild"
                );
                rebuild.addProperty("mods_directory", harness.modsDirectory.toString());
                rebuild.addProperty("force_rebuild", true);
                harness.send(rebuild);
                JsonObject rebuilt = harness.read(event ->
                        WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                                && "knowledge-rebuild".equals(WorkerProtocol.string(event, "request_id"))
                                && "knowledge.rebuild".equals(WorkerProtocol.string(event, "operation")));
                check(WorkerProtocol.isCurrentVersion(rebuilt), "知识库构建完成事件应带当前协议版本");
                check(Files.isRegularFile(harness.knowledgeRoot.resolve("knowledge.db")),
                        "知识库构建完成后应由 Worker 创建 knowledge.db");

                double itemSyncMillis = verifyItemCatalogFileSync(harness);

                JsonObject chat = WorkerProtocol.message(WorkerProtocol.CHAT_START, "chat-search-only");
                chat.addProperty("conversation_id", WorkerProtocol.string(
                        startup, "active_conversation_id"));
                chat.addProperty("prompt", "IPC 本地夹具查询");
                chat.addProperty("language", "zh_cn");
                harness.send(chat);
                JsonObject chatCompleted = harness.read(event ->
                        WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                                && "chat-search-only".equals(WorkerProtocol.string(event, "request_id")));
                check(!WorkerProtocol.string(chatCompleted, "answer").isBlank(),
                        "chat.start 应通过 Worker 返回仅搜索结果");
                check(chatCompleted.has("messages"), "chat.start 完成事件应携带会话快照");
                check(Files.isRegularFile(harness.knowledgeRoot.resolve("knowledge.db")),
                        "knowledge.db 应由 Worker 进程创建或打开");

                verifyProcessAiChain(harness, aiFixture, startup);
                verifyProcessAi503Failure(harness, aiFixture, startup);

                JsonObject cancel = WorkerProtocol.message(WorkerProtocol.CHAT_CANCEL, "cancel-unknown");
                cancel.addProperty("conversation_id", CONVERSATION_ID);
                harness.send(cancel);
                JsonObject cancelled = harness.read(event ->
                        WorkerProtocol.CANCELLED.equals(WorkerProtocol.string(event, "type"))
                                && "cancel-unknown".equals(WorkerProtocol.string(event, "request_id")));
                check(WorkerProtocol.isCurrentVersion(cancelled), "取消事件应带当前协议版本");

                verifyPing(harness, "ping-one");
                verifyEventEnvelope();
                double[] latency = benchmarkPing(harness);
                check(latency[1] <= 5.0,
                        "IPC p95 超过 5 ms：" + formatLatency(latency));

                JsonObject shutdown = WorkerProtocol.message(WorkerProtocol.SHUTDOWN, "shutdown-valid");
                shutdown.addProperty("conversation_id", CONVERSATION_ID);
                harness.send(shutdown);
                JsonObject completed = harness.read(event ->
                        WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                                && "shutdown-valid".equals(WorkerProtocol.string(event, "request_id")));
                check(WorkerProtocol.isCurrentVersion(completed), "关闭响应应带当前协议版本");
                check(harness.process.waitFor(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "Worker 收到 shutdown 后应退出");
                harness.assertNoClientUiClasses();
                System.out.println(
                        "Worker IPC latency " + formatLatency(latency)
                                + String.format(java.util.Locale.ROOT,
                                ", item catalog %d entries %.2f ms",
                                ITEM_SYNC_ENTRIES, itemSyncMillis)
                );
            }

            verifyConversationRestart(root.resolve("conversation-restart"));

            System.out.println("ModPedia Worker IPC self-test passed");
            passed = true;
        } finally {
            if (passed) {
                deleteTree(root);
            } else {
                System.err.println("Worker IPC self-test artifacts retained for diagnosis: " + root);
            }
        }
    }

    private static void verifyPublishedJarDoesNotBundleRuntimeGson() throws IOException {
        String value = System.getProperty("modpedia.worker.jar", "").strip();
        if (value.isBlank() || !Files.isRegularFile(Path.of(value))) {
            return;
        }
        try (ZipFile zip = new ZipFile(Path.of(value).toFile())) {
            boolean bundled = zip.stream()
                    .anyMatch(entry -> entry.getName().matches(
                            "META-INF/jarjar/.*/?gson[^/]*\\.jar"
                    ));
            check(!bundled,
                    "发布 JAR 不应嵌入第二份 Gson 模块，否则会触发 NeoForge 模块解析冲突");
            boolean bundledSlf4j = zip.stream()
                    .anyMatch(entry -> entry.getName().matches(
                            "META-INF/jarjar/.*/?slf4j-api[^/]*\\.jar"
                    ));
            check(!bundledSlf4j,
                    "发布 JAR 不应嵌入第二份 SLF4J API，否则会与整合包日志模块冲突");
        }
    }

    private static void checkEnvelope() {
        JsonObject message = WorkerProtocol.message(WorkerProtocol.PING, "envelope");
        check(WorkerProtocol.VERSION == WorkerProtocol.integer(message, "protocol_version", -1),
                "协议消息必须带 protocol_version");
        check("envelope".equals(WorkerProtocol.string(message, "request_id")),
                "协议消息必须保留 request_id");
        check(message.has("conversation_id"), "协议消息必须带 conversation_id");
        check(WorkerProtocol.isCurrentVersion(message), "当前消息应通过版本检查");
    }

    private static void checkCancellationGate() {
        WorkerRequestCancellation gate = new WorkerRequestCancellation();
        check(gate.cancel("cancelled-request"), "首次取消应记录请求状态");
        check(!gate.cancel("cancelled-request"), "重复取消不应产生第二个取消状态");

        for (String type : List.of(
                WorkerProtocol.STATUS,
                WorkerProtocol.TOOL_CALL,
                WorkerProtocol.TOOL_RESULT,
                WorkerProtocol.TEXT_DELTA,
                WorkerProtocol.COMPLETED,
                WorkerProtocol.ERROR
        )) {
            JsonObject late = WorkerProtocol.message(type, "cancelled-request");
            check(!gate.allows(late), "取消后不得放行迟到事件：" + type);
        }
        JsonObject cancelled = WorkerProtocol.message(
                WorkerProtocol.CANCELLED,
                "cancelled-request"
        );
        check(gate.allows(cancelled), "取消终态本身必须放行");

        JsonObject runtimeRequest = WorkerProtocol.message(
                WorkerProtocol.RUNTIME_CONTEXT_REQUEST,
                "runtime-subrequest"
        );
        runtimeRequest.addProperty("chat_request_id", "cancelled-request");
        check(!gate.allows(runtimeRequest), "取消后不得继续向游戏请求运行时上下文");

        JsonObject unrelated = WorkerProtocol.message(WorkerProtocol.PING, "other-request");
        check(gate.allows(unrelated), "取消一个请求不得影响其他请求");

        for (int index = 0; index < WorkerRequestCancellation.MAX_ENTRIES + 128; index++) {
            gate.cancel("bounded-cancel-" + index);
        }
        check(gate.sizeForTest() <= WorkerRequestCancellation.MAX_ENTRIES,
                "取消状态必须有界，不能随 Worker 生命周期无限增长");
    }

    private static void verifyRejectedHandshake(
            Path root,
            String expectedToken,
            String suppliedToken,
            boolean wrongVersion
    ) throws Exception {
        try (Harness harness = Harness.start(root, expectedToken)) {
            JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "hello-rejected");
            hello.addProperty("auth_token", suppliedToken);
            WorkerCompatibility.addClientHello(hello);
            if (wrongVersion) {
                hello.addProperty("protocol_version", WorkerProtocol.VERSION + 1);
            }
            harness.send(hello);
            JsonObject ack = harness.read(event -> WorkerProtocol.HELLO_ACK.equals(
                    WorkerProtocol.string(event, "type")));
            check(!WorkerProtocol.bool(ack, "accepted", true),
                    wrongVersion ? "错误协议版本应拒绝握手" : "错误 Token 应拒绝握手");
            check(!WorkerProtocol.string(ack, "error").isBlank(),
                    "拒绝握手应返回错误说明");
            check(harness.process.waitFor(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "拒绝握手后 Worker 应退出");
        }
    }

    private static void verifyRejectedCompatibilityHandshake(Path root) throws Exception {
        try (Harness harness = Harness.start(root, "token-compatibility")) {
            JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "hello-bad-baseline");
            hello.addProperty("auth_token", "token-compatibility");
            WorkerCompatibility.addClientHello(hello);
            hello.addProperty("worker_baseline", "worker-baseline-999");
            harness.send(hello);
            JsonObject ack = harness.read(event -> WorkerProtocol.HELLO_ACK.equals(
                    WorkerProtocol.string(event, "type")));
            check(!WorkerProtocol.bool(ack, "accepted", true), "错误 Worker 基线应拒绝握手");
            check(WorkerProtocol.string(ack, "error").contains("兼容层"),
                    "兼容层拒绝应返回明确错误");
            check(harness.process.waitFor(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "兼容层不匹配后 Worker 应退出");
        }
    }

    /** 关闭 Worker 后用同一配置目录重启，确认历史索引和会话消息仍可恢复。 */
    private static void verifyConversationRestart(Path root) throws Exception {
        String conversationId;
        try (Harness harness = Harness.start(root, "token-restart")) {
            JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "restart-hello-one");
            hello.addProperty("auth_token", "token-restart");
            WorkerCompatibility.addClientHello(hello);
            harness.send(hello);
            JsonObject ack = harness.read(event -> WorkerProtocol.HELLO_ACK.equals(
                    WorkerProtocol.string(event, "type")));
            check(WorkerProtocol.bool(ack, "accepted", false), "重启测试第一次握手应成功");
            JsonObject startup = harness.read(event -> WorkerProtocol.CONVERSATION_STATE.equals(
                    WorkerProtocol.string(event, "type")));
            conversationId = WorkerProtocol.string(startup, "active_conversation_id");
            check(!conversationId.isBlank(), "Worker 启动时应创建可持久化的活动会话");

            JsonObject rename = WorkerProtocol.message(
                    WorkerProtocol.CONVERSATION_RENAME,
                    "restart-rename"
            );
            rename.addProperty("conversation_id", conversationId);
            rename.addProperty("title", "Worker 重启恢复");
            harness.send(rename);
            JsonObject renamed = harness.read(event ->
                    WorkerProtocol.CONVERSATION_STATE.equals(WorkerProtocol.string(event, "type"))
                            && "restart-rename".equals(WorkerProtocol.string(event, "request_id")));
            check("Worker 重启恢复".equals(WorkerProtocol.string(renamed, "active_title")),
                    "会话重命名应在 Worker 关闭前持久化");

            // 不配置真实模型，仍让 Worker 写入一条用户消息；随后重启检查历史正文。
            JsonObject chat = WorkerProtocol.message(WorkerProtocol.CHAT_START, "restart-message");
            chat.addProperty("conversation_id", conversationId);
            chat.addProperty("prompt", "重启后仍应保留的会话消息");
            chat.addProperty("language", "zh_cn");
            harness.send(chat);
            JsonObject error = harness.read(event ->
                    WorkerProtocol.ERROR.equals(WorkerProtocol.string(event, "type"))
                            && "restart-message".equals(WorkerProtocol.string(event, "request_id")));
            check(!WorkerProtocol.string(error, "message").isBlank(),
                    "未配置模型时仍应返回明确错误并完成历史写入");

            JsonObject shutdown = WorkerProtocol.message(WorkerProtocol.SHUTDOWN, "restart-shutdown-one");
            shutdown.addProperty("conversation_id", conversationId);
            harness.send(shutdown);
            harness.read(event -> WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                    && "restart-shutdown-one".equals(WorkerProtocol.string(event, "request_id")));
            check(harness.process.waitFor(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "第一次 Worker 应在重启测试中正常退出");
        }

        try (Harness harness = Harness.start(root, "token-restart")) {
            JsonObject hello = WorkerProtocol.message("hello", "restart-hello-two");
            hello.addProperty("auth_token", "token-restart");
            WorkerCompatibility.addClientHello(hello);
            harness.send(hello);
            JsonObject ack = harness.read(event -> WorkerProtocol.HELLO_ACK.equals(
                    WorkerProtocol.string(event, "type")));
            check(WorkerProtocol.bool(ack, "accepted", false), "重启测试第二次握手应成功");
            JsonObject startup = harness.read(event -> WorkerProtocol.CONVERSATION_STATE.equals(
                    WorkerProtocol.string(event, "type")));
            check(conversationId.equals(WorkerProtocol.string(startup, "active_conversation_id")),
                    "Worker 重启后应恢复原活动会话");
            check("Worker 重启恢复".equals(WorkerProtocol.string(startup, "active_title")),
                    "Worker 重启后应恢复会话标题");
            String messages = WorkerPayloadCodec.array(startup, "messages").toString();
            check(messages.contains("重启后仍应保留的会话消息"),
                    "Worker 重启后应恢复持久化的用户消息");

            JsonObject shutdown = WorkerProtocol.message(WorkerProtocol.SHUTDOWN, "restart-shutdown-two");
            shutdown.addProperty("conversation_id", conversationId);
            harness.send(shutdown);
            harness.read(event -> WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                    && "restart-shutdown-two".equals(WorkerProtocol.string(event, "request_id")));
            check(harness.process.waitFor(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "第二次 Worker 应在重启测试中正常退出");
        }
        System.out.println("Worker conversation restart self-test passed");
    }

    private static void verifyPing(Harness harness, String requestId) throws Exception {
        JsonObject ping = WorkerProtocol.message(WorkerProtocol.PING, requestId);
        ping.addProperty("conversation_id", CONVERSATION_ID);
        harness.send(ping);
        JsonObject pong = harness.read(event -> WorkerProtocol.PONG.equals(
                WorkerProtocol.string(event, "type")) && requestId.equals(
                WorkerProtocol.string(event, "request_id")));
        check(WorkerProtocol.isCurrentVersion(pong), "pong 应带当前协议版本");
        check(CONVERSATION_ID.equals(WorkerProtocol.string(pong, "conversation_id")),
                "pong 应回传 conversation_id");
        check(WorkerProtocol.longValue(pong, "worker_pid", -1) > 0,
                "pong 应返回独立 Worker PID");
    }

    private static double verifyItemCatalogFileSync(Harness harness) throws Exception {
        Path payload = harness.knowledgeRoot.getParent()
                .resolve("worker").resolve("payloads").resolve("items-file-sync.jsonl");
        Files.createDirectories(payload.getParent());
        try (BufferedWriter output = Files.newBufferedWriter(payload, StandardCharsets.UTF_8)) {
            for (int index = 0; index < ITEM_SYNC_ENTRIES; index++) {
                output.write(WorkerPayloadCodec.item(new ItemCatalogEntry(
                        "fixture:item_" + index,
                        "zh_cn",
                        "夹具物品 " + index,
                        "- 用于 Worker 批量导入性能测试",
                        "fixture",
                        "worker-fingerprint-" + index
                )).toString());
                output.newLine();
            }
        }

        JsonObject request = WorkerProtocol.message(
                WorkerProtocol.KNOWLEDGE_ITEMS_SYNC,
                "items-file-sync"
        );
        request.addProperty("language", "zh_cn");
        request.addProperty("items_file", payload.toAbsolutePath().normalize().toString());
        request.addProperty("items_format", "item_catalog_jsonl_v1");
        request.addProperty("item_count", ITEM_SYNC_ENTRIES);
        long started = System.nanoTime();
        harness.send(request);
        JsonObject completed = harness.read(event ->
                WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                        && "items-file-sync".equals(WorkerProtocol.string(event, "request_id")));
        double elapsedMillis = (System.nanoTime() - started) / 1_000_000D;
        check(WorkerProtocol.KNOWLEDGE_ITEMS_SYNC.equals(
                        WorkerProtocol.string(completed, "operation")),
                "Worker 文件载荷同步应返回物品目录操作结果");
        check(WorkerProtocol.integer(completed, "item_count", -1) == ITEM_SYNC_ENTRIES,
                "Worker 文件载荷同步应保留全部物品");
        check(elapsedMillis <= 2_000D,
                String.format(java.util.Locale.ROOT,
                        "Worker 物品目录批量同步不应超过 2 秒，实际 %.2f ms",
                        elapsedMillis));
        System.out.printf(
                java.util.Locale.ROOT,
                "Worker item payload: total=%.2f ms, payload_read=%d ms, database_write=%d ms%n",
                elapsedMillis,
                WorkerProtocol.longValue(completed, "payload_read_ms", -1),
                WorkerProtocol.longValue(completed, "database_write_ms", -1)
        );
        return elapsedMillis;
    }

    private static void verifyProcessAiChain(
            Harness harness,
            AiFixture fixture,
            JsonObject startup
    ) throws Exception {
        saveAiSettings(harness, fixture.endpoint(), false, "settings-worker-ai-blocking");
        String conversationId = WorkerProtocol.string(startup, "active_conversation_id");

        JsonObject connectionTest = WorkerProtocol.message(
                WorkerProtocol.AI_CONNECTION_TEST,
                "ai-connection-test"
        );
        connectionTest.add("settings", aiSettings(fixture.endpoint(), false));
        harness.send(connectionTest);
        JsonObject connectionResult = harness.read(event ->
                (WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                        || WorkerProtocol.ERROR.equals(WorkerProtocol.string(event, "type")))
                        && "ai-connection-test".equals(WorkerProtocol.string(event, "request_id")));
        check(!WorkerProtocol.bool(connectionResult, "failed", true),
                "Worker IPC 的连接测试应成功");
        check(WorkerProtocol.integer(connectionResult, "status_code", 0) == 200,
                "Worker IPC 的连接测试应返回 HTTP 状态码");
        check(WorkerProtocol.integer(connectionResult, "attempts", 0) == 1,
                "连接测试成功时不应重复发送");

        JsonObject blocking = WorkerProtocol.message(WorkerProtocol.CHAT_START, "chat-worker-blocking");
        blocking.addProperty("conversation_id", conversationId);
        blocking.addProperty("prompt", "请通过本地工具查询 IPC 夹具");
        blocking.addProperty("language", "zh_cn");
        harness.send(blocking);
        JsonObject toolEvent = harness.read(event ->
                WorkerProtocol.TOOL_CALL.equals(WorkerProtocol.string(event, "type"))
                        && "chat-worker-blocking".equals(WorkerProtocol.string(event, "request_id")));
        check("search_knowledge".equals(WorkerProtocol.string(toolEvent, "tool")),
                "独立 Worker 的阻塞 AI 链路应实际调用本地搜索工具");
        JsonObject blockingCompleted = harness.read(event ->
                WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                        && "chat-worker-blocking".equals(WorkerProtocol.string(event, "request_id")));
        check("worker blocking answer".equals(WorkerProtocol.string(blockingCompleted, "answer")),
                "独立 Worker 应完成工具结果续接并返回最终回答");

        saveAiSettings(harness, fixture.endpoint(), true, "settings-worker-ai-streaming");
        JsonObject streaming = WorkerProtocol.message(WorkerProtocol.CHAT_START, "chat-worker-streaming");
        streaming.addProperty("conversation_id", conversationId);
        streaming.addProperty("prompt", "请流式查询 IPC 夹具");
        streaming.addProperty("language", "zh_cn");
        harness.send(streaming);
        boolean sawTool = false;
        boolean sawDelta = false;
        JsonObject streamingCompleted = null;
        while (streamingCompleted == null) {
            JsonObject event = harness.read(ignored -> true);
            if (!"chat-worker-streaming".equals(WorkerProtocol.string(event, "request_id"))) {
                continue;
            }
            if (WorkerProtocol.TOOL_CALL.equals(WorkerProtocol.string(event, "type"))) {
                sawTool = "search_knowledge".equals(WorkerProtocol.string(event, "tool"));
            } else if (WorkerProtocol.TEXT_DELTA.equals(WorkerProtocol.string(event, "type"))) {
                sawDelta = sawDelta || !WorkerProtocol.string(event, "text").isBlank();
            } else if (WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))) {
                streamingCompleted = event;
            }
        }
        check(sawTool, "独立 Worker 的流式 AI 链路应实际调用本地搜索工具");
        check(sawDelta, "独立 Worker 应通过 IPC 返回流式文本增量");
        check("worker streaming answer".equals(
                        WorkerProtocol.string(streamingCompleted, "answer")),
                "独立 Worker 应完成流式工具续接并返回最终回答");
        check(fixture.requestCount() >= 4,
                "进程级 AI 夹具应收到阻塞和流式两轮模型请求");
    }

    private static void verifyProcessAi503Failure(
            Harness harness,
            AiFixture fixture,
            JsonObject startup
    ) throws Exception {
        saveAiSettings(harness, fixture.endpoint(), false, "settings-worker-ai-503");
        fixture.setChatUnavailable(true);
        int before = fixture.requestCount();
        try {
            JsonObject request = WorkerProtocol.message(WorkerProtocol.CHAT_START, "chat-worker-503");
            request.addProperty("conversation_id", WorkerProtocol.string(
                    startup, "active_conversation_id"));
            request.addProperty("prompt", "请查询一个会触发 503 的夹具问题");
            request.addProperty("language", "zh_cn");
            harness.send(request);
            JsonObject error = harness.read(event ->
                    WorkerProtocol.ERROR.equals(WorkerProtocol.string(event, "type"))
                            && "chat-worker-503".equals(WorkerProtocol.string(event, "request_id")));
            String message = WorkerProtocol.string(error, "message");
            check(message.contains("HTTP 503"), "聊天 503 应向客户端返回 HTTP 状态码");
            check(message.contains("Service temporarily unavailable"),
                    "聊天 503 应保留脱敏后的上游错误原因");
            check(!message.contains("{\"error\""),
                    "聊天错误不应把上游原始 JSON 直接显示给玩家");
            check(!message.contains("fixture-key"), "聊天错误不得泄露 API Key");
            check(fixture.requestCount() == before + 1,
                    "上游 503 时 Worker 不应在短时间内立即重复聊天请求");
        } finally {
            fixture.setChatUnavailable(false);
        }
    }

    private static void saveAiSettings(
            Harness harness,
            String endpoint,
            boolean streaming,
            String requestId
    ) throws Exception {
        JsonObject settings = WorkerProtocol.message(WorkerProtocol.SETTINGS_SAVE, requestId);
        settings.add("settings", aiSettings(endpoint, streaming));
        harness.send(settings);
        JsonObject saved = harness.read(event ->
                WorkerProtocol.COMPLETED.equals(WorkerProtocol.string(event, "type"))
                        && requestId.equals(WorkerProtocol.string(event, "request_id")));
        check(WorkerProtocol.SETTINGS_SAVE.equals(WorkerProtocol.string(saved, "operation")),
                "进程级 AI 夹具设置应保存成功");
    }

    private static JsonObject aiSettings(String endpoint, boolean streaming) {
        JsonObject value = new JsonObject();
        value.addProperty("mode", "AI");
        value.addProperty("endpoint", endpoint);
        value.addProperty("model", "fixture-model");
        value.addProperty("api_key", "fixture-key");
        value.addProperty("streaming", streaming);
        value.addProperty("intensity", "FAST");
        value.addProperty("max_rounds", 2);
        value.addProperty("max_results", 4);
        value.addProperty("max_context_chars", 8_000);
        value.addProperty("timeout_seconds", 10);
        return value;
    }

    private static final class AiFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger blockingRequests = new AtomicInteger();
        private final AtomicInteger streamingRequests = new AtomicInteger();
        private volatile boolean chatUnavailable;

        private AiFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static AiFixture start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "modpedia-worker-ai-fixture");
                thread.setDaemon(true);
                return thread;
            });
            AiFixture fixture = new AiFixture(server, executor);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", fixture::handle);
            server.start();
            return fixture;
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private int requestCount() {
            return requests.size();
        }

        private void setChatUnavailable(boolean value) {
            chatUnavailable = value;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            requests.add(request);
            String compact = request.replaceAll("\\s+", "");
            boolean streaming = compact.contains("\"stream\":true");
            if (chatUnavailable && compact.contains("\"tools\"")) {
                byte[] body = "{\"error\":{\"message\":\"Service temporarily unavailable\",\"type\":\"api_error\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
                return;
            }
            String response;
            // 连接测试使用不带 tools 的最小请求；不要让它消耗后续阻塞对话
            // 夹具的“工具调用 → 最终回答”序列。
            if (!streaming && !compact.contains("\"tools\"")) {
                response = "{\"id\":\"worker-connection\",\"choices\":[{\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\"OK\"},"
                        + "\"finish_reason\":\"stop\"}]}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else if (streaming) {
                response = streamingRequests.getAndIncrement() % 2 == 1
                        ? streamTextResponse()
                        : streamToolResponse();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            } else {
                response = blockingRequests.getAndIncrement() % 2 == 1
                        ? blockingTextResponse()
                        : blockingToolResponse();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private static String blockingToolResponse() {
            return "{\"id\":\"worker-fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"worker-call\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"IPC\\\",\\\"language\\\":\\\"zh_cn\\\",\\\"limit\\\":4,\\\"focus\\\":\\\"related\\\",\\\"exclude_document_ids\\\":[]}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        }

        private static String blockingTextResponse() {
            return "{\"id\":\"worker-fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"worker blocking answer\"},\"finish_reason\":\"stop\"}]}";
        }

        private static String streamToolResponse() {
            String first = "{\"query\":\"IPC\"";
            String second = ",\"language\":\"zh_cn\",\"limit\":4,\"focus\":\"related\",\"exclude_document_ids\":[]}";
            return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"worker-stream-call\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\""
                    + escape(first)
                    + "\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\""
                    + escape(second)
                    + "\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
        }

        private static String streamTextResponse() {
            return "data: {\"choices\":[{\"delta\":{\"content\":\"worker \"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"streaming answer\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void verifyEventEnvelope() {
        for (String type : List.of(
                WorkerProtocol.STATUS,
                WorkerProtocol.TOOL_CALL,
                WorkerProtocol.TOOL_RESULT,
                WorkerProtocol.TEXT_DELTA,
                WorkerProtocol.COMPLETED,
                WorkerProtocol.ERROR,
                WorkerProtocol.CANCELLED
        )) {
            JsonObject event = WorkerProtocol.message(type, "event-" + type);
            event.addProperty("conversation_id", CONVERSATION_ID);
            check(WorkerProtocol.isCurrentVersion(event), type + " 事件应带当前协议版本");
            check(CONVERSATION_ID.equals(WorkerProtocol.string(event, "conversation_id")),
                    type + " 事件应带 conversation_id");
        }
    }

    private static double[] benchmarkPing(Harness harness) throws Exception {
        // 先预热，避免把首次类加载和 socket 缓存建立计入稳定 IPC 指标。
        for (int index = 0; index < 20; index++) {
            verifyPing(harness, "warmup-" + index);
        }
        List<Double> samples = new ArrayList<>(PING_SAMPLES);
        for (int index = 0; index < PING_SAMPLES; index++) {
            String requestId = "benchmark-" + index;
            JsonObject ping = WorkerProtocol.message(WorkerProtocol.PING, requestId);
            ping.addProperty("conversation_id", CONVERSATION_ID);
            long started = System.nanoTime();
            harness.send(ping);
            harness.read(event -> WorkerProtocol.PONG.equals(WorkerProtocol.string(event, "type"))
                    && requestId.equals(WorkerProtocol.string(event, "request_id")));
            samples.add((System.nanoTime() - started) / 1_000_000.0);
        }
        samples.sort(Double::compareTo);
        return new double[]{
                percentile(samples, 0.50),
                percentile(samples, 0.95),
                percentile(samples, 0.99)
        };
    }

    private static double percentile(List<Double> sorted, double fraction) {
        int index = Math.max(0, Math.min(sorted.size() - 1,
                (int) Math.ceil(sorted.size() * fraction) - 1));
        return sorted.get(index);
    }

    private static String formatLatency(double[] latency) {
        return String.format(
                java.util.Locale.ROOT,
                "p50=%.3f ms, p95=%.3f ms, p99=%.3f ms",
                latency[0], latency[1], latency[2]
        );
    }

    private static final class Harness implements AutoCloseable {
        private final ServerSocket listener;
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final Process process;
        private final Path classLog;
        private final Path knowledgeRoot;
        private final Path contentRoot;
        private final Path modsDirectory;

        private Harness(
                ServerSocket listener,
                Socket socket,
                BufferedReader reader,
                BufferedWriter writer,
                Process process,
                Path classLog,
                Path knowledgeRoot,
                Path contentRoot,
                Path modsDirectory
        ) {
            this.listener = listener;
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
            this.process = process;
            this.classLog = classLog;
            this.knowledgeRoot = knowledgeRoot;
            this.contentRoot = contentRoot;
            this.modsDirectory = modsDirectory;
        }

        private static Harness start(Path root, String token) throws Exception {
            Path config = root.resolve("config");
            // 测试实例必须把用户级共享配置注入到夹具目录，不能改写维护者真实的 ~/.modpedia/ai.json。
            ModPediaPaths paths = ModPediaPaths.forConfig(config, root.resolve("user-home"));
            Path knowledge = paths.runtimeKnowledgeRoot();
            Path content = paths.contentRoot();
            Path conversations = paths.conversationsRoot();
            Path settings = paths.aiSettings();
            Path mods = root.resolve("mods");
            Path logs = root.resolve("logs");
            Files.createDirectories(knowledge);
            Files.createDirectories(content);
            Files.createDirectories(conversations);
            Files.createDirectories(mods);
            Files.createDirectories(logs);
            Path custom = content.resolve("custom/ipc-guide.md");
            Files.createDirectories(custom.getParent());
            Files.writeString(custom, "---\n"
                    + "id: fixture:ipc-guide\n"
                    + "language: zh_cn\n"
                    + "title: IPC 夹具指南\n"
                    + "---\n"
                    + "# IPC 本地夹具查询\n\n"
                    + "用于验证 content/knowledge 与 runtime/knowledge 分离。\n",
                    StandardCharsets.UTF_8);
            Path outputLog = logs.resolve("worker.log");
            Path classLog = logs.resolve("worker-classes.log");

            ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Process process = null;
            try {
                process = launch(listener.getLocalPort(), token, config, knowledge, content, conversations, settings,
                        outputLog, classLog, root.resolve("lib"));
                listener.setSoTimeout(START_TIMEOUT_MILLIS);
                Socket socket = listener.accept();
                socket.setSoTimeout(START_TIMEOUT_MILLIS);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                return new Harness(listener, socket, reader, writer, process, classLog,
                        knowledge, content, mods);
            } catch (Throwable failure) {
                if (process != null) {
                    destroy(process);
                }
                listener.close();
                throw failure;
            }
        }

        private static Process launch(
                int port,
                String token,
                Path config,
                Path knowledge,
                Path content,
                Path conversations,
                Path settings,
                Path outputLog,
                Path classLog,
                Path libraryDirectory
        ) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-Dmodpedia.worker=true");
            command.add("-Xlog:class+load=info:file=" + classLog.toAbsolutePath());
            command.add("-cp");
            command.add(workerClasspath(libraryDirectory));
            command.add(WorkerMain.class.getName());
            command.add("--port");
            command.add(Integer.toString(port));
            command.add("--config");
            command.add(config.toString());
            command.add("--knowledge");
            command.add(knowledge.toString());
            command.add("--content");
            command.add(content.toString());
            command.add("--conversations");
            command.add(conversations.toString());
            command.add("--settings");
            command.add(settings.toString());
            String publishedJar = System.getProperty("modpedia.worker.jar", "").strip();
            if (!publishedJar.isBlank() && Files.isRegularFile(Path.of(publishedJar))) {
                command.add("--worker-library");
                command.add(libraryDirectory.toAbsolutePath().normalize().toString());
                command.add("--worker-baseline");
                command.add(WorkerCompatibility.WORKER_LIBRARY_BASELINE);
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("MODPEDIA_WORKER_TOKEN", token);
            return builder
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(outputLog.toFile()))
                    .start();
        }

        private static String workerClasspath(Path libraryDirectory) throws IOException {
            String jarProperty = System.getProperty("modpedia.worker.jar", "").strip();
            if (jarProperty.isBlank() || !Files.isRegularFile(Path.of(jarProperty))) {
                return System.getProperty("java.class.path");
            }
            Path jar = Path.of(jarProperty).toAbsolutePath().normalize();
            Files.createDirectories(libraryDirectory);
            List<String> entries = new ArrayList<>();
            entries.add(jar.toString());
            entries.addAll(WorkerLibraryVerifier.synchronize(jar, libraryDirectory).classpath()
                    .stream().map(Path::toString).toList());
            // 发布 JAR 不再携带 SLF4J；真实游戏由 NeoForge 模块层提供，测试进程
            // 从自身的编译 classpath 取出同一份 API，模拟生产 Worker 的装配方式。
            addClassLocation(entries, "org.slf4j.LoggerFactory");
            // Gson 由 Minecraft/NeoForge 运行时提供，发布 JAR 不再携带第二份
            // com.google.gson 模块；测试也必须模拟生产 Worker 的 classpath。
            addClassLocation(entries, "com.google.gson.Gson");
            return String.join(java.io.File.pathSeparator, entries);
        }

        private static void addClassLocation(List<String> entries, String className) {
            try {
                Class<?> type = Class.forName(className, false, WorkerIpcSelfTest.class.getClassLoader());
                if (type.getProtectionDomain().getCodeSource() == null) {
                    return;
                }
                Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(location)) {
                    entries.add(location.toString());
                }
            } catch (Exception exception) {
                throw new IllegalStateException("测试需要可定位的 SLF4J API：" + className, exception);
            }
        }


        private void send(JsonObject value) throws IOException {
            WorkerProtocol.write(writer, value);
        }

        private JsonObject read(Predicate<JsonObject> predicate) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MILLIS);
            while (System.nanoTime() < deadline) {
                JsonObject value = WorkerProtocol.read(reader);
                if (value == null) {
                    throw new IOException("Worker 在返回预期事件前关闭连接");
                }
                if (predicate.test(value)) {
                    return value;
                }
            }
            throw new IOException("读取 Worker 事件超时");
        }

        private void assertNoClientUiClasses() throws IOException {
            if (!Files.isRegularFile(classLog)) {
                throw new IOException("Worker 类加载日志未生成：" + classLog);
            }
            String log = Files.readString(classLog, StandardCharsets.UTF_8);
            for (String forbidden : List.of(
                    "io.ctyx.modpedia.client.Assistant",
                    "io.ctyx.modpedia.client.FloatingAssistantWindow",
                    "io.ctyx.modpedia.client.ModPediaClient",
                    "io.ctyx.modpedia.client.ManualSourceNavigator",
                    "io.ctyx.modpedia.client.Jade",
                    "io.ctyx.modpedia.client.Jei",
                    "io.ctyx.modpedia.client.ItemCatalog",
                    "net.minecraft.",
                    "net.neoforged.",
                    "ModernUI",
                    "modernui"
            )) {
                check(!log.contains(forbidden), "Worker 启动解析了客户端 UI 类：" + forbidden);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                socket.close();
            } finally {
                listener.close();
                destroy(process);
            }
        }
    }

    private static String javaExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
