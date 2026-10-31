package io.ctyx.modpedia.clientcore;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Provider;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;

/** Worker JAR 的完整性和 Ed25519 签名校验。 */
public final class ArtifactVerifier {
    private ArtifactVerifier() { }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file.toFile())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("运行时缺少 SHA-256", e);
        }
    }

    public static void verifySha256(Path file, String expected) throws IOException {
        if (expected == null || !expected.trim().matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Worker 清单必须包含有效的 SHA-256 摘要");
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected.trim())) {
            throw new IOException("Worker SHA-256 校验失败：expected=" + expected + ", actual=" + actual);
        }
    }

    /**
     * 验证清单中的 Base64 Ed25519 公钥和签名。Java 8 编译允许该算法名，旧 JRE
     * 没有 provider 时返回清晰的不可用错误；不会回退为仅 hash 信任。
     */
    public static void verifyEd25519(Path artifact, String signatureBase64, String publicKeyBase64)
            throws IOException {
        try (InputStream input = Files.newInputStream(artifact)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) bytes.write(buffer, 0, count);
            verifyEd25519(bytes.toByteArray(), signatureBase64, publicKeyBase64);
        }
    }

    /** 使用固定的 Ed25519 provider 校验清单 canonical payload。 */
    public static void verifyEd25519(byte[] payload, String signatureBase64, String publicKeyBase64)
            throws IOException {
        if (payload == null) throw new IOException("Worker 签名 payload 不能为空");
        if (signatureBase64 == null || signatureBase64.trim().isEmpty()) {
            throw new IOException("Worker 清单缺少 Ed25519 签名");
        }
        if (publicKeyBase64 == null || publicKeyBase64.trim().isEmpty()) {
            throw new IOException("没有配置可信的 Worker Ed25519 公钥");
        }
        try {
            byte[] encodedKey = Base64.getDecoder().decode(publicKeyBase64);
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            Provider provider = new EdDSASecurityProvider();
            PublicKey key = KeyFactory.getInstance("EdDSA", provider)
                    .generatePublic(new X509EncodedKeySpec(encodedKey));
            Signature verifier = Signature.getInstance("NONEwithEdDSA", provider);
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new IOException("Worker Ed25519 签名校验失败");
        } catch (IllegalArgumentException e) {
            throw new IOException("Worker 签名不是有效 Base64", e);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Worker Ed25519 签名校验失败", e);
        }
    }
}
