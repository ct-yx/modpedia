package io.ctyx.modpedia.clientcore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 纯 Java client-core 回归，不加载 Minecraft 或 Worker 实现。 */
public final class ClientCoreSelfTest {
    private ClientCoreSelfTest() { }

    public static void main(String[] args) throws Exception {
        manifestRoundTrip();
        javaVersionParsing();
        protocolProofAndResume();
        tooltipApprovalBoundary();
        installAtomicAndFallback();
        ed25519Verification();
        System.out.println("ModPedia client-core self-test passed");
    }

    private static void manifestRoundTrip() {
        WorkerManifest manifest = new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                "https://primary/worker.jar", Arrays.asList("https://backup/worker.jar"),
                "aabb", "signature");
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
            byte[] payload = "worker-v2".getBytes(StandardCharsets.UTF_8);
            final String hash = sha256(payload);
            WorkerManifest manifest = new WorkerManifest(1, "stable", "2.0.0", 17, 2,
                    "bad", Collections.singletonList("good"), hash, "");
            WorkerInstaller installer = new WorkerInstaller(paths, null);
            WorkerInstaller.InstallResult result = installer.install(manifest, url -> {
                if ("bad".equals(url)) throw new IOException("primary down");
                return new ByteArrayInputStream(payload);
            });
            check("good".equals(result.sourceUrl()), "失败主源后应使用备用源");
            check(Files.exists(paths.workerJar()), "激活后 Worker JAR 应存在");
            check(new String(Files.readAllBytes(paths.workerJar()), StandardCharsets.UTF_8).equals("worker-v2"), "激活内容应完整");
            check(Files.exists(paths.manifest()), "激活后应保存 manifest.json");
        } finally {
            WorkerInstaller.deleteTree(root);
        }
    }

    private static void ed25519Verification() throws Exception {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            byte[] payload = "signed-worker".getBytes(StandardCharsets.UTF_8);
            Path file = Files.createTempFile("client-core-signature-", ".jar");
            try {
                Files.write(file, payload);
                java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
                signer.initSign(pair.getPrivate());
                signer.update(payload);
                String signature = java.util.Base64.getEncoder().encodeToString(signer.sign());
                String publicKey = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
                ArtifactVerifier.verifyEd25519(file, signature, publicKey);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            System.out.println("Ed25519 provider unavailable; signature test skipped");
        }
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
