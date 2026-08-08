package io.ctyx.modpedia.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 助手玻璃表面的客户端颜色、透明度和发光强度配置。 */
public final class AssistantGlassConfig {
    private static final String DEFAULT_THEME_COLOR = "#4D9CFF";
    private static final float DEFAULT_BACKGROUND_OPACITY = 0.70f;
    private static final float DEFAULT_GLOW = 0.78f;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get()
            .resolve("modpedia")
            .resolve("assistant-glass.json");
    private static final Settings DEFAULTS = new Settings(
            DEFAULT_THEME_COLOR,
            DEFAULT_BACKGROUND_OPACITY,
            DEFAULT_GLOW
    );

    private AssistantGlassConfig() {
    }

    public static Style load() {
        Settings settings = DEFAULTS;
        if (Files.isRegularFile(PATH)) {
            try {
                Settings loaded = GSON.fromJson(
                        Files.readString(PATH, StandardCharsets.UTF_8),
                        Settings.class
                );
                if (loaded != null) {
                    settings = loaded;
                    if (settings.themeColor == null && settings.color != null) {
                        settings.themeColor = settings.color;
                        settings.color = null;
                    }
                    if (settings.backgroundOpacity == null && settings.opacity != null) {
                        settings.backgroundOpacity = settings.opacity;
                        settings.opacity = null;
                    }
                    // 读取旧版配置后立即写回新字段，玩家打开文件即可直接调节主题色和透明度。
                    if (settings.themeColor != null || settings.backgroundOpacity != null) {
                        save(settings);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 使用默认玻璃样式，浮窗仍然可以正常打开。
            }
        } else {
            saveDefaults();
        }
        return Style.from(settings);
    }

    private static void saveDefaults() {
        save(DEFAULTS);
    }

    private static void save(Settings settings) {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 配置目录不可写时使用内存中的默认样式。
        }
    }

    private static int parseColor(String value) {
        if (value == null) {
            return 0xFF4D9CFF;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            int parsed = (int) Long.parseLong(hex, 16);
            return switch (hex.length()) {
                case 6 -> 0xFF000000 | parsed;
                case 8 -> parsed;
                default -> 0xFF4D9CFF;
            };
        } catch (NumberFormatException ignored) {
            return 0xFF4D9CFF;
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int shade(int color, float factor) {
        int red = Math.round(((color >> 16) & 0xFF) * factor);
        int green = Math.round(((color >> 8) & 0xFF) * factor);
        int blue = Math.round((color & 0xFF) * factor);
        return (Math.max(0, Math.min(255, red)) << 16)
                | (Math.max(0, Math.min(255, green)) << 8)
                | Math.max(0, Math.min(255, blue));
    }

    private static int alpha(float opacity) {
        return Math.round(Math.max(0.25f, Math.min(0.94f, opacity)) * 255.0f);
    }

    private static int glowAlpha(float strength, int maximum) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, strength)) * maximum);
    }

    public record Style(
            int accentColor,
            int panelColor,
            int headerColor,
            int inputColor,
            int assistantBubbleColor,
            int userBubbleColor,
            int sourceColor,
            int outlineColor,
            int glowOuterColor,
            int glowInnerColor,
            int opaquePanelColor,
            int opaqueHeaderColor,
            int opaqueInputColor,
            int opaqueAssistantBubbleColor,
            int opaqueUserBubbleColor,
            int opaqueSourceColor
    ) {
        private static Style from(Settings settings) {
            String configuredColor = settings.themeColor != null
                    ? settings.themeColor
                    : settings.color;
            float configuredOpacity = settings.backgroundOpacity != null
                    ? settings.backgroundOpacity
                    : settings.opacity != null ? settings.opacity : DEFAULT_BACKGROUND_OPACITY;
            float configuredGlow = settings.glow != null ? settings.glow : DEFAULT_GLOW;
            int color = parseColor(configuredColor);
            int rgb = color & 0x00FFFFFF;
            int surfaceAlpha = alpha(configuredOpacity);
            int accent = withAlpha(rgb, 0xFF);
            return new Style(
                    accent,
                    withAlpha(shade(rgb, 0.30f), surfaceAlpha),
                    withAlpha(shade(rgb, 0.52f), Math.min(0xF2, surfaceAlpha + 0x18)),
                    withAlpha(shade(rgb, 0.38f), Math.min(0xEE, surfaceAlpha + 0x0C)),
                    withAlpha(shade(rgb, 0.30f), Math.min(0xEE, surfaceAlpha + 0x10)),
                    withAlpha(shade(rgb, 0.48f), Math.min(0xF0, surfaceAlpha + 0x08)),
                    withAlpha(shade(rgb, 0.34f), Math.min(0xDE, surfaceAlpha + 0x02)),
                    withAlpha(rgb, 0xD0),
                    withAlpha(rgb, glowAlpha(configuredGlow, 0x48)),
                    withAlpha(rgb, glowAlpha(configuredGlow, 0xA0)),
                    withAlpha(shade(rgb, 0.18f), 0xFF),
                    withAlpha(shade(rgb, 0.34f), 0xFF),
                    withAlpha(shade(rgb, 0.26f), 0xFF),
                    withAlpha(shade(rgb, 0.18f), 0xFF),
                    withAlpha(shade(rgb, 0.34f), 0xFF),
                    withAlpha(shade(rgb, 0.24f), 0xFF)
            );
        }
    }

    private static final class Settings {
        /** 当前配置字段；使用包装类型以便识别旧版字段缺失。 */
        private String themeColor;
        private Float backgroundOpacity;
        private Float glow;

        /** v1/v2 字段，保留读取兼容性。 */
        @SuppressWarnings("unused")
        private String color;
        @SuppressWarnings("unused")
        private Float opacity;

        private Settings() {
        }

        private Settings(String themeColor, float backgroundOpacity, float glow) {
            this.themeColor = themeColor;
            this.backgroundOpacity = backgroundOpacity;
            this.glow = glow;
        }
    }
}
