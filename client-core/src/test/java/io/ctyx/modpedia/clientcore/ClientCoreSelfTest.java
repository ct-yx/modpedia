package io.ctyx.modpedia.clientcore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;

/** 纯 Java client-core 回归，不加载 Minecraft 或 Worker 实现。 */
public final class ClientCoreSelfTest {
    private ClientCoreSelfTest() { }

    public static void main(String[] args) throws Exception {
        manifestRoundTrip();
        javaVersionParsing();
        protocolProofAndResume();
        tooltipApprovalBoundary();
        installAtomicAndFallback();
        missingTrustFailsClosed();
        ed25519Verification();
        System.out.println("ModPedia client-core self-test passed");
    }

    private static void manifestRoundTrip() {
        WorkerManifest manifest = new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                "https://primary/worker.jar", Arrays.asList("https://backup/worker.jar"),
                repeat("00", 32), signatureBase64());
        WorkerManifest parsed = WorkerManifest.parse(manifest.toJson());
        check(parsed.javaMajor() == 17 && parsed.protocolVersion() == 2, "清单数字字段应保留");
        check(parsed.artifactUrls().size() == 2, "清单主源和备用源应保留");
    }

    private static void javaVersionParsing() {
        check(JavaRuntime.parseMajor("17") == 17, "Java 17 版本解析失败");
        check(JavaRuntime.parseMajor("17.0.11") == 17, "Java 17.0 版本解析失败");
        check(JavaRuntime.parseMajor("1.8.0_402") == 8, "Java 8 版本解析失败");
        check(JavaRuntime.parseMajor("bad") == -1, "未知 Java 版本应返回 -1");
    }

    private static void protocolProofAndResume() {
        String proof = WorkerProtocolClient.challengeProof("challenge", "token");
        check("XYos66e3JvqA7LaCse4cplGLXAeEiTI8_faPh-Ozdew".equals(proof),
                "challenge proof 应与 Worker Core 的 HmacSHA256(token, challenge) 一致");
        check("uZcByJQsTFGoymV8u1HDzYdHMsvhDxdoO3hFphZylUM".equals(
                        WorkerProtocolClient.challengeProof("challenge-1", "secret-token")),
                "challenge proof 固定向量应与 Worker Core 一致");
        check(WorkerProtocolClient.accepts(2, 1) && WorkerProtocolClient.accepts(2, 2), "v2 应兼容 v1/v2 客户端");
        check(new WorkerProtocolClient.ResumeRequest("conversation", 3).revision() == 3, "resume revision 应保留");
        check(new WorkerProtocolClient.ConversationEvent("conversation", 4, "message.delta", "{}").revision() == 4, "增量事件 revision 应保留");
    }

    private static void tooltipApprovalBoundary() {
        RuntimeTooltipCoordinator coordinator = new RuntimeTooltipCoordinator();
        RuntimeTooltipCoordinator.Result unavailable = coordinator.read("example:item", id -> "secret tooltip");
        check(!unavailable.available() && "item_not_confirmed".equals(unavailable.reason()), "未确认物品不得读取 Tooltip");
        coordinator.approve("example:item");
        RuntimeTooltipCoordinator.Result available = coordinator.read("example:item", id -> "Tooltip");
        check(available.available() && "Tooltip".equals(available.tooltip()), "确认物品应读取 Tooltip");
        RuntimeTooltipCoordinator.Result cached = coordinator.read("example:item", id -> "changed");
        check("Tooltip".equals(cached.tooltip()), "Tooltip 应在当前请求上下文缓存");
        coordinator.revoke("example:item");
        check(!coordinator.isApproved("example:item"), "撤销确认应清理授权");
    }

    private static void installAtomicAndFallback() throws Exception {
        Path root = Files.createTempDirectory("client-core-install-");
        try {
            WorkerPaths paths = new WorkerPaths(root.resolve("worker"));
            // RFC 8032 第 1 组向量：空内容的 Ed25519 签名，固定覆盖 Java 8 provider。
            byte[] payload = new byte[0];
            final String hash = sha256(payload);
            WorkerManifest manifest = new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                    "bad", Collections.singletonList("good"), hash, signatureBase64());
            WorkerInstaller installer = new WorkerInstaller(paths, trustedPublicKey());
            WorkerInstaller.InstallResult result = installer.install(manifest, url -> {
                if ("bad".equals(url)) throw new IOException("primary down");
                return new ByteArrayInputStream(payload);
            });
            check("good".equals(result.sourceUrl()), "失败主源后应使用备用源");
            check(Files.exists(paths.workerJar()), "激活后 Worker JAR 应存在");
            check(Files.size(paths.workerJar()) == 0, "激活内容应完整");
            check(Files.exists(paths.manifest()), "激活后应保存 manifest.json");
            check("2.0.0".equals(installer.verifyInstalled().workerVersion()), "已安装 Worker 应校验签名后可用");
            Files.write(paths.workerJar(), new byte[] {1});
            expectIOException(installer::verifyInstalled, "已安装 Worker 被篡改时必须拒绝启动");
        } finally {
            WorkerInstaller.deleteTree(root);
        }
    }

    private static void missingTrustFailsClosed() throws Exception {
        Path root = Files.createTempDirectory("client-core-untrusted-");
        try {
            WorkerPaths paths = new WorkerPaths(root.resolve("worker"));
            WorkerManifest manifest = new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                    "https://primary/worker.jar", Collections.<String>emptyList(),
                    repeat("00", 32), signatureBase64());
            expectIOException(() -> new WorkerInstaller(paths, null).install(manifest, url -> {
                throw new AssertionError("缺少可信公钥时不得下载");
            }), "缺少可信公钥时必须 fail closed");
            check(!Files.exists(paths.current()), "缺少可信公钥时不得激活 Worker");
            expectIllegalArgument(() -> new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                    "https://primary/worker.jar", Collections.<String>emptyList(), "", signatureBase64()),
                    "缺少 SHA-256 时必须拒绝清单");
            expectIllegalArgument(() -> new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                    "https://primary/worker.jar", Collections.<String>emptyList(), repeat("00", 32), ""),
                    "缺少签名时必须拒绝清单");
        } finally {
            WorkerInstaller.deleteTree(root);
        }
    }

    private static void ed25519Verification() throws Exception {
        Path file = Files.createTempFile("client-core-signature-", ".jar");
        try {
            WorkerManifest manifest = signedTestManifest();
            ArtifactVerifier.verifyEd25519(manifest.signaturePayload(), manifest.signature(),
                    Base64.getEncoder().encodeToString(trustedPublicKey()));
            byte[] tampered = manifest.signaturePayload();
            tampered[tampered.length - 1] ^= 1;
            expectIOException(() -> ArtifactVerifier.verifyEd25519(
                    tampered, manifest.signature(), Base64.getEncoder().encodeToString(trustedPublicKey())),
                    "篡改 canonical payload 必须拒绝签名");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] trustedPublicKey() {
        byte[] raw = Base64.getDecoder().decode("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=");
        byte[] x509Prefix = hexBytes("302a300506032b6570032100");
        byte[] encoded = new byte[x509Prefix.length + raw.length];
        System.arraycopy(x509Prefix, 0, encoded, 0, x509Prefix.length);
        System.arraycopy(raw, 0, encoded, x509Prefix.length, raw.length);
        return encoded;
    }

    private static String signatureBase64() {
        return "2EQ59r/NNLWtL/3dwLX1HWweRBVl7Q9B5kNYzNPsmFSaZUdpSQPZtC3v7szDMYh7RVVeCl5NvPuOwnWwuYvyAQ==";
    }

    private static WorkerManifest signedTestManifest() {
        return new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                "bad", Collections.singletonList("good"),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", signatureBase64());
    }

    private static byte[] hexBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void expectIOException(CheckedAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // 预期失败。
        }
    }

    private static void expectIllegalArgument(CheckedAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // 预期失败。
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static String sha256(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] result = digest.digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte value : result) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
