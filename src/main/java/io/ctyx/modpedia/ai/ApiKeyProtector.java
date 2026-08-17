package io.ctyx.modpedia.ai;

import com.google.gson.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用系统标识派生的 AES-GCM 密钥保护本地 API Key。 */
public final class ApiKeyProtector {
    public static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String identity;
    private final String fingerprint;

    public ApiKeyProtector(String identity) {
        this.identity = MachineIdentity.normalize(identity);
        if (this.identity.isBlank()) {
            throw new IllegalArgumentException("本地密钥保护需要系统标识");
        }
        this.fingerprint = MachineIdentity.fingerprint(this.identity);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public JsonObject encrypt(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(apiKey.strip().getBytes(StandardCharsets.UTF_8));

            JsonObject result = new JsonObject();
            result.addProperty("version", VERSION);
            result.addProperty("machine_fingerprint", fingerprint);
            result.addProperty("nonce", Base64.getEncoder().encodeToString(nonce));
            result.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("API Key 加密失败", exception);
        }
    }

    public DecryptionResult decrypt(JsonObject value) {
        if (value == null) {
            return DecryptionResult.invalid();
        }
        if (!fingerprint.equals(string(value, "machine_fingerprint"))) {
            return DecryptionResult.mismatch();
        }
        try {
            if (value.get("version") == null || value.get("version").getAsInt() != VERSION) {
                return DecryptionResult.invalid();
            }
            byte[] nonce = Base64.getDecoder().decode(string(value, "nonce"));
            byte[] ciphertext = Base64.getDecoder().decode(string(value, "ciphertext"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            return DecryptionResult.success(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return DecryptionResult.invalid();
        }
    }

    private SecretKeySpec key() {
        return new SecretKeySpec(sha256("ModPedia/api-key-key/v1:" + identity), "AES");
    }

    private static String string(JsonObject value, String name) {
        try {
            return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    public enum Status {
        SUCCESS,
        MISMATCH,
        INVALID
    }

    public record DecryptionResult(Status status, String plaintext) {
        static DecryptionResult success(String plaintext) {
            return new DecryptionResult(Status.SUCCESS, plaintext);
        }

        static DecryptionResult mismatch() {
            return new DecryptionResult(Status.MISMATCH, "");
        }

        static DecryptionResult invalid() {
            return new DecryptionResult(Status.INVALID, "");
        }

        public boolean success() {
            return status == Status.SUCCESS && plaintext != null && !plaintext.isBlank();
        }
    }
}
