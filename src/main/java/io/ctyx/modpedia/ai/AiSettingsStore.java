package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Set;

/** AI 设置的本地 JSON 存储；优先使用系统密钥环，失败时回退到机器绑定密文。 */
public final class AiSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final ApiKeyProtector protector;
    private final SystemSecretStore secretStore;
    private AiSettings cached;
    private boolean loaded;

    public AiSettingsStore(Path path) {
        this(path, MachineIdentity.forSettings(path), SystemSecretStore.current());
    }

    AiSettingsStore(Path path, String machineIdentity) {
        this(path, machineIdentity, SystemSecretStore.disabled());
    }

    AiSettingsStore(Path path, String machineIdentity, SystemSecretStore secretStore) {
        this.path = path.toAbsolutePath().normalize();
        this.protector = new ApiKeyProtector(machineIdentity);
        this.secretStore = secretStore == null ? SystemSecretStore.disabled() : secretStore;
    }

    // Worker 只接收显式路径；客户端运行时路径由 AiAssistantSession 适配层提供。
    /** 首次读取时解密一次；后续请求直接复用当前进程缓存。 */
    public synchronized AiSettings load() {
        if (loaded) {
            return cached;
        }
        if (!Files.isRegularFile(path)) {
            cached = AiSettings.defaults();
            loaded = true;
            write(cached);
            return cached;
        }
        try {
            JsonObject stored = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            Decoded decoded = decode(stored);
            cached = decoded.settings();
            loaded = true;
            // 旧版明文、机器标识不匹配或密文损坏时，立即重写为无密钥配置。
            if (decoded.migrate() || decoded.removeStoredKey()) {
                write(cached);
            }
            return cached;
        } catch (IOException | RuntimeException exception) {
            cached = AiSettings.defaults();
            loaded = true;
            return cached;
        }
    }

    /** 保存并回读校验；失败时返回 false，调用方可以在设置页明确提示用户。 */
    public synchronized boolean save(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        boolean success = write(actual);
        if (success) {
            cached = actual;
            loaded = true;
        }
        return success;
    }

    public Path path() {
        return path;
    }

    String machineFingerprint() {
        return protector.fingerprint();
    }

    private boolean write(AiSettings actual) {
        Path temporary = null;
        try {
            Files.createDirectories(parent());
            restrictDirectory(parent());
            temporary = Files.createTempFile(parent(), "ai-", ".tmp");
            restrictFile(temporary);
            JsonObject stored = GSON.toJsonTree(actual).getAsJsonObject();
            // Gson 的 record 字段名是 apiKey；同时清理可能来自旧版本的 snake_case 字段。
            stored.remove("apiKey");
            stored.remove("api_key");
            stored.remove("api_key_storage");
            stored.remove("api_key_ref");
            stored.remove("apiFormat");
            stored.addProperty("api_format", actual.apiFormat().name());
            if (!actual.apiKey().isBlank()) {
                if (secretStore.put(actual.apiKey())) {
                    stored.addProperty("api_key_storage", "system");
                    stored.addProperty("api_key_ref", secretStore.reference());
                    stored.remove("api_key_encrypted");
                } else {
                    stored.add("api_key_encrypted", protector.encrypt(actual.apiKey()));
                }
            } else {
                secretStore.delete(secretStore.reference());
                stored.remove("api_key_encrypted");
            }
            Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictFile(path);
            AiSettings persisted = decode(
                    JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject()
            ).settings();
            return actual.equals(persisted);
        } catch (IOException | RuntimeException ignored) {
            // 设置文件不可写或回读校验失败时，当前进程仍可继续使用内存设置。
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响当前设置状态。
                }
            }
        }
    }

    private Decoded decode(JsonObject stored) {
        AiSettings base = GSON.fromJson(stored, AiSettings.class);
        AiApiFormat apiFormat = stored.has("api_format")
                ? AiApiFormat.parse(firstString(stored, "api_format"))
                : base.apiFormat();
        String plaintext = firstString(stored, "apiKey", "api_key");
        String apiKey = plaintext;
        boolean migrate = !plaintext.isBlank();
        boolean removeStoredKey = stored.has("api_key_encrypted") && plaintext.isBlank();
        if (apiKey.isBlank() && "system".equalsIgnoreCase(firstString(stored, "api_key_storage"))) {
            String keyFromSystem = secretStore.get(firstString(stored, "api_key_ref"));
            if (!keyFromSystem.isBlank()) {
                apiKey = keyFromSystem;
                removeStoredKey = false;
            }
        }
        if (apiKey.isBlank() && stored.has("api_key_encrypted")
                && stored.get("api_key_encrypted").isJsonObject()) {
            ApiKeyProtector.DecryptionResult result = protector.decrypt(
                    stored.getAsJsonObject("api_key_encrypted")
            );
            if (result.success()) {
                apiKey = result.plaintext();
                removeStoredKey = false;
            } else if (result.status() == ApiKeyProtector.Status.MISMATCH
                    || result.status() == ApiKeyProtector.Status.INVALID) {
                apiKey = "";
                removeStoredKey = true;
            }
        }
        return new Decoded(copyWithApiKey(base, apiFormat, apiKey), migrate, removeStoredKey);
    }

    private static AiSettings copyWithApiKey(AiSettings base, AiApiFormat apiFormat, String apiKey) {
        return new AiSettings(
                base.mode(),
                apiFormat,
                base.endpoint(),
                base.model(),
                apiKey,
                base.streaming(),
                base.intensity(),
                base.maxRounds(),
                base.maxResults(),
                base.maxContextChars(),
                base.timeoutSeconds()
        );
    }

    private static String firstString(JsonObject value, String... names) {
        for (String name : names) {
            try {
                if (value.has(name) && !value.get(name).isJsonNull()) {
                    String candidate = value.get(name).getAsString().strip();
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            } catch (RuntimeException ignored) {
                // 尝试下一个兼容字段。
            }
        }
        return "";
    }

    private Path parent() {
        Path parent = path.getParent();
        return parent == null ? Path.of(".") : parent;
    }

    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 和不支持 POSIX 权限的文件系统使用系统默认用户权限。
        }
    }

    private static void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 和不支持 POSIX 权限的文件系统使用系统默认用户权限。
        }
    }

    private record Decoded(AiSettings settings, boolean migrate, boolean removeStoredKey) {
    }
}
