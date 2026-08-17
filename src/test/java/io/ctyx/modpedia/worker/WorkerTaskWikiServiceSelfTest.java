package io.ctyx.modpedia.worker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Worker Wiki 本地副本与禁用网络路径回归。 */
public final class WorkerTaskWikiServiceSelfTest {
    private WorkerTaskWikiServiceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-task-wiki-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            WorkerTaskWikiService service = new WorkerTaskWikiService(root);
            check(service.prepareLocal(), "首次应从 Worker classpath 准备内置 Wiki");
            Path document = root.resolve("sources/ftbquests-wiki/documents/ftb-quests.md");
            Path descriptor = root.resolve("sources/ftbquests-wiki/source.json");
            check(Files.isRegularFile(document) && Files.size(document) > 0, "Wiki 文档应写入 Worker 知识目录");
            check(Files.isRegularFile(descriptor)
                            && Files.readString(descriptor, StandardCharsets.UTF_8).contains("wiki_markdown"),
                    "Wiki 来源描述应由 Worker 生成");
            service.markPendingRebuild();
            check(service.synchronize("disabled").rebuildRequired(),
                    "存在待重建标记时，即使远程禁用也应保留后续重建请求");
            service.clearPendingRebuild();
            check(!service.synchronize("disabled").changed()
                            && !service.synchronize("disabled").rebuildRequired(),
                    "成功清理待重建标记后不应重复报告 Wiki 变化");
            WorkerTaskWikiService.SyncResult disabled = service.synchronize("disabled");
            check(!disabled.downloaded() && !disabled.changed(), "禁用远程更新不得访问网络或改写文件");

            byte[] small = "# 远程任务 Wiki\n\n正文。\n".getBytes(StandardCharsets.UTF_8);
            byte[] oversized = new byte[WorkerTaskWikiService.MAX_WIKI_BYTES + 1];
            Arrays.fill(oversized, (byte) 'x');
            byte[] invalidUtf8 = new byte[]{(byte) 0xc3, 0x28};
            server.createContext("/small", exchange -> respond(exchange, 200, small, false));
            server.createContext("/oversized", exchange -> respond(exchange, 200, oversized, false));
            server.createContext("/chunked", exchange -> respond(exchange, 200, oversized, true));
            server.createContext("/invalid-utf8", exchange -> respond(exchange, 200, invalidUtf8, false));
            server.createContext("/error", exchange -> respond(exchange, 503, "暂不可用".getBytes(StandardCharsets.UTF_8), false));
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            WorkerTaskWikiService.SyncResult updated = service.synchronize(base + "/small");
            check(updated.downloaded() && updated.changed(), "上限内的远程 Markdown 应成功更新");
            check(Files.readString(document, StandardCharsets.UTF_8).equals("# 远程任务 Wiki\n\n正文。\n"),
                    "远程 Wiki 更新内容应完整保存");
            // 模拟 Worker 已经成功完成本次知识库重建；在真实链路中由
            // WorkerServer 在数据库安装成功后清除该标记。
            service.clearPendingRebuild();
            WorkerTaskWikiService.SyncResult unchanged = service.synchronize(base + "/small");
            check(unchanged.downloaded()
                            && !unchanged.changed()
                            && !unchanged.rebuildRequired(),
                    "内容未变化时不得重复触发更新");

            String cached = "# 保留缓存\n";
            for (String endpoint : new String[]{"/oversized", "/chunked", "/invalid-utf8", "/error"}) {
                Files.writeString(document, cached, StandardCharsets.UTF_8);
                WorkerTaskWikiService.SyncResult rejected = service.synchronize(base + endpoint);
                check(!rejected.downloaded() && !rejected.changed()
                                && !rejected.rebuildRequired(),
                        "异常 Wiki 响应不得覆盖本地缓存：" + endpoint);
                check(Files.readString(document, StandardCharsets.UTF_8).equals(cached),
                        "异常 Wiki 响应后应保留本地缓存：" + endpoint);
            }
            System.out.println("ModPedia Worker task Wiki self-test passed");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, boolean chunked)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
        exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
