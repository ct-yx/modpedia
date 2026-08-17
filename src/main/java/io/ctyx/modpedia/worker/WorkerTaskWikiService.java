package io.ctyx.modpedia.worker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.logging.Logger;

/** Worker 端任务 Wiki 来源管理；网络和文件写入不进入游戏 JVM。 */
public final class WorkerTaskWikiService {
    public static final String SOURCE_ID = "ftbquests-wiki";
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/FTBTeam/FTB-Quests/1.21.1/main/README.md";
    static final int MAX_WIKI_BYTES = 4 * 1024 * 1024;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");
    private final HttpClient httpClient;
    private final Path knowledgeRoot;

    public WorkerTaskWikiService(Path knowledgeRoot) {
        this(
                knowledgeRoot,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
        );
    }

    WorkerTaskWikiService(Path knowledgeRoot, HttpClient httpClient) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.httpClient = httpClient;
    }

    /** 启动构建前准备内置副本，不访问网络。 */
    public boolean prepareLocal() throws IOException {
        Paths paths = paths();
        Files.createDirectories(paths.documentsRoot());
        writeDescriptorIfMissing(paths.descriptor());
        if (Files.isRegularFile(paths.document())) {
            return false;
        }
        String builtIn = readBuiltInWiki();
        if (builtIn.isBlank()) {
            return false;
        }
        atomicWrite(paths.document(), builtIn);
        return true;
    }

    /** 下载远程 Wiki；失败时保留上一份本地副本。 */
    public SyncResult synchronize(String requestedUrl) throws IOException {
        // changed 只表示本地事实源的内容发生变化；待重建标记是独立的状态。
        // 如果把二者合并，成功下载后未清理 pending marker 的下一次相同内容
        // 同步会被错误报告为 changed=true，调用方就会不断重复触发重建。
        boolean changed = prepareLocal();
        boolean rebuildRequired = changed || hasPendingRebuild();
        Paths paths = paths();
        String url = requestedUrl == null || requestedUrl.isBlank()
                ? DEFAULT_URL : requestedUrl.strip();
        if (url.isBlank() || "disabled".equalsIgnoreCase(url)) {
            if (rebuildRequired) {
                markPendingRebuild();
            }
            return new SyncResult(changed, false, "远程任务 Wiki 已禁用", rebuildRequired);
        }
        String remote = download(url);
        if (remote.isBlank()) {
            if (rebuildRequired) {
                markPendingRebuild();
            }
            return new SyncResult(
                    changed,
                    false,
                    "远程任务 Wiki 不可用，继续使用本地副本",
                    rebuildRequired
            );
        }
        String previous = read(paths.document());
        if (!remote.equals(previous)) {
            atomicWrite(paths.document(), remote);
            changed = true;
            rebuildRequired = true;
        }
        if (rebuildRequired) {
            // 先记录待重建，再写描述文件并返回。即使 Worker 在随后重建前
            // 被终止，下一次启动也不会因远程内容指纹相同而跳过这次导入。
            markPendingRebuild();
        }
        writeDescriptor(paths.descriptor(), url);
        return new SyncResult(changed, true, "任务 Wiki 已同步", rebuildRequired);
    }

    /** 构建失败后保留“需要再次导入”的状态，避免下次启动因内容指纹不变而跳过重建。 */
    public void markPendingRebuild() throws IOException {
        atomicWrite(paths().pendingMarker(), "pending\n");
    }

    /** 只有知识库成功安装后才清除重建标记。 */
    public void clearPendingRebuild() {
        try {
            Files.deleteIfExists(paths().pendingMarker());
        } catch (IOException exception) {
            LOG.fine("任务 Wiki 重建标记清理失败，将在下次同步时重试");
        }
    }

    private boolean hasPendingRebuild() {
        return Files.isRegularFile(paths().pendingMarker());
    }

    private String download(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                LOG.warning("任务 Wiki URL 协议不受支持，继续使用本地副本");
                return "";
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LOG.warning("任务 Wiki 返回 HTTP " + response.statusCode() + "，继续使用本地副本");
                    return "";
                }
                long contentLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (contentLength > MAX_WIKI_BYTES) {
                    LOG.warning("任务 Wiki 响应超过 4 MiB 上限，继续使用本地副本");
                    return "";
                }
                byte[] bytes = readLimited(body);
                if (bytes == null) {
                    LOG.warning("任务 Wiki 流式读取超过 4 MiB 上限，继续使用本地副本");
                    return "";
                }
                return decodeUtf8(bytes).replace("\r\n", "\n");
            }
        } catch (CharacterCodingException exception) {
            LOG.warning("任务 Wiki 不是有效 UTF-8，继续使用本地副本");
            return "";
        } catch (Exception exception) {
            LOG.fine("任务 Wiki 网络更新不可用，继续使用本地副本");
            return "";
        }
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private String readBuiltInWiki() {
        try (var stream = WorkerTaskWikiService.class.getResourceAsStream(
                "/assets/modpedia/wiki/ftb-quests.md")) {
            if (stream == null) {
                return "";
            }
            byte[] bytes = readLimited(stream);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private void writeDescriptorIfMissing(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            writeDescriptor(path, DEFAULT_URL);
        }
    }

    private void writeDescriptor(Path path, String url) throws IOException {
        Descriptor descriptor = new Descriptor(
                SOURCE_ID,
                SOURCE_ID,
                "wiki",
                "wiki_markdown",
                "remote",
                "任务模组 Wiki",
                "neutral",
                "1.21.1",
                url,
                "documents",
                60,
                "{}"
        );
        atomicWrite(path, JSON.toJson(descriptor));
    }

    private String read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = readLimited(input);
                return bytes == null ? "" : decodeUtf8(bytes);
            }
        } catch (IOException exception) {
            return "";
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        return readLimited(input, MAX_WIKI_BYTES);
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - total) {
                return null;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "modpedia-task-wiki-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Paths paths() {
        Path sourceRoot = knowledgeRoot.resolve("sources").resolve(SOURCE_ID);
        return new Paths(
                sourceRoot,
                sourceRoot.resolve("documents"),
                sourceRoot.resolve("source.json"),
                sourceRoot.resolve("documents").resolve("ftb-quests.md"),
                sourceRoot.resolve("rebuild.pending")
        );
    }

    public record SyncResult(
            boolean changed,
            boolean downloaded,
            String message,
            boolean rebuildRequired
    ) {
        /** 兼容仅关心下载结果的旧调用方。 */
        public SyncResult(boolean changed, boolean downloaded, String message) {
            this(changed, downloaded, message, changed);
        }
    }

    private record Paths(
            Path sourceRoot,
            Path documentsRoot,
            Path descriptor,
            Path document,
            Path pendingMarker
    ) {
    }

    private record Descriptor(
            String source_id,
            String collection_id,
            String content_kind,
            String source_type,
            String origin_type,
            String title,
            String language,
            String version,
            String origin_uri,
            String documents_root,
            int priority,
            String metadata_json
    ) {
    }
}
