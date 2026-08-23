package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.storage.ModPediaPaths;

import java.net.Socket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** ModPedia Worker JVM 入口；只通过 localhost JSONL 与游戏进程通信。 */
public final class WorkerMain {
    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        int port = Integer.parseInt(options.getOrDefault("port", "0"));
        if (port <= 0) {
            throw new IllegalArgumentException("Worker 缺少 --port");
        }
        String host = options.getOrDefault("host", "127.0.0.1");
        // Token 只允许通过父进程环境传递，禁止命令行回退，避免出现在 ps/Activity
        // Monitor 和崩溃报告的参数列表中。
        String token = System.getenv("MODPEDIA_WORKER_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Worker 缺少 MODPEDIA_WORKER_TOKEN");
        }
        Path configDirectory = Path.of(options.getOrDefault("config", "."));
        ModPediaPaths paths = ModPediaPaths.forConfig(configDirectory);
        paths.migrateLegacyQuietly();
        Path knowledgeRoot = Path.of(options.getOrDefault(
                "knowledge", paths.runtimeKnowledgeRoot().toString()
        ));
        Path contentRoot = Path.of(options.getOrDefault(
                "content", paths.contentRoot().toString()
        ));
        Path conversationsRoot = Path.of(options.getOrDefault(
                "conversations", paths.conversationsRoot().toString()
        ));
        Path settingsPath = Path.of(options.getOrDefault(
                "settings", paths.aiSettings().toString()
        ));

        // 只输出类来源、版本和词表存在性，不读取或记录 API 配置内容。
        WorkerRuntimeDiagnostics.logStartup();

        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            WorkerServer server = new WorkerServer(
                    socket,
                    token,
                    configDirectory,
                    knowledgeRoot,
                    contentRoot,
                    conversationsRoot,
                    settingsPath
            );
            server.run();
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--") || index + 1 >= args.length) {
                continue;
            }
            result.put(argument.substring(2), args[++index]);
        }
        return result;
    }
}
