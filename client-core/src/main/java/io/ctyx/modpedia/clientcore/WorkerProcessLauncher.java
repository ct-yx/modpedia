package io.ctyx.modpedia.clientcore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 使用用户指定的 Java 17 启动独立 Worker JVM，不把 Worker 类加载进游戏 JVM。 */
public final class WorkerProcessLauncher {
    private WorkerProcessLauncher() { }

    public static Process launch(Path javaExecutable, Path workerJar, String host, int port,
                                 String config, String token, Map<String, String> environment)
            throws IOException {
        if (javaExecutable == null || !Files.isRegularFile(javaExecutable)) {
            throw new IOException("找不到 Java 17 可执行文件：" + javaExecutable);
        }
        if (workerJar == null || !Files.isRegularFile(workerJar)) {
            throw new IOException("找不到 Worker JAR：" + workerJar);
        }
        if (token == null || token.trim().isEmpty()) throw new IOException("Worker token 不能为空");
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(workerJar.toString());
        command.add("io.ctyx.modpedia.worker.WorkerMain");
        command.add("--host"); command.add(host == null ? "127.0.0.1" : host);
        command.add("--port"); command.add(Integer.toString(port));
        command.add("--config"); command.add(config == null ? "." : config);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("MODPEDIA_WORKER_TOKEN", token);
        if (environment != null) builder.environment().putAll(environment);
        // Worker 只接收本地 IPC token；不要把客户端继承的 API Key 传入独立 JVM。
        builder.environment().remove("MODPEDIA_API_KEY");
        builder.redirectErrorStream(true);
        return builder.start();
    }
}
