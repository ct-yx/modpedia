package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** AI 设置的本地 JSON 存储。 */
public final class AiSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;

    public AiSettingsStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public static AiSettingsStore runtime() {
        return new AiSettingsStore(FMLPaths.CONFIGDIR.get()
                .resolve("modpedia")
                .resolve("ai.json"));
    }

    public synchronized AiSettings load() {
        if (!Files.isRegularFile(path)) {
            AiSettings defaults = AiSettings.defaults();
            save(defaults);
            return defaults;
        }
        try {
            AiSettings settings = GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8),
                    AiSettings.class
            );
            return settings == null ? AiSettings.defaults() : settings;
        } catch (IOException | RuntimeException exception) {
            return AiSettings.defaults();
        }
    }

    /** 保存并回读校验；失败时返回 false，调用方可以在设置页明确提示用户。 */
    public synchronized boolean save(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "ai-", ".tmp");
            Files.writeString(temporary, GSON.toJson(actual), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            AiSettings persisted = GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8),
                    AiSettings.class
            );
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

    public Path path() {
        return path;
    }
}
