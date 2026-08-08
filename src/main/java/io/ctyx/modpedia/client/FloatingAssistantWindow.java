package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 浮窗容器的尺寸常量与玻璃表面绘制。 */
public final class FloatingAssistantWindow {
    public static final int HEADER_HEIGHT = 30;
    public static final int CONTENT_PADDING = 12;
    public static final int INPUT_HEIGHT = 36;
    public static final int INPUT_FIELD_HEIGHT = 22;
    public static final int SEND_BUTTON_SIZE = 22;
    public static final int INPUT_GAP = 6;
    public static final int RESIZE_HANDLE_SIZE = 8;

    private FloatingAssistantWindow() {
    }

    /**
     * Minecraft 1.21.1 自带高对比度选项；无障碍模式下不使用透明表面。
     * JVM 属性同时给测试和桌面无障碍启动器留下一个不依赖第三方 API 的开关。
     */
    public static boolean prefersOpaqueSurface(Minecraft minecraft) {
        return Boolean.getBoolean("modpedia.reduceTransparency")
                || Boolean.TRUE.equals(minecraft.options.highContrast().get());
    }

    public static void renderSurface(
            GuiGraphics graphics,
            WindowBounds bounds,
            AssistantGlassConfig.Style style,
            boolean opaque
    ) {
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        int bottom = top + bounds.height();
        // 两层蓝光边缘代替黑色阴影，玻璃表面仍保持单层半透明。
        graphics.fill(left - 3, top - 2, right + 3, bottom + 2, style.glowOuterColor());
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, style.glowInnerColor());
        graphics.fill(left + 4, top + 5, right + 4, bottom + 5, 0x30000000);
        graphics.fill(left, top, right, bottom, opaque ? style.opaquePanelColor() : style.panelColor());
        graphics.fill(left, top, right, top + HEADER_HEIGHT, opaque ? style.opaqueHeaderColor() : style.headerColor());
        graphics.fill(left, bottom - INPUT_HEIGHT, right, bottom, opaque ? style.opaqueInputColor() : style.inputColor());
        graphics.renderOutline(left, top, bounds.width(), bounds.height(), style.outlineColor());
        graphics.renderOutline(left + 1, top + 1, bounds.width() - 2, bounds.height() - 2, style.glowInnerColor());
        graphics.fill(left, top + HEADER_HEIGHT - 1, right, top + HEADER_HEIGHT, style.glowInnerColor());
    }

    public static void renderHeader(
            GuiGraphics graphics,
            Font font,
            WindowBounds bounds,
            Component title,
            String status,
            AssistantGlassConfig.Style style,
            int titleColor,
            int subtleColor
    ) {
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        graphics.drawString(font, Component.literal("✦"), left + 16, top + 11, style.accentColor(), false);
        graphics.drawString(font, title, left + 32, top + 5, titleColor, false);
        graphics.drawString(font, Component.literal(status), left + 32, top + 18, subtleColor, false);
        graphics.drawString(font, Component.literal("×"), right - 25, top + 6, titleColor, false);
    }

    public static void renderResizeHandles(
            GuiGraphics graphics,
            WindowBounds bounds,
            WindowBounds.ResizeEdge edge,
            AssistantGlassConfig.Style style
    ) {
        int color = edge == WindowBounds.ResizeEdge.NONE ? style.glowInnerColor() : style.accentColor();
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        int bottom = top + bounds.height();
        graphics.hLine(left + 8, right - 8, top + 2, color);
        graphics.hLine(left + 8, right - 8, bottom - 3, color);
        graphics.vLine(left + 2, top + 8, bottom - 8, color);
        graphics.vLine(right - 3, top + 8, bottom - 8, color);
        graphics.hLine(left + 4, left + 12, top + 3, color);
        graphics.vLine(left + 3, top + 4, top + 12, color);
        graphics.hLine(right - 12, right - 4, top + 3, color);
        graphics.vLine(right - 4, top + 4, top + 12, color);
        graphics.hLine(left + 4, left + 12, bottom - 4, color);
        graphics.vLine(left + 3, bottom - 12, bottom - 4, color);
        graphics.hLine(right - 12, right - 4, bottom - 4, color);
        graphics.vLine(right - 4, bottom - 12, bottom - 4, color);
    }
}
