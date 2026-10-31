package io.ctyx.modpedia.clientcore;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 下载、校验和原子激活 Worker；所有变更先落到 staging。 */
public final class WorkerInstaller {
    public interface ManifestSource {
        WorkerManifest fetch() throws IOException;
    }

    public interface ArtifactSource {
        InputStream open(String url) throws IOException;
    }

    public static final class InstallResult {
        private final Path workerJar;
        private final WorkerManifest manifest;
        private final String sourceUrl;

        InstallResult(Path workerJar, WorkerManifest manifest, String sourceUrl) {
            this.workerJar = workerJar;
            this.manifest = manifest;
            this.sourceUrl = sourceUrl;
        }
        public Path workerJar() { return workerJar; }
        public WorkerManifest manifest() { return manifest; }
        public String sourceUrl() { return sourceUrl; }
    }

    public static final class DefaultHttpSource implements ManifestSource, ArtifactSource {
        private final int timeoutMillis;
        public DefaultHttpSource(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }

        @Override public WorkerManifest fetch() throws IOException {
            throw new UnsupportedOperationException("请用 ManifestHttpSource(url) 获取清单");
        }
        @Override public InputStream open(String url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json, application/octet-stream");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("下载失败 HTTP " + status + "：" + url);
            return connection.getInputStream();
        }
    }

    private final WorkerPaths paths;
    private final byte[] publicKey;

    public WorkerInstaller(WorkerPaths paths, byte[] publicKey) {
        this.paths = paths;
        this.publicKey = publicKey == null ? null : publicKey.clone();
    }

    public InstallResult install(WorkerManifest manifest, ArtifactSource source) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("Worker 清单不能为空");
        if (source == null) throw new IllegalArgumentException("Worker 下载源不能为空");
        Files.createDirectories(paths.root());
        Files.createDirectories(paths.staging());
        try (FileChannel channel = FileChannel.open(paths.installLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path stagingRoot = paths.staging().resolve(manifest.workerVersion() + ".part");
            deleteTree(stagingRoot);
            Files.createDirectories(stagingRoot);
            IOException last = null;
            String selected = null;
            Path artifact = stagingRoot.resolve("modpedia-worker.jar");
            for (String url : manifest.artifactUrls()) {
                try (InputStream input = source.open(url)) {
                    Files.copy(input, artifact, StandardCopyOption.REPLACE_EXISTING);
                    ArtifactVerifier.verifySha256(artifact, manifest.sha256());
                    if (publicKey != null && manifest.signature() != null && !manifest.signature().trim().isEmpty()) {
                        ArtifactVerifier.verifyEd25519(artifact, manifest.signature(), Base64Support.encode(publicKey));
                    }
                    selected = url;
                    break;
                } catch (IOException failure) {
                    last = failure;
                    Files.deleteIfExists(artifact);
                }
            }
            if (selected == null) throw new IOException("Worker 主源和备用源均失败", last);
            Files.write(stagingRoot.resolve("manifest.json"), manifest.toJson().getBytes("UTF-8"));
            Path active = paths.current();
            Path backup = paths.root().resolve("current.previous");
            if (Files.exists(active)) {
                deleteTree(backup);
                Files.move(active, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(stagingRoot, active, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                if (Files.exists(backup)) Files.move(backup, active, StandardCopyOption.ATOMIC_MOVE);
                throw failure;
            }
            Files.write(paths.installState(), ("{\"worker_version\":\"" + JsonEscaper.escape(manifest.workerVersion()) + "\",\"source_url\":\"" + JsonEscaper.escape(selected) + "\"}\n").getBytes("UTF-8"));
            deleteTree(backup);
            return new InstallResult(paths.workerJar(), manifest, selected);
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        if (!Files.isDirectory(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) deleteTree(child);
        }
        Files.deleteIfExists(root);
    }

    private static final class Base64Support {
        static String encode(byte[] bytes) { return java.util.Base64.getEncoder().encodeToString(bytes); }
    }
}
