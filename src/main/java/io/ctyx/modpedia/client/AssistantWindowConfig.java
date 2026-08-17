package io.ctyx.modpedia.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ctyx.modpedia.storage.ModPediaPaths;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 保存客户端最近一次浮窗位置和尺寸。 */
public final class AssistantWindowConfig {
    private static final int CONFIG_VERSION = 3;
    private static final int PREVIOUS_DEFAULT_WIDTH = 440;
    private static final int PREVIOUS_DEFAULT_HEIGHT = 540;
    private static final int PREVIOUS_MIN_WIDTH = 240;
    private static final int PREVIOUS_MIN_HEIGHT = 180;
    private static final int LEGACY_DEFAULT_WIDTH = 520;
    private static final int LEGACY_DEFAULT_HEIGHT = 640;
    private static final int LEGACY_MIN_WIDTH = 320;
    private static final int LEGACY_MIN_HEIGHT = 240;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = runtimePath();

    private static Path runtimePath() {
        ModPediaPaths paths = ModPediaPaths.forConfig(FMLPaths.CONFIGDIR.get());
        paths.migrateLegacyQuietly();
        return paths.assistantWindow();
    }

    public WindowBounds load(WindowBounds fallback) {
        if (!Files.isRegularFile(path)) {
            return fallback;
        }
        try {
            SavedBounds saved = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SavedBounds.class);
            if (saved == null) {
                return fallback;
            }
            // 只迁移阶段四旧的默认尺寸；玩家手动保存过的其它尺寸继续保留。
            if (saved.version < CONFIG_VERSION
                    && saved.width == LEGACY_DEFAULT_WIDTH
                    && saved.height == LEGACY_DEFAULT_HEIGHT) {
                return fallback;
            }
            if (saved.version < CONFIG_VERSION
                    && ((saved.width == LEGACY_MIN_WIDTH && saved.height == LEGACY_MIN_HEIGHT)
                    || (saved.width == PREVIOUS_MIN_WIDTH && saved.height == PREVIOUS_MIN_HEIGHT))) {
                return new WindowBounds(saved.x, saved.y, WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT);
            }
            if (saved.version < CONFIG_VERSION
                    && ((saved.width == PREVIOUS_DEFAULT_WIDTH && saved.height == PREVIOUS_DEFAULT_HEIGHT)
                    || (saved.width == LEGACY_DEFAULT_WIDTH && saved.height == LEGACY_DEFAULT_HEIGHT))) {
                return fallback;
            }
            return new WindowBounds(saved.x, saved.y, saved.width, saved.height);
        } catch (IOException | RuntimeException exception) {
            return fallback;
        }
    }

    public void save(WindowBounds bounds) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(new SavedBounds(bounds)), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // 配置保存失败不影响浮窗本次使用。
        }
    }

    private static final class SavedBounds {
        private final int version;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private SavedBounds(WindowBounds bounds) {
            this.version = CONFIG_VERSION;
            this.x = bounds.x();
            this.y = bounds.y();
            this.width = bounds.width();
            this.height = bounds.height();
        }
    }
}
