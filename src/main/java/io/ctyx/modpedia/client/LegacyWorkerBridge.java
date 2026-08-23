package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;
import io.ctyx.modpedia.storage.UserModPediaPaths;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 1.12.2 客户端与 Java 21 Worker 的最小 JSONL 桥接。
 *
 * <p>游戏 JVM 只负责生命周期、UI 和可选 Mod 适配；SQLite、FTS、Markdown
 * 导入后的数据库、会话和 AI 请求都在独立 Worker 进程执行。没有安装同一
 * {@code worker-baseline-3} 时，桥接保持不可用，客户端仍可使用本地搜索回退。</p>
 */
public final class LegacyWorkerBridge {
    private static final int PROTOCOL_VERSION = 1;
    private static final String BASELINE = "worker-baseline-3";
    private static final String WORKER_ONLY_DEPENDENCY_PREFIX = "META-INF/modpedia-worker/";
    private static final String MAIN_ENTRY = "io/ctyx/modpedia/worker/WorkerMain.class";
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
                    send(request);
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
            hello.addProperty("auth_token", token);
            hello.addProperty("worker_api_level", 1);
            hello.addProperty("worker_baseline", BASELINE);
            hello.addProperty("client_adapter", "forge-1.12.2");
            hello.addProperty("client_java", System.getProperty("java.specification.version", "8"));
            hello.add("client_capabilities", capabilities());
            if (!send(hello)) {
                throw new IOException("无法发送 Worker 握手");
            }
            JsonObject ack = read(reader);
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
        String classpath = workerClasspath(instanceRoot);
        if (classpath.length() == 0) {
            throw new IOException("未找到 worker-baseline-3");
        }
        List<String> command = new ArrayList<String>();
        command.add(workerJava());
        command.add("-Dmodpedia.worker=true");
        command.add("-cp");
        command.add(classpath);
        command.add("io.ctyx.modpedia.worker.WorkerMain");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--host");
        command.add(HOST);
        command.add("--config");
        command.add(configDirectory.toString());
        command.add("--knowledge");
        command.add(configDirectory.resolve("modpedia/runtime/knowledge").toString());
        command.add("--content");
        command.add(configDirectory.resolve("modpedia/knowledge").toString());
        command.add("--conversations");
        command.add(paths.conversations().toString());
        command.add("--settings");
        command.add(paths.aiSettings().toString());
        Path log = paths.instanceWorkerRoot().resolve("worker.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("MODPEDIA_WORKER_TOKEN", token);
        builder.directory(instanceRoot.toFile());
        return builder.start();
    }

    private String workerClasspath(Path instanceRoot) throws IOException {
        String override = System.getProperty("modpedia.worker.classpath", "").trim();
        if (!override.isEmpty()) {
            return override;
        }
        Path root = paths.workerLibraryRoot();
        Files.createDirectories(root);

        LinkedHashSet<String> entries = new LinkedHashSet<String>();
        Path packaged = findWorkerArchive(
                instanceRoot == null ? null : instanceRoot.resolve("mods")
        );
        if (packaged != null) {
            if (containsEntry(packaged, MAIN_ENTRY)) {
                entries.add(packaged.toString());
            }
            // 发布 Mod 的外层 JAR 只保存 Worker 包；Worker 包内部再保存
            // META-INF/jarjar 与 META-INF/modpedia-worker 依赖，因此这里递归提取。
            entries.addAll(extractNested(packaged, root));
        }

        List<Path> archives = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            for (Path path : stream.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar") || name.endsWith(".zip")) {
                    archives.add(path);
                }
            }
        }

        for (Path archive : archives) {
            entries.add(archive.toString());
        }
        return join(entries);
    }

    static Path findWorkerArchive(Path modsDirectory) {
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            return null;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(modsDirectory)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar") || name.endsWith(".zip");
                    })
                    .sorted()
                    .collect(Collectors.toList());
            for (Path candidate : candidates) {
                if (containsEntry(candidate, MAIN_ENTRY) || containsWorkerBundle(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private List<String> extractNested(Path archive, Path output) throws IOException {
        List<String> result = new ArrayList<String>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isWorkerDependencyEntry(entry.getName())) {
                    continue;
                }
                Path target = output.resolve(new File(entry.getName()).getName());
                if (!Files.exists(target) || Files.size(target) != entry.getSize()) {
                    InputStream input = zip.getInputStream(entry);
                    try {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        input.close();
                    }
                }
                result.add(target.toString());
                if (containsEntry(target, MAIN_ENTRY)) {
                    result.addAll(extractNested(target, output));
                }
            }
        }
        return result;
    }

    private static boolean isWorkerDependencyEntry(String name) {
        return (name.startsWith("META-INF/jarjar/")
                || name.startsWith(WORKER_ONLY_DEPENDENCY_PREFIX))
                && name.endsWith(".jar");
    }

    private static boolean containsWorkerBundle(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isWorkerDependencyEntry(entry.getName())
                        && containsEntry(zip, entry, MAIN_ENTRY)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static boolean containsEntry(ZipFile outer, ZipEntry nested, String expected)
            throws IOException {
        InputStream input = outer.getInputStream(nested);
        try {
            ZipInputStream jar = new ZipInputStream(input);
            try {
                ZipEntry entry;
                while ((entry = jar.getNextEntry()) != null) {
                    if (expected.equals(entry.getName())) {
                        return true;
                    }
                }
            } finally {
                jar.close();
            }
        } finally {
            input.close();
        }
        return false;
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
                    send(runtimeResponse(requestId));
                    continue;
                }
                if ("recipe_query_request".equals(type)) {
                    dispatchRecipeQuery(requestId, event);
                    continue;
                }
                CompletableFuture<JsonObject> future = responses.get(requestId);
                if (future != null && ("completed".equals(type) || "error".equals(type)
                        || "cancelled".equals(type) || "pong".equals(type)
                        || "conversation_state".equals(type))) {
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

    private String workerJava() {
        List<String> candidates = new ArrayList<String>();
        candidates.add(System.getProperty("modpedia.worker.java", ""));
        candidates.add(System.getenv("MODPEDIA_WORKER_JAVA"));
        candidates.add(System.getenv("JAVA_HOME_21"));
        candidates.add(System.getenv("JAVA_HOME"));
        candidates.add("/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home");
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                Path executable = path.resolve("bin/java");
                if (Files.isExecutable(executable)) {
                    return executable.toString();
                }
            } else if (Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return "java";
    }

    private String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean containsEntry(Path archive, String entry) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.getEntry(entry) != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private String join(LinkedHashSet<String> entries) {
        StringBuilder result = new StringBuilder();
        for (String entry : entries) {
            if (result.length() > 0) {
                result.append(File.pathSeparator);
            }
            result.append(entry);
        }
        return result.toString();
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
