package io.ctyx.modpedia.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 可选的操作系统密钥环适配层。
 *
 * <p>不引入平台专用 Java 依赖：macOS 使用 security，Linux 使用 secret-tool。
 * 命令不可用或密钥环拒绝访问时由 {@link AiSettingsStore} 回退到机器绑定的
 * AES-GCM 密文，不能因为安全组件缺失阻断 Worker 启动。macOS 的 security CLI
 * 只支持把写入值作为参数传入，因此该值仅在短暂的子进程生命周期内出现，不写入日志；
 * Linux 的 secret-tool 仍通过 stdin 接收。</p>
 */
final class SystemSecretStore {
    private static final String SERVICE = "io.ctyx.modpedia.modpedia";
    private static final String ACCOUNT = "api-key";
    private static final long COMMAND_TIMEOUT_SECONDS = 3L;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private enum Backend {
        MACOS,
        LINUX,
        NONE
    }

    private final Backend backend;
    private final boolean enabled;

    private SystemSecretStore(Backend backend, boolean enabled) {
        this.backend = backend;
        this.enabled = enabled;
    }

    static SystemSecretStore current() {
        // 集成测试和诊断夹具必须避免修改开发机的真实密钥环。
        if (Boolean.getBoolean("modpedia.disable.system.keychain")) {
            return disabled();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && commandAvailable("/usr/bin/security")) {
            return new SystemSecretStore(Backend.MACOS, true);
        }
        if (os.contains("linux") && commandAvailable("secret-tool")) {
            return new SystemSecretStore(Backend.LINUX, true);
        }
        return disabled();
    }

    static SystemSecretStore disabled() {
        return new SystemSecretStore(Backend.NONE, false);
    }

    String reference() {
        return SERVICE + "/" + ACCOUNT;
    }

    boolean put(String secret) {
        if (!enabled || secret == null || secret.isBlank()) {
            return false;
        }
        try {
            return switch (backend) {
                case MACOS -> run(
                        List.of("/usr/bin/security", "add-generic-password",
                                "-a", ACCOUNT, "-s", SERVICE, "-U", "-w", secret),
                        ""
                ).success();
                case LINUX -> run(
                        List.of("secret-tool", "store", "--label=ModPedia API Key",
                                "service", SERVICE, "account", ACCOUNT),
                        secret
                ).success();
                case NONE -> false;
            };
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    String get(String reference) {
        if (!enabled || !reference().equals(reference)) {
            return "";
        }
        try {
            CommandResult result = switch (backend) {
                case MACOS -> run(
                        List.of("/usr/bin/security", "find-generic-password",
                                "-a", ACCOUNT, "-s", SERVICE, "-w"),
                        ""
                );
                case LINUX -> run(
                        List.of("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT),
                        ""
                );
                case NONE -> CommandResult.failure();
            };
            return result.success() ? result.output().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    void delete(String reference) {
        if (!enabled || !reference().equals(reference)) {
            return;
        }
        try {
            switch (backend) {
                case MACOS -> run(
                        List.of("/usr/bin/security", "delete-generic-password",
                                "-a", ACCOUNT, "-s", SERVICE),
                        ""
                );
                case LINUX -> run(
                        List.of("secret-tool", "clear", "service", SERVICE, "account", ACCOUNT),
                        ""
                );
                case NONE -> {
                }
            }
        } catch (RuntimeException ignored) {
            // 密钥环删除失败不影响新的空设置落盘；下次保存会再次尝试。
        }
    }

    private CommandResult run(List<String> command, String stdin) {
        Process process = null;
        try {
            process = new ProcessBuilder(new ArrayList<>(command))
                    .redirectErrorStream(true)
                    .start();
            if (stdin != null && !stdin.isEmpty()) {
                try (OutputStream output = process.getOutputStream()) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                }
            } else {
                process.getOutputStream().close();
            }
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CommandResult.failure();
            }
            byte[] output = readLimited(process.getInputStream());
            return process.exitValue() == 0
                    ? new CommandResult(true, new String(output, StandardCharsets.UTF_8))
                    : CommandResult.failure();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
            return CommandResult.failure();
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                throw new IOException("系统密钥存储命令输出过大");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean commandAvailable(String command) {
        if (command.endsWith("security")) {
            return Files.isExecutable(Path.of(command));
        }
        try {
            List<String> probe = List.of(command, "--version");
            Process process = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(1L, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            if (finished) {
                process.getInputStream().transferTo(OutputStream.nullOutputStream());
            }
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private record CommandResult(boolean success, String output) {
        private static CommandResult failure() {
            return new CommandResult(false, "");
        }
    }
}
