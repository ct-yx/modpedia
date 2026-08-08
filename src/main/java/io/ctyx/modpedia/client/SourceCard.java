package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 回答底部的来源卡片及其可点击区域。 */
public record SourceCard(SourceReference source, int left, int top, int width, int height) {
    public boolean contains(double x, double y) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    public void render(GuiGraphics graphics, Font font, int backgroundColor, int textColor) {
        graphics.fill(left, top, left + width, top + height, backgroundColor);
        graphics.drawString(font, Component.literal("↗ " + source.title()), left + 4, top + 3, textColor, false);
    }
}
