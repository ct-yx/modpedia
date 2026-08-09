package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/** 助手二级页面统一使用的轻量按钮，不使用原版按钮纹理。 */
class AssistantPanelButton extends AbstractWidget {
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int DISABLED_COLOR = 0xFF72839A;

    private final Font font;
    private final AssistantGlassConfig.Style style;
    private final Supplier<Component> labelSupplier;
    private final Runnable action;

    AssistantPanelButton(
            Font font,
            AssistantGlassConfig.Style style,
            int x,
            int y,
            int width,
            int height,
            Component message,
            Runnable action
    ) {
        this(font, style, x, y, width, height, () -> message, action);
    }

    AssistantPanelButton(
            Font font,
            AssistantGlassConfig.Style style,
            int x,
            int y,
            int width,
            int height,
            Supplier<Component> labelSupplier,
            Runnable action
    ) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.style = style;
        this.labelSupplier = labelSupplier;
        this.action = action;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Component label = displayMessage();
        setMessage(label);

        if (active && isHoveredOrFocused()) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, style.glowInnerColor());
        }
        graphics.renderOutline(getX(), getY(), width, height, style.glowInnerColor());

        String text = font.plainSubstrByWidth(label.getString(), Math.max(1, width - 8));
        graphics.drawCenteredString(
                font,
                Component.literal(text),
                getX() + width / 2,
                getY() + Math.max(0, (height - font.lineHeight) / 2),
                active ? TEXT_COLOR : DISABLED_COLOR
        );
    }

    protected Component displayMessage() {
        return labelSupplier.get();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (active) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
