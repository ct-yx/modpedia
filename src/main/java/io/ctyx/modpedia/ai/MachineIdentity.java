package io.ctyx.modpedia.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取用于本地密钥保护的系统标识；原始标识不会写入配置文件。 */
public final class MachineIdentity {
    private static final int MAX_COMMAND_OUTPUT = 64 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Pattern MAC_UUID = Pattern.compile(
            "IOPlatformUUID\\\"\\s*=\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern WINDOWS_UUID = Pattern.compile(
            "MachineGuid\\s+REG_SZ\\s+(.+)"
    );

    private MachineIdentity() {
    }

    /** 返回当前平台的稳定系统标识；系统没有可读取的标识时返回空值。 */
    public static Optional<String> current() {
        String override = normalize(System.getenv("MODPEDIA_SYSTEM_UUID"));
        if (!override.isBlank()) {
            return Optional.of(override);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    .map(MAC_UUID::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> normalize(matcher.group(1)))
                    .filter(value -> !value.isBlank());
        }
        if (os.contains("win")) {
            return command("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .map(WINDOWS_UUID::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> normalize(matcher.group(1)))
                    .filter(value -> !value.isBlank());
        }
        if (os.contains("linux")) {
            for (Path candidate : new Path[]{
                    Path.of("/etc/machine-id"),
                    Path.of("/var/lib/dbus/machine-id")
            }) {
                try {
                    if (Files.isRegularFile(candidate)) {
                        String value = normalize(Files.readString(candidate, StandardCharsets.UTF_8));
                        if (!value.isBlank()) {
                            return Optional.of(value);
                        }
                    }
                } catch (IOException ignored) {
                    // 尝试下一个平台来源。
                }
            }
        }
        return Optional.empty();
    }

    /** 为无法读取系统标识的平台生成并持久化安装级回退标识。 */
    public static String forSettings(Path settingsPath) {
        Optional<String> system = current();
        if (system.isPresent()) {
            return system.get();
        }
        Path parent = settingsPath.toAbsolutePath().normalize().getParent();
        Path fallback = (parent == null ? Path.of(".") : parent).resolve("installation-id");
        try {
            Files.createDirectories(fallback.getParent());
            if (Files.isRegularFile(fallback)) {
                String value = normalize(Files.readString(fallback, StandardCharsets.UTF_8));
                if (!value.isBlank()) {
                    return value;
                }
            }
            String value = UUID.randomUUID().toString();
            Files.writeString(fallback, value, StandardCharsets.UTF_8);
            return value;
        } catch (IOException ignored) {
            // 只读环境使用进程级标识，正常配置目录优先使用持久化标识。
            return "volatile:" + UUID.randomUUID();
        }
    }

    public static String fingerprint(String identity) {
        return hex(sha256("ModPedia/machine-fingerprint/v1:" + normalize(identity)));
    }

    static String normalize(String value) {
        return value == null ? "" : value.strip().replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static Optional<String> command(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(
                    process.getInputStream().readNBytes(MAX_COMMAND_OUTPUT),
                    StandardCharsets.UTF_8
            );
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            return Optional.empty();
        } catch (InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return Optional.empty();
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

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
