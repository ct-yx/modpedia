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

    public synchronized void save(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "ai-", ".tmp");
            Files.writeString(temporary, GSON.toJson(actual), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 设置文件不可写时，当前进程继续使用内存设置。
        }
    }

    public Path path() {
        return path;
    }
}
