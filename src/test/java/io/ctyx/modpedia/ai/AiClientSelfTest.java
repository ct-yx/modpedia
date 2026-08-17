package io.ctyx.modpedia.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** AI 地址归一化、模型列表解析、认证错误和 HTML 错误提示回归测试。 */
public final class AiClientSelfTest {
    private AiClientSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        check("https://example.invalid/v1".equals(AiClient.normalizedEndpoint("https://example.invalid")),
                "域名根地址应自动补全 /v1");
        check("https://example.invalid/v1".equals(AiClient.normalizedEndpoint("https://example.invalid/v1/")),
                "版本地址只应去除末尾斜杠");
        check("https://example.invalid/api".equals(AiClient.normalizedEndpoint("https://example.invalid/api")),
                "自定义 API 路径不应被改写");
        check("https://example.invalid/v1".equals(
                        AiClient.normalizedEndpoint("https://example.invalid/v1/chat/completions")),
                "误填完整 Chat Completions 地址时应回退到 API 根地址");
        check("https://example.invalid/v1".equals(
                        AiClient.normalizedEndpoint("https://example.invalid/v1/models")),
                "误填模型列表地址时应回退到 API 根地址");

        AiClient.ModelListResult parsed = AiClient.parseModelListResponse(
                200,
                "{\"data\":["
                        + "{\"id\":\"zeta\",\"owned_by\":\"team-z\"},"
                        + "{\"id\":\"alpha\"},"
                        + "{\"id\":\"zeta\",\"owned_by\":\"duplicate\"}]}"
        );
        check(!parsed.failed(), "正常模型列表不应失败");
        check(parsed.models().size() == 2, "模型 ID 应去重");
        check("alpha".equals(parsed.models().get(0).id()), "模型应按 ID 稳定排序");
        check("team-z".equals(parsed.models().get(1).ownedBy()), "owned_by 应保留");

        AiClient.ModelListResult empty = AiClient.parseModelListResponse(200, "{\"data\":[]}");
        check(!empty.failed() && empty.models().isEmpty(), "空模型列表应是成功的空结果");

        AiClient.ModelListResult unauthorized = AiClient.parseModelListResponse(
                401,
                "{\"code\":\"INVALID_API_KEY\",\"message\":\"secret-value invalid\"}"
        );
        check(unauthorized.failed(), "401 应标记为失败");
        check(unauthorized.message().contains("API Key"), "401 应提示 API Key 问题");
        check(!unauthorized.message().contains("secret-value"), "错误提示不得包含 API Key");

        AiClient.ModelListResult html = AiClient.parseModelListResponse(
                200,
                "<!doctype html><html><body>login</body></html>"
        );
        check(html.failed(), "HTML 响应应标记为失败");
        check(html.message().contains("/v1"), "HTML 响应应提示 API 地址通常需要 /v1");

        String friendlyHtml = AiClient.friendlyError(
                new IllegalStateException("JsonParseException: Unexpected character '<' (code 60)")
        );
        check(friendlyHtml.contains("网页内容"), "连接测试应将 HTML 解析错误转换为可读提示");
        check(!friendlyHtml.contains("secret-value"), "连接错误提示不得包含 API Key");
        check(!AiClient.friendlyError(
                new IllegalStateException("upstream echoed secret-value"),
                "secret-value"
        ).contains("secret-value"), "上游直接回显 API Key 时也必须脱敏");

        String friendlySpi = AiClient.friendlyError(new java.util.ServiceConfigurationError(
                "dev.langchain4j.http.client.HttpClientBuilderFactory: "
                        + "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory not a subtype"
        ));
        check(friendlySpi.contains("HTTP 客户端依赖"), "SPI 类加载错误应转换为可读提示");

        check("grok-4.5-latest".equals(AiClient.fallbackModelName("grok-4.5")),
                "grok-4.5 应回退到工具调用兼容的 latest 路由");
        check("grok-4.5-latest".equals(AiClient.effectiveModelName("grok-4.5")),
                "AI 请求应在第一次调用前使用工具调用兼容的 latest 路由");
        check(AiClient.fallbackModelName("grok-4.5-latest").isBlank(),
                "latest 路由不应重复触发模型回退");
        Throwable upstreamUnavailable = new RuntimeException(
                "HTTP 503: {\"error\":{\"message\":\"No healthy Grok OAuth account is currently available\"}}"
        );
        check(AiClient.isGrokOAuthUnavailable(upstreamUnavailable),
                "应识别 Grok OAuth 上游不可用错误");
        check(AiClient.friendlyError(upstreamUnavailable).contains("grok-4.5-latest"),
                "Grok OAuth 上游错误应提示兼容模型");
        check(AiClient.isRetryableFailure(new IllegalStateException("HTTP 503 upstream unavailable")),
                "503 应允许自动重试");
        check(!AiClient.shouldRetryImmediately(new IllegalStateException(
                        "HTTP 503: {\"error\":{\"message\":\"Service temporarily unavailable\"}}")),
                "503 不应在几百毫秒后立即重复请求");
        check(AiClient.shouldRetryImmediately(new IllegalStateException("HTTP 502 upstream unavailable")),
                "没有 Retry-After 的 502 可以短退避重试");
        check(AiClient.httpStatusCode(new dev.langchain4j.exception.HttpException(
                        503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}")) == 503,
                "LangChain HTTP 异常应保留状态码");
        String friendly503 = AiClient.friendlyError(
                new dev.langchain4j.exception.HttpException(
                        503,
                        "{\"error\":{\"message\":\"Service temporarily unavailable\"}}"),
                "secret-value"
        );
        check(friendly503.contains("HTTP 503") && friendly503.contains("Service temporarily unavailable"),
                "聊天 503 应显示友好状态和上游消息");
        check(!friendly503.contains("{\"error\""),
                "聊天错误不应把上游原始 JSON 直接显示给玩家");
        check(!friendly503.contains("secret-value"), "聊天错误不得泄露 API Key");
        String friendlyRetryAfter = AiClient.friendlyError(
                new IllegalStateException(
                        "HTTP 503: {\"error\":{\"message\":\"Service temporarily unavailable\"}}, Retry-After: 30"),
                "secret-value"
        );
        check(friendlyRetryAfter.contains("30 秒"), "聊天错误应传达上游要求的等待时间");
        check(AiClient.isRetryableFailure(new IllegalStateException("temporary unavailable")),
                "网关返回 temporary unavailable 应允许自动重试");
        check(AiClient.isRetryableFailure(new IllegalStateException("No tool output found for function call fc-1")),
                "孤立工具调用应允许自动清理后重试");
        check(!AiClient.isRetryableFailure(new IllegalStateException("HTTP 400 invalid model")),
                "模型参数错误不应重复请求");

        AiSettings modelSettings = new AiSettings(
                AssistantMode.AI,
                "http://127.0.0.1:1/v1",
                "self-test-model",
                "secret-value",
                true,
                SearchIntensity.FAST,
                1,
                4,
                8_000,
                10
        );
        try {
            check(AiClient.blockingModel(modelSettings) != null,
                    "阻塞模型应能使用显式 JDK HTTP 客户端构造");
            check(AiClient.streamingModel(modelSettings) != null,
                    "流式模型应能使用显式 JDK HTTP 客户端构造");
        } catch (java.util.ServiceConfigurationError error) {
            throw new AssertionError("模型构造不应再依赖异常的 HTTP 客户端 SPI", error);
        }

        testFetchModelsUsesNormalizedEndpoint();
        testFetchModelsRetriesTemporaryFailure();
        testConnectionReportsSuccessfulResponseAsSuccess();
        testConnectionReportsNonRetryableFailure();
        testConnectionRespectsRetryAfter();
        testConnectionUsesMinimalRequestAndReportsStatus();
        System.out.println("ModPedia AI client self-test passed");
    }

    private static void testConnectionReportsSuccessfulResponseAsSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-client-connection-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = attempts.incrementAndGet();
            byte[] body = (attempt == 1
                    ? "{\"error\":{\"message\":\"temporary unavailable\"}}"
                    : "{\"id\":\"test\",\"choices\":[{\"message\":{"
                    + "\"role\":\"assistant\",\"content\":\"OK\"},"
                    + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            AtomicReference<AiClient.TestResult> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            AiClient.testConnection(settings, value -> {
                result.set(value);
                completed.countDown();
            });
            check(completed.await(5, TimeUnit.SECONDS), "连接测试应在测试超时内完成");
            check(result.get() != null && !result.get().failed(),
                    "非空的成功响应必须报告为连接成功");
            check(attempts.get() >= 2, "连接测试遇到临时 503 后应自动重试一次");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void testConnectionReportsNonRetryableFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-client-failure-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"invalid model\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            java.util.concurrent.atomic.AtomicReference<AiClient.TestResult> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            AiClient.testConnection(settings, value -> {
                result.set(value);
                completed.countDown();
            });
            check(completed.await(5, TimeUnit.SECONDS), "非重试错误的连接测试应完成");
            check(result.get() != null && result.get().failed(),
                    "400 等配置错误必须报告 failed=true，不能显示为连接成功");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void testConnectionUsesMinimalRequestAndReportsStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-client-minimal-fixture");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-model",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            AiClient.TestResult result = AiClient.testConnectionBlocking(settings);
            check(!result.failed(), "最小 Chat Completions 请求应成功");
            check(result.statusCode() == 200, "连接测试应返回 HTTP 状态码");
            check(result.attempts() == 1, "成功请求不应重复发送");
            check(requestBody.get().contains("\"model\":\"test-model\""),
                    "连接测试应发送所选模型");
            check(!requestBody.get().contains("tools"),
                    "连接测试不应强制使用工具调用");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void testConnectionRespectsRetryAfter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-client-retry-after-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"Service temporarily unavailable\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "30");
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            AiClient.TestResult result = AiClient.testConnectionBlocking(settings);
            check(result.failed(), "上游明确要求退避时仍应报告连接失败");
            check(result.statusCode() == 503, "Retry-After 测试应保留 HTTP 状态码");
            check(result.attempts() == 1 && attempts.get() == 1,
                    "上游要求等待时不应在 350 ms 后立即重复请求");
            check(result.message().contains("30 秒"),
                    "Retry-After 应显示给用户，避免把上游退避误判为 API Key 错误");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void testFetchModelsRetriesTemporaryFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modpedia-ai-client-model-retry-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/models", exchange -> {
            int attempt = attempts.incrementAndGet();
            byte[] body = (attempt == 1
                    ? "{\"error\":{\"message\":\"temporary unavailable\"}}"
                    : "{\"data\":[{\"id\":\"retried-model\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            AtomicReference<AiClient.ModelListResult> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            AiClient.fetchModels(settings, value -> {
                result.set(value);
                completed.countDown();
            });
            check(completed.await(5, TimeUnit.SECONDS), "模型列表临时失败后的重试应完成");
            check(result.get() != null && !result.get().failed()
                            && result.get().models().stream()
                            .anyMatch(model -> "retried-model".equals(model.id())),
                    "模型列表遇到 503 后应自动重试并返回模型");
            check(attempts.get() >= 2, "模型列表临时失败后必须发起第二次请求");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void testFetchModelsUsesNormalizedEndpoint() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<String> requestLine = new AtomicReference<>("");
            AtomicReference<String> authorization = new AtomicReference<>("");
            CountDownLatch requestReceived = new CountDownLatch(1);
            Thread responder = new Thread(() -> serveOnce(
                    server,
                    requestLine,
                    authorization,
                    requestReceived
            ), "modpedia-ai-client-self-test-server");
            responder.start();

            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    "http://127.0.0.1:" + server.getLocalPort(),
                    "",
                    "secret-value",
                    false,
                    SearchIntensity.FAST,
                    1,
                    4,
                    8_000,
                    10
            );
            AtomicReference<AiClient.ModelListResult> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            AiClient.fetchModels(settings, value -> {
                result.set(value);
                completed.countDown();
            });

            check(completed.await(5, TimeUnit.SECONDS), "模型列表请求应在测试超时内完成");
            check(requestReceived.await(5, TimeUnit.SECONDS), "测试服务端应收到模型列表请求");
            responder.join(Duration.ofSeconds(5).toMillis());
            check(result.get() != null && !result.get().failed(), "本地模型列表请求应成功");
            check(requestLine.get().startsWith("GET /v1/models "), "根地址请求应自动访问 /v1/models");
            check("Bearer secret-value".equals(authorization.get()), "模型列表请求应携带 Bearer API Key");
        }
    }

    private static void serveOnce(
            ServerSocket server,
            AtomicReference<String> requestLine,
            AtomicReference<String> authorization,
            CountDownLatch requestReceived
    ) {
        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            );
            requestLine.set(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Authorization:", 0, "Authorization:".length())) {
                    authorization.set(line.substring("Authorization:".length()).strip());
                }
            }
            byte[] body = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"self-test\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            requestReceived.countDown();
        } catch (Exception exception) {
            requestReceived.countDown();
            throw new RuntimeException(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
