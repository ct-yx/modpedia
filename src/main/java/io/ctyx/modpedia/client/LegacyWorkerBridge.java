package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;
import io.ctyx.modpedia.storage.UserModPediaPaths;
import io.ctyx.modpedia.clientcore.JavaRuntime;
import io.ctyx.modpedia.clientcore.PagesManifestSource;
import io.ctyx.modpedia.clientcore.WorkerInstaller;
import io.ctyx.modpedia.clientcore.WorkerManifest;
import io.ctyx.modpedia.clientcore.WorkerPaths;
import io.ctyx.modpedia.clientcore.WorkerProcessLauncher;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.nio.file.StandardOpenOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 1.12.2 客户端与 Java 21 Worker 的最小 JSONL 桥接。
 *
 * <p>游戏 JVM 只负责生命周期、UI 和可选 Mod 适配；SQLite、FTS、Markdown
 * 导入后的数据库、会话和 AI 请求都在独立 Worker 进程执行。没有安装同一
 * {@code worker-baseline-3} 时，桥接保持不可用，客户端仍可使用本地搜索回退。</p>
 */
public final class LegacyWorkerBridge {
    private static final int PROTOCOL_VERSION = 2;
    private static final int LEGACY_PROTOCOL_VERSION = 1;
    private static final String BASELINE = "worker-baseline-3";
    private static final String WORKER_MANIFEST_URL =
            "https://ct-yx.github.io/modpedia/worker/latest.json";
    private static final String HOST = "127.0.0.1";
    private static final long START_TIMEOUT_MILLIS = 15000L;
    private static final long OPERATION_TIMEOUT_SECONDS = 300L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final LegacyWorkerBridge INSTANCE = new LegacyWorkerBridge();

    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(daemonFactory("ModPedia-1.12-worker"));
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("ModPedia-1.12-worker-timeouts")
    );
    private final Map<String, CompletableFuture<JsonObject>> responses = new ConcurrentHashMap<String, CompletableFuture<JsonObject>>();
    private final Map<String, Consumer<JsonObject>> chatListeners = new ConcurrentHashMap<String, Consumer<JsonObject>>();
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile Path configDirectory;
    private volatile UserModPediaPaths paths;

    private LegacyWorkerBridge() {
    }

    public static LegacyWorkerBridge get() {
        return INSTANCE;
    }

    public void startAsync(final Path configDirectory, final Path instanceRoot, final Runnable callback) {
        if (configDirectory == null || instanceRoot == null || stopping || ready) {
            if (callback != null && ready) {
                runOnClient(callback);
            }
            return;
        }
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.paths = UserModPediaPaths.resolve(this.configDirectory);
        lifecycle.execute(new Runnable() {
            @Override
            public void run() {
                boolean started = startOnce(instanceRoot.toAbsolutePath().normalize());
                if (callback != null && started) {
                    runOnClient(callback);
                }
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    public CompletableFuture<JsonObject> rebuildKnowledge(final Path modsDirectory, final boolean force) {
        JsonObject request = message("knowledge.rebuild", UUID.randomUUID().toString());
        request.addProperty("mods_directory", modsDirectory == null ? "" : modsDirectory.toString());
        request.addProperty("force_rebuild", force);
        return request(request, OPERATION_TIMEOUT_SECONDS);
    }

    public CompletableFuture<JsonObject> loadSettings() {
        return request(message("settings.load", UUID.randomUUID().toString()), 30L);
    }

    public CompletableFuture<JsonObject> saveSettings(JsonObject settings) {
        JsonObject request = message("settings.save", UUID.randomUUID().toString());
        request.add("settings", settings == null ? new JsonObject() : settings);
        return request(request, 30L);
    }

    public CompletableFuture<JsonObject> fetchModels(JsonObject settings) {
        JsonObject request = message("ai.models.fetch", UUID.randomUUID().toString());
        request.add("settings", settings == null ? new JsonObject() : settings);
        return request(request, 90L);
    }

    public CompletableFuture<JsonObject> testConnection(JsonObject settings) {
        JsonObject request = message("ai.connection.test", UUID.randomUUID().toString());
        request.add("settings", settings == null ? new JsonObject() : settings);
        return request(request, 120L);
    }

    public CompletableFuture<JsonObject> listConversations() {
        return request(message("conversation.list", UUID.randomUUID().toString()), 30L);
    }

    public CompletableFuture<JsonObject> newConversation() {
        return request(message("conversation.new", UUID.randomUUID().toString()), 30L);
    }

    public CompletableFuture<JsonObject> selectConversation(String conversationId) {
        JsonObject request = message("conversation.select", UUID.randomUUID().toString());
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        return request(request, 30L);
    }

    public CompletableFuture<JsonObject> renameConversation(String conversationId, String title) {
        JsonObject request = message("conversation.rename", UUID.randomUUID().toString());
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        request.addProperty("title", title == null ? "" : title.trim());
        return request(request, 30L);
    }

    public CompletableFuture<JsonObject> deleteConversation(String conversationId) {
        JsonObject request = message("conversation.delete", UUID.randomUUID().toString());
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        return request(request, 30L);
    }

    public CompletableFuture<JsonObject> clearConversation() {
        return request(message("conversation.clear", UUID.randomUUID().toString()), 30L);
    }

    public CompletableFuture<JsonObject> syncItems(
            final String language,
            final List<LegacyItemCatalogEntry> entries
    ) {
        if (!ready || paths == null) {
            return failed("Worker 不可用");
        }
        final String requestId = UUID.randomUUID().toString();
        final JsonObject request = message("knowledge.items.sync", requestId);
        request.addProperty("language", language == null ? "zh_cn" : language);
        request.addProperty("items_format", "item_catalog_jsonl_v1");
        request.addProperty("item_count", entries == null ? 0 : entries.size());
        final CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        responses.put(requestId, future);
        timeouts.schedule(new Runnable() {
            @Override
            public void run() {
                CompletableFuture<JsonObject> removed = responses.remove(requestId);
                if (removed != null) {
                    removed.completeExceptionally(new IOException("物品目录同步超时"));
                }
            }
        }, OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        lifecycle.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Path payloadRoot = paths.workerPayloadRoot();
                    Files.createDirectories(payloadRoot);
                    Path temporary = payloadRoot.resolve(requestId + ".jsonl.tmp");
                    Path payload = payloadRoot.resolve(requestId + ".jsonl");
                    java.io.BufferedWriter output = Files.newBufferedWriter(
                            temporary, StandardCharsets.UTF_8
                    );
                    try {
                        if (entries != null) {
                            for (LegacyItemCatalogEntry entry : entries) {
                                output.write(itemJson(entry).toString());
                                output.newLine();
                            }
                        }
                    } finally {
                        output.close();
                    }
                    try {
                        Files.move(temporary, payload, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, payload, StandardCopyOption.REPLACE_EXISTING);
                    }
                    request.addProperty("items_file", payload.toString());
                    if (!send(request)) {
                        CompletableFuture<JsonObject> response = responses.remove(requestId);
                        if (response != null) {
                            response.completeExceptionally(new IOException("物品目录同步请求发送失败"));
                        }
                    }
                } catch (IOException exception) {
                    CompletableFuture<JsonObject> response = responses.remove(requestId);
                    if (response != null) {
                        response.completeExceptionally(exception);
                    }
                }
            }
        });
        return future;
    }

    public boolean startChat(
            String prompt,
            String language,
            Consumer<JsonObject> listener
    ) {
        return startChatRequest(prompt, language, "", listener) != null;
    }

    /** 启动聊天并返回请求 ID，供取消按钮和会话切换使用。 */
    public String startChatRequest(
            String prompt,
            String language,
            String conversationId,
            Consumer<JsonObject> listener
    ) {
        if (!ready) {
            return null;
        }
        String requestId = UUID.randomUUID().toString();
        if (listener != null) {
            chatListeners.put(requestId, listener);
        }
        JsonObject request = message("chat.start", requestId);
        request.addProperty("prompt", prompt == null ? "" : prompt);
        request.addProperty("language", language == null ? "auto" : language);
        request.addProperty("conversation_id", conversationId == null ? "" : conversationId);
        if (!send(request)) {
            chatListeners.remove(requestId);
            return null;
        }
        return requestId;
    }

    public boolean cancelChat(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }
        return send(message("chat.cancel", requestId));
    }

    public void shutdown() {
        stopping = true;
        lifecycle.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ready) {
                        send(message("shutdown", UUID.randomUUID().toString()));
                    }
                } finally {
                    stopProcess();
                }
            }
        });
        lifecycle.shutdownNow();
        timeouts.shutdownNow();
    }

    private boolean startOnce(Path instanceRoot) {
        if (stopping || ready) {
            return ready;
        }
        try {
            Path workerDirectory = paths.instanceWorkerRoot();
            Files.createDirectories(workerDirectory);
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(HOST));
            server.setSoTimeout((int) START_TIMEOUT_MILLIS);
            String token = token();
            Process child = launchWorker(server.getLocalPort(), token, instanceRoot);
            process = child;
            Socket accepted;
            try {
                accepted = server.accept();
            } finally {
                server.close();
            }
            accepted.setTcpNoDelay(true);
            socket = accepted;
            writer = new BufferedWriter(new OutputStreamWriter(
                    accepted.getOutputStream(), StandardCharsets.UTF_8
            ));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    accepted.getInputStream(), StandardCharsets.UTF_8
            ));
            JsonObject hello = message("hello", UUID.randomUUID().toString());
            hello.addProperty("handshake_mode", "challenge");
            JsonArray supportedProtocols = new JsonArray();
            supportedProtocols.add(2);
            supportedProtocols.add(1);
            hello.add("supported_protocol_versions", supportedProtocols);
            hello.addProperty("worker_api_level", 1);
            hello.addProperty("worker_baseline", BASELINE);
            hello.addProperty("client_adapter", "cleanroom-1.12.2");
            hello.addProperty("client_java", System.getProperty("java.specification.version", "8"));
            hello.add("client_capabilities", capabilities());
            JsonArray optionalCapabilities = new JsonArray();
            optionalCapabilities.add("runtime_item_context");
            if (LegacyRuntimeMaterialFactsReader.isAvailable()) {
                optionalCapabilities.add("runtime_material_facts");
            }
            hello.add("client_optional_capabilities", optionalCapabilities);
            if (!send(hello)) {
                throw new IOException("无法发送 Worker 握手");
            }
            JsonObject ack = read(reader);
            if (ack != null && "hello_challenge".equals(string(ack, "type"))) {
                String challenge = string(ack, "challenge");
                JsonObject proof = message("hello_proof", hello.get("request_id").getAsString());
                proof.addProperty("challenge", challenge);
                proof.addProperty("proof", challengeProof(token, challenge));
                if (!send(proof)) {
                    throw new IOException("无法发送 Worker challenge proof");
                }
                ack = read(reader);
            }
            if (ack == null || !"hello_ack".equals(string(ack, "type"))
                    || !bool(ack, "accepted", false)
                    || !BASELINE.equals(string(ack, "worker_baseline"))) {
                throw new IOException("Worker 握手失败");
            }
            ready = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(reader);
                }
            }, "ModPedia-1.12-worker-ipc");
            thread.setDaemon(true);
            thread.start();
            ModPedia.LOGGER.info("ModPedia 1.12 Worker 已启动");
            return true;
        } catch (Throwable failure) {
            ModPedia.LOGGER.debug("ModPedia 1.12 Worker 未启动：{}", failure.getMessage());
            stopProcess();
            return false;
        }
    }

    private Process launchWorker(int port, String token, Path instanceRoot) throws IOException {
        Path workerJar = ensureWorkerJar();
        Path javaExecutable = workerJava();
        Map<String, String> environment = Collections.emptyMap();
        Process worker = WorkerProcessLauncher.launch(
                javaExecutable,
                workerJar,
                HOST,
                port,
                configDirectory.toString(),
                token,
                environment
        );
        attachWorkerLog(worker, paths.instanceWorkerRoot().resolve("worker.log"));
        return worker;
    }

    private Path ensureWorkerJar() throws IOException {
        Path workerJar = paths.workerJar();
        byte[] publicKey = null;
        String encoded = System.getProperty("modpedia.worker.publicKey", "").trim();
        if (!encoded.isEmpty()) {
            try {
                publicKey = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Worker Ed25519 公钥不是有效 Base64", exception);
            }
        }
        WorkerInstaller installer = new WorkerInstaller(
                new WorkerPaths(paths.workerInstallRoot()), publicKey
        );
        // 启动前即使已有 current/Worker JAR 也必须重新校验清单、SHA-256 和
        // Ed25519；不能因文件存在而绕过共享安装器的 fail-closed 信任边界。
        if (Files.isRegularFile(workerJar)) {
            installer.verifyInstalled();
            return workerJar;
        }
        WorkerManifest manifest = new PagesManifestSource(
                System.getProperty("modpedia.worker.manifest", WORKER_MANIFEST_URL),
                15_000
        ).fetch();
        return installer.install(manifest, new WorkerInstaller.DefaultHttpSource(30_000)).workerJar();
    }

    private Path workerJava() throws IOException {
        List<String> candidates = new ArrayList<String>();
        candidates.add(System.getProperty("modpedia.worker.java", ""));
        candidates.add(System.getenv("MODPEDIA_WORKER_JAVA"));
        candidates.add(System.getenv("JAVA_HOME_17"));
        candidates.add(System.getenv("JAVA_HOME"));
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            Path path = Paths.get(candidate.trim());
            Path executable = Files.isDirectory(path) ? path.resolve("bin/java") : path;
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                // P0-03：不能仅凭路径猜测 JVM；实际探测必须返回 Java 17+。
                Process probe = new ProcessBuilder(executable.toString(), "-XshowSettings:properties", "-version")
                        .redirectErrorStream(true).start();
                String output;
                try (InputStream input = probe.getInputStream();
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count > 0) bytes.write(buffer, 0, count);
                    }
                    output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
                try {
                    probe.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    probe.destroyForcibly();
                    continue;
                }
                int major = parseJavaMajor(output);
                if (major >= 17) return executable.toAbsolutePath().normalize();
            }
        }
        throw new IOException("找不到 Java 17 Worker 运行时；请设置 modpedia.worker.java 或 MODPEDIA_WORKER_JAVA");
    }

    private int parseJavaMajor(String output) {
        if (output == null) return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("java.specification.version\\s*=\\s*([0-9]+)(?:\\.([0-9]+))?")
                .matcher(output);
        if (!matcher.find()) return -1;
        try {
            int first = Integer.parseInt(matcher.group(1));
            if (first == 1 && matcher.group(2) != null) return Integer.parseInt(matcher.group(2));
            return first;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void attachWorkerLog(Process worker, Path log) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Files.createDirectories(log.getParent());
                    InputStream input = worker.getInputStream();
                    try (java.io.OutputStream output = Files.newOutputStream(log,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND)) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count > 0) output.write(buffer, 0, count);
                        }
                    } finally { input.close(); }
                } catch (IOException ignored) { }
            }
        }, "ModPedia-1.12-worker-log");
        thread.setDaemon(true);
        thread.start();
    }

    private String challengeProof(String token, String challenge) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(challenge.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IOException("HMAC-SHA256 不可用", exception);
        }
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject event = readLine(line);
                if (event == null) {
                    continue;
                }
                String type = string(event, "type");
                String requestId = string(event, "request_id");
                if ("runtime_context_request".equals(type)) {
                    if ("item_tooltip".equals(string(event, "request_kind"))) {
                        dispatchRuntimeItemContext(requestId, event);
                    } else if ("material_facts".equals(string(event, "request_kind"))) {
                        dispatchRuntimeMaterialFacts(requestId, event);
                    } else {
                        send(runtimeResponse(requestId));
                    }
                    continue;
                }
                if ("recipe_query_request".equals(type)) {
                    dispatchRecipeQuery(requestId, event);
                    continue;
                }
                CompletableFuture<JsonObject> future = responses.get(requestId);
                if (future != null && ("completed".equals(type) || "error".equals(type)
                        || "cancelled".equals(type) || "pong".equals(type)
                        || "conversation_state".equals(type)
                        || "conversation.state".equals(type))) {
                    future.complete(event);
                    responses.remove(requestId);
                }
                Consumer<JsonObject> listener = chatListeners.get(requestId);
                if (listener != null) {
                    runOnClient(new Runnable() {
                        @Override
                        public void run() {
                            listener.accept(event);
                        }
                    });
                    if ("completed".equals(type) || "error".equals(type) || "cancelled".equals(type)) {
                        chatListeners.remove(requestId);
                    }
                }
            }
        } catch (IOException ignored) {
            // 断线状态由 ready/process 统一清理；不把 Worker 的原始异常写入游戏日志。
        } finally {
            ready = false;
            for (CompletableFuture<JsonObject> future : responses.values()) {
                future.completeExceptionally(new IOException("Worker 连接已关闭"));
            }
            responses.clear();
        }
    }

    private JsonObject runtimeResponse(String requestId) {
        JsonObject response = message("runtime_context_response", requestId);
        JsonObject snapshot = LegacyFtbQuestRuntimeReader.readSnapshot();
        boolean available = snapshot != null;
        if (available && snapshot.has("available") && snapshot.get("available").isJsonPrimitive()) {
            available = snapshot.get("available").getAsBoolean();
        }
        // Keep the response shape compatible with the current Worker.  The
        // legacy client reads the save directly, but Worker still uses these
        // flags to distinguish an empty snapshot from a failed read.
        response.addProperty("available", available);
        response.addProperty("read", available);
        response.addProperty(
                "message",
                available ? "" : "未找到当前玩家的 FTBQ 运行时任务数据"
        );
        response.add("runtime_context", available ? snapshot : unavailable());
        return response;
    }

    private void dispatchRuntimeItemContext(final String requestId, final JsonObject request) {
        try {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    send(LegacyRuntimeItemContextReader.read(request));
                }
            });
        } catch (Throwable failure) {
            send(LegacyRuntimeItemContextReader.unavailable(requestId, "客户端世界尚未就绪"));
        }
    }

    /** 可选材料模组的注册表只能在客户端线程读取。 */
    private void dispatchRuntimeMaterialFacts(final String requestId, final JsonObject request) {
        try {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    send(LegacyRuntimeMaterialFactsReader.read(request));
                }
            });
        } catch (Throwable failure) {
            JsonObject response = message("runtime_context_response", requestId);
            response.addProperty("request_kind", "material_facts");
            response.addProperty("status", "unavailable");
            response.addProperty("world_ready", false);
            response.add("facts", new JsonArray());
            send(response);
        }
    }

    /** JEI/HEI 的客户端运行时对象必须在 Minecraft 主线程访问。 */
    private void dispatchRecipeQuery(final String requestId, final JsonObject request) {
        try {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    send(LegacyJeiBridge.queryResponse(requestId, request));
                }
            });
        } catch (Throwable failure) {
            send(recipeUnavailableResponse(requestId, "客户端配方界面尚未就绪"));
        }
    }

    private JsonObject recipeUnavailableResponse(String requestId, String message) {
        JsonObject response = message("recipe_query_response", requestId);
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "unavailable");
        payload.addProperty("message", message == null ? "配方查询不可用" : message);
        payload.add("methods", new JsonArray());
        payload.add("recipes", new JsonArray());
        payload.add("machines", new JsonArray());
        response.add("recipe_response", payload);
        return response;
    }

    private CompletableFuture<JsonObject> request(JsonObject request, long timeoutSeconds) {
        if (!ready) {
            return failed("Worker 不可用");
        }
        String requestId = string(request, "request_id");
        CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        responses.put(requestId, future);
        if (!send(request)) {
            responses.remove(requestId);
            future.completeExceptionally(new IOException("Worker 请求发送失败"));
            return future;
        }
        timeouts.schedule(new Runnable() {
            @Override
            public void run() {
                CompletableFuture<JsonObject> removed = responses.remove(requestId);
                if (removed != null) {
                    removed.completeExceptionally(new IOException("Worker 操作超时"));
                }
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        return future;
    }

    private boolean send(JsonObject message) {
        BufferedWriter output = writer;
        if (!ready && !"hello".equals(string(message, "type"))) {
            return false;
        }
        if (output == null) {
            return false;
        }
        synchronized (writeLock) {
            try {
                output.write(message.toString());
                output.newLine();
                output.flush();
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }

    private void stopProcess() {
        ready = false;
        Socket currentSocket = socket;
        socket = null;
        writer = null;
        if (currentSocket != null) {
            try { currentSocket.close(); } catch (IOException ignored) { }
        }
        Process child = process;
        process = null;
        if (child != null) {
            child.destroy();
        }
    }

    private JsonObject message(String type, String requestId) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", PROTOCOL_VERSION);
        result.addProperty("type", type);
        result.addProperty("request_id", requestId);
        result.addProperty("conversation_id", "");
        return result;
    }

    private JsonArray capabilities() {
        JsonArray result = new JsonArray();
        for (String value : Arrays.asList("chat", "knowledge_rebuild", "knowledge_items_sync",
                "runtime_context", "recipe_query", "conversations", "ai_settings")) {
            result.add(value);
        }
        return result;
    }

    private JsonObject itemJson(LegacyItemCatalogEntry entry) {
        JsonObject value = new JsonObject();
        value.addProperty("item_id", entry.getItemId());
        value.addProperty("language", entry.getLanguage());
        value.addProperty("display_name", entry.getDisplayName());
        value.addProperty("description_markdown", entry.getDescriptionMarkdown());
        value.addProperty("source_mod", entry.getSourceMod());
        value.addProperty("fingerprint", entry.getFingerprint());
        return value;
    }

    private JsonObject unavailable() {
        JsonObject value = new JsonObject();
        value.addProperty("available", false);
        return value;
    }

    private JsonObject read(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line == null ? null : readLine(line);
    }

    private JsonObject readLine(String line) {
        try {
            JsonElement element = new JsonParser().parse(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null ? fallback : value.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static CompletableFuture<JsonObject> failed(String message) {
        CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        future.completeExceptionally(new IOException(message));
        return future;
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static void runOnClient(Runnable runnable) {
        try {
            Minecraft.getMinecraft().addScheduledTask(runnable);
        } catch (Throwable ignored) {
            // 只在客户端线程可用时派发 UI 事件；Worker 状态本身不依赖该回调。
        }
    }
}
