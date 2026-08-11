package io.ctyx.modpedia.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 初始化可选任务 Wiki 来源，并在后台尝试更新远程 Markdown。 */
public final class TaskWikiSyncService {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-task-wiki");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final String SOURCE_ID = "ftbquests-wiki";
    private static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/FTBTeam/FTB-Quests/1.21.1/main/README.md";

    private TaskWikiSyncService() {
    }

    public static void startAsync() {
        EXECUTOR.execute(TaskWikiSyncService::synchronize);
    }

    private static void synchronize() {
        Path knowledgeRoot = FMLPaths.CONFIGDIR.get()
                .resolve("modpedia")
                .resolve("knowledge");
        Path sourceRoot = knowledgeRoot.resolve("sources").resolve(SOURCE_ID);
        Path documentsRoot = sourceRoot.resolve("documents");
        Path document = documentsRoot.resolve("ftb-quests.md");
        try {
            Files.createDirectories(documentsRoot);
            writeDescriptor(sourceRoot.resolve("source.json"));
            boolean changed = false;
            if (!Files.isRegularFile(document)) {
                String builtIn = readBuiltInWiki();
                if (!builtIn.isBlank()) {
                    atomicWrite(document, builtIn);
                    changed = true;
                }
            }

            String url = System.getProperty("modpedia.taskWikiUrl", DEFAULT_URL).strip();
            if (!url.isBlank() && !url.equals("disabled")) {
                String remote = download(url);
                if (!remote.isBlank() && !remote.equals(read(document))) {
                    atomicWrite(document, remote);
                    changed = true;
                }
            }
            if (changed) {
                KnowledgeUpdateService.rebuildAsync();
            }
        } catch (Throwable failure) {
            // Wiki 是可选增强；网络、权限或格式异常不能阻止客户端进入游戏。
            ModPedia.LOGGER.warn(
                    "任务 Wiki 更新失败，继续使用本地缓存：{}",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
            );
        }
    }

    private static void writeDescriptor(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return;
        }
        Descriptor descriptor = new Descriptor(
                SOURCE_ID,
                SOURCE_ID,
                "wiki",
                "wiki_markdown",
                "remote",
                "任务模组 Wiki",
                "neutral",
                "1.21.1",
                System.getProperty("modpedia.taskWikiUrl", DEFAULT_URL),
                "documents",
                60,
                ""
        );
        atomicWrite(path, JSON.toJson(descriptor));
    }

    private static String readBuiltInWiki() {
        try (var stream = TaskWikiSyncService.class.getResourceAsStream(
                "/assets/modpedia/wiki/ftb-quests.md"
        )) {
            return stream == null
                    ? ""
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private static String download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                ModPedia.LOGGER.warn("任务 Wiki 返回 HTTP {}，继续使用本地缓存", response.statusCode());
                return "";
            }
            return response.body() == null ? "" : response.body().replace("\r\n", "\n");
        } catch (Exception exception) {
            ModPedia.LOGGER.warn("任务 Wiki 网络更新不可用，继续使用本地缓存");
            return "";
        }
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    private static void atomicWrite(Path path, String content) throws IOException {
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
