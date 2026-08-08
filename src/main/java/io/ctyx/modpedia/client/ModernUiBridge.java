package io.ctyx.modpedia.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 通过运行时反射接入 ModernUI，并将模糊结果裁剪为助手窗口内的背景。 */
public final class ModernUiBridge {
    private static final String BLUR_HANDLER = "icyllis.modernui.mc.BlurHandler";
    private static boolean lookupComplete;
    private static Method blurMethod;
    private static Method drawScreenBackgroundMethod;
    private static Object blurInstance;
    private static Field vanillaScreenBlurField;
    private static Field blurEffectField;
    private static Field blurRadiusField;
    private static Field backgroundColorField;
    private static boolean assistantBlurActive;
    private static boolean previousVanillaScreenBlur;
    private static int[] previousBackgroundColor;
    private static TextureTarget clearSnapshot;

    private ModernUiBridge() {
    }

    /**
     * 在切换到助手 Screen 前打开 ModernUI 的模糊资格。
     *
     * <p>ModernUI 自带的 {@code drawScreenBackground} 会把主帧缓冲整体处理。助手
     * 每帧先复制清晰画面，再调用这个入口，最后用四块裁剪区域恢复窗口外的清晰
     * 副本，因此只有玻璃窗口后面的区域保持模糊。</p>
     */
    public static boolean beginAssistantScreen(Screen screen) {
        if (!lookupComplete) {
            lookup();
        }
        if (blurMethod == null
                || drawScreenBackgroundMethod == null
                || blurInstance == null
                || vanillaScreenBlurField == null
                || blurEffectField == null
                || blurRadiusField == null
                || screen == null) {
            return false;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null
                    || !blurEffectField.getBoolean(null)
                    || blurRadiusField.getInt(null) < 1) {
                return false;
            }
            if (!assistantBlurActive) {
                previousVanillaScreenBlur = vanillaScreenBlurField.getBoolean(null);
                vanillaScreenBlurField.setBoolean(null, true);
                if (backgroundColorField != null) {
                    Object value = backgroundColorField.get(null);
                    if (value instanceof int[] colors) {
                        previousBackgroundColor = colors.clone();
                        // 玻璃颜色由助手自己的表面负责，避免 ModernUI 再叠一层黑色全屏遮罩。
                        backgroundColorField.set(null, new int[]{0, 0, 0, 0});
                    }
                }
                assistantBlurActive = true;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            endAssistantScreen();
            return false;
        }
    }

    /**
     * 绘制窗口区域的模糊背景。返回 false 时由调用方继续使用普通半透明表面。
     */
    public static boolean renderLocalizedBackdrop(
            GuiGraphics graphics,
            WindowBounds bounds,
            int guiWidth,
            int guiHeight
    ) {
        if (!assistantBlurActive || graphics == null || bounds == null) {
            return false;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget mainTarget = minecraft.getMainRenderTarget();
            int targetWidth = mainTarget.width;
            int targetHeight = mainTarget.height;
            if (targetWidth < 1 || targetHeight < 1) {
                return false;
            }
            ensureSnapshot(targetWidth, targetHeight);
            copyColor(mainTarget, clearSnapshot);

            drawScreenBackgroundMethod.invoke(
                    blurInstance,
                    graphics,
                    0,
                    0,
                    guiWidth,
                    guiHeight
            );
            restoreOutsideWindow(mainTarget, clearSnapshot, bounds, guiWidth, guiHeight);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            endAssistantScreen();
            return false;
        }
    }

    public static void endAssistantScreen() {
        try {
            if (assistantBlurActive && blurMethod != null && blurInstance != null) {
                blurMethod.invoke(blurInstance, new Object[]{null});
            }
            if (assistantBlurActive && vanillaScreenBlurField != null) {
                vanillaScreenBlurField.setBoolean(null, previousVanillaScreenBlur);
            }
            if (backgroundColorField != null && previousBackgroundColor != null) {
                backgroundColorField.set(null, previousBackgroundColor);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // ModernUI 卸载或重载时，助手仍可使用半透明表面。
        } finally {
            assistantBlurActive = false;
            previousBackgroundColor = null;
            destroySnapshot();
        }
    }

    private static void ensureSnapshot(int width, int height) {
        if (clearSnapshot == null) {
            clearSnapshot = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        } else if (clearSnapshot.width != width || clearSnapshot.height != height) {
            clearSnapshot.resize(width, height, Minecraft.ON_OSX);
        }
    }

    private static void destroySnapshot() {
        if (clearSnapshot != null) {
            clearSnapshot.destroyBuffers();
            clearSnapshot = null;
        }
    }

    private static void copyColor(RenderTarget source, RenderTarget destination) {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(
                0,
                0,
                source.width,
                source.height,
                0,
                0,
                destination.width,
                destination.height,
                GL30.GL_COLOR_BUFFER_BIT,
                GL30.GL_NEAREST
        );
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.frameBufferId);
    }

    private static void restoreOutsideWindow(
            RenderTarget mainTarget,
            TextureTarget snapshot,
            WindowBounds bounds,
            int guiWidth,
            int guiHeight
    ) {
        int targetWidth = mainTarget.width;
        int targetHeight = mainTarget.height;
        int left = scale(bounds.x(), targetWidth, guiWidth);
        int top = scale(bounds.y(), targetHeight, guiHeight);
        int right = scale(bounds.x() + bounds.width(), targetWidth, guiWidth);
        int bottom = scale(bounds.y() + bounds.height(), targetHeight, guiHeight);

        mainTarget.bindWrite(true);
        blitRegion(snapshot, targetWidth, targetHeight, 0, targetHeight - top, targetWidth, top);
        blitRegion(snapshot, targetWidth, targetHeight, 0, 0, targetWidth, targetHeight - bottom);
        blitRegion(snapshot, targetWidth, targetHeight, 0, targetHeight - bottom, left, bottom - top);
        blitRegion(
                snapshot,
                targetWidth,
                targetHeight,
                right,
                targetHeight - bottom,
                targetWidth - right,
                bottom - top
        );
        RenderSystem.disableScissor();
        mainTarget.bindWrite(true);
    }

    private static void blitRegion(
            TextureTarget snapshot,
            int targetWidth,
            int targetHeight,
            int x,
            int y,
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        RenderSystem.enableScissor(x, y, width, height);
        snapshot.blitToScreen(targetWidth, targetHeight, true);
    }

    private static int scale(int value, int targetSize, int guiSize) {
        return Math.max(0, Math.min(targetSize, Math.round(value * targetSize / (float) Math.max(1, guiSize))));
    }

    private static void lookup() {
        lookupComplete = true;
        try {
            Class<?> type = Class.forName(BLUR_HANDLER);
            Field instanceField = type.getField("INSTANCE");
            blurInstance = instanceField.get(null);
            blurMethod = type.getMethod("blur", Screen.class);
            drawScreenBackgroundMethod = type.getMethod(
                    "drawScreenBackground",
                    GuiGraphics.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class
            );
            vanillaScreenBlurField = type.getField("sBlurForVanillaScreens");
            blurEffectField = type.getField("sBlurEffect");
            blurRadiusField = type.getField("sBlurRadius");
            backgroundColorField = type.getField("sBackgroundColor");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            blurMethod = null;
            drawScreenBackgroundMethod = null;
            blurInstance = null;
            vanillaScreenBlurField = null;
            blurEffectField = null;
            blurRadiusField = null;
            backgroundColorField = null;
        }
    }
}
