package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.SourceReference;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 回答正文来源标注区的手册跳转按钮及其可点击区域。 */
public record SourceCard(SourceReference source, int left, int top, int width, int height) {
    public static final int INLINE_HEIGHT = 18;
    public static final int INLINE_GAP = 3;

    public boolean contains(double x, double y) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    public void render(GuiGraphics graphics, Font font, int backgroundColor, int textColor) {
        graphics.fill(left, top, left + width, top + height, backgroundColor);
        graphics.renderOutline(left, top, width, height, textColor & 0x66FFFFFF | 0x66000000);
        String label = source.displayLabel().isBlank() ? source.documentId() : source.displayLabel();
        label = font.plainSubstrByWidth(label, Math.max(1, width - 10));
        graphics.drawString(font, Component.literal("↗ " + label), left + 4, top + 3, textColor, false);
    }

    /** 在正文行后按可用宽度排列短来源标注，超出后自动换行。 */
    static List<SourceCard> inlineLayout(
            List<SourceReference> sources,
            Font font,
            int left,
            int top,
            int availableWidth
    ) {
        if (sources == null || sources.isEmpty() || font == null || availableWidth <= 0) {
            return List.of();
        }
        List<SourceCard> result = new ArrayList<>();
        int right = left + availableWidth;
        int x = left;
        int y = top;
        for (SourceReference source : sources) {
            if (source == null) {
                continue;
            }
            int chipWidth = chipWidth(source, font, availableWidth);
            if (x > left && x + chipWidth > right) {
                x = left;
                y += INLINE_HEIGHT + INLINE_GAP;
            }
            result.add(new SourceCard(source, x, y, chipWidth, INLINE_HEIGHT));
            x += chipWidth + INLINE_GAP;
        }
        return List.copyOf(result);
    }

    static int inlineHeight(List<SourceReference> sources, Font font, int availableWidth) {
        List<SourceCard> cards = inlineLayout(sources, font, 0, 0, availableWidth);
        if (cards.isEmpty()) {
            return 0;
        }
        int lastRow = cards.get(cards.size() - 1).top();
        return lastRow + INLINE_HEIGHT;
    }

    private static int chipWidth(SourceReference source, Font font, int availableWidth) {
        String label = source.displayLabel().isBlank() ? source.documentId() : source.displayLabel();
        int preferred = font.width("↗ " + label) + 10;
        int maximum = Math.min(220, Math.max(1, availableWidth));
        int minimum = Math.min(52, maximum);
        return Math.min(maximum, Math.max(minimum, preferred));
    }
}
