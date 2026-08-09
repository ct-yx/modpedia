package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 浮窗容器的尺寸常量与玻璃表面绘制。 */
public final class FloatingAssistantWindow {
    public static final int HEADER_HEIGHT = 24;
    public static final int CONTENT_PADDING = 8;
    public static final int INPUT_HEIGHT = 20;
    public static final int COLLAPSED_INPUT_HEIGHT = 12;
    public static final int INPUT_FIELD_HEIGHT = 14;
    public static final int SEND_BUTTON_SIZE = 14;
    public static final int INPUT_GAP = 2;
    public static final int COLLAPSED_INPUT_WIDTH = 24;
    public static final int RESIZE_HANDLE_SIZE = 8;
    private static final int COMPACT_HEADER_HEIGHT = 20;
    private static final int MIN_HEADER_HEIGHT = 16;
    private static final int HEADER_ACTION_WIDTH = 38;
    private static final int COMPACT_HEADER_ACTION_WIDTH = 24;
    private static final int HEADER_CLOSE_WIDTH = 32;
    private static final int COMPACT_HEADER_CLOSE_WIDTH = 20;
    private static final int HEADER_ACTION_GAP = 2;

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
        renderSurface(graphics, bounds, style, opaque, INPUT_HEIGHT, HEADER_HEIGHT);
    }

    /**
     * 绘制窗口表面；输入区折叠时只绘制折叠后的底部带，避免多出来的空白
     * 色块占用消息区域。
     */
    public static void renderSurface(
            GuiGraphics graphics,
            WindowBounds bounds,
            AssistantGlassConfig.Style style,
            boolean opaque,
            int inputHeight
    ) {
        renderSurface(graphics, bounds, style, opaque, inputHeight, headerHeight(bounds));
    }

    public static void renderSurface(
            GuiGraphics graphics,
            WindowBounds bounds,
            AssistantGlassConfig.Style style,
            boolean opaque,
            int inputHeight,
            int headerHeight
    ) {
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        int bottom = top + bounds.height();
        int clampedInputHeight = Math.max(0, Math.min(bounds.height(), inputHeight));
        int clampedHeaderHeight = Math.max(1, Math.min(bounds.height(), headerHeight));
        // 两层蓝光边缘提供发光轮廓；不再绘制底部黑色叠层，避免出现无意义的
        // 模糊/阴影带，玻璃主体保持单层半透明。
        graphics.fill(left - 3, top - 2, right + 3, bottom + 2, style.glowOuterColor());
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, style.glowInnerColor());
        graphics.fill(left, top, right, bottom, opaque ? style.opaquePanelColor() : style.panelColor());
        graphics.fill(left, top, right, top + clampedHeaderHeight,
                opaque ? style.opaqueHeaderColor() : style.headerColor());
        if (clampedInputHeight > 0) {
            graphics.fill(left, bottom - clampedInputHeight, right, bottom,
                    opaque ? style.opaqueInputColor() : style.inputColor());
        }
        graphics.renderOutline(left, top, bounds.width(), bounds.height(), style.outlineColor());
        graphics.renderOutline(left + 1, top + 1, bounds.width() - 2, bounds.height() - 2, style.glowInnerColor());
        graphics.fill(left, top + clampedHeaderHeight - 1, right, top + clampedHeaderHeight,
                style.glowInnerColor());
    }

    public static int headerHeight(WindowBounds bounds) {
        if (bounds.width() <= 180 || bounds.height() <= 120) {
            return MIN_HEADER_HEIGHT;
        }
        if (bounds.width() < 240 || bounds.height() < 150) {
            return COMPACT_HEADER_HEIGHT;
        }
        return HEADER_HEIGHT;
    }

    public static int headerActionWidth(int windowWidth) {
        return windowWidth < 240 ? COMPACT_HEADER_ACTION_WIDTH : HEADER_ACTION_WIDTH;
    }

    public static int headerCloseWidth(int windowWidth) {
        return windowWidth < 240 ? COMPACT_HEADER_CLOSE_WIDTH : HEADER_CLOSE_WIDTH;
    }

    public static int headerActionsStart(WindowBounds bounds) {
        int actionWidth = headerActionWidth(bounds.width());
        int closeWidth = headerCloseWidth(bounds.width());
        return bounds.x() + bounds.width() - closeWidth - HEADER_ACTION_GAP
                - actionWidth * 2 - HEADER_ACTION_GAP;
    }

    public static int headerActionGap() {
        return HEADER_ACTION_GAP;
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
        renderHeader(graphics, font, bounds, title, status, style, titleColor, subtleColor,
                headerHeight(bounds));
    }

    public static void renderHeader(
            GuiGraphics graphics,
            Font font,
            WindowBounds bounds,
            Component title,
            String status,
            AssistantGlassConfig.Style style,
            int titleColor,
            int subtleColor,
            int headerHeight
    ) {
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        int titleLeft = left + 32;
        int titleWidth = Math.max(1, headerActionsStart(bounds) - titleLeft - 4);
        String titleText = font.plainSubstrByWidth(title.getString(), titleWidth);
        boolean compact = bounds.width() < 240 || headerHeight < HEADER_HEIGHT;
        if (!compact) {
            graphics.drawString(font, Component.literal("✦"), left + 16, top + 10, style.accentColor(), false);
        }
        if (bounds.width() >= 200) {
            graphics.drawString(font, Component.literal(titleText), titleLeft,
                    top + Math.max(2, (headerHeight - font.lineHeight) / 3), titleColor, false);
        }
        // 窄窗口保留标题和三个可点击控件，状态文本让位给操作区，避免标题栏
        // 的文字和历史/设置按钮发生层级与点击区域重叠。
        if (bounds.width() >= 240 && headerHeight >= HEADER_HEIGHT) {
            String statusText = font.plainSubstrByWidth(status, titleWidth);
            graphics.drawString(font, Component.literal(statusText), titleLeft, top + 14, subtleColor, false);
        }
        graphics.drawCenteredString(
                font,
                Component.literal("×"),
                right - headerCloseWidth(bounds.width()) / 2,
                top + Math.max(0, (headerHeight - font.lineHeight) / 2),
                titleColor
        );
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
