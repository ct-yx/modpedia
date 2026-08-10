package io.ctyx.modpedia.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** 将轻量 Markdown 行转换为 Minecraft 的带样式文本并按字体宽度换行。 */
public final class MarkdownRenderer {
    private static final int CODE_COLOR = 0xFFD5E7FF;
    private static final int HEADING_COLOR = 0xFF8ED8FF;
    private static final int QUOTE_COLOR = 0xFFB8C3D3;
    private static final int LINK_COLOR = 0xFF8ED8FF;
    private static final int INLINE_CODE_COLOR = 0xFFFFD58A;

    private MarkdownRenderer() {
    }

    public static List<RenderedLine> layout(String markdown, Font font, int width) {
        List<RenderedLine> result = new ArrayList<>();
        int lineWidth = Math.max(1, width);
        for (MarkdownLine line : MarkdownParser.parse(markdown)) {
            Component component = component(line);
            List<FormattedCharSequence> wrapped = font.split(component, lineWidth);
            if (wrapped.isEmpty()) {
                result.add(new RenderedLine(FormattedCharSequence.EMPTY, line));
            } else {
                for (FormattedCharSequence sequence : wrapped) {
                    result.add(new RenderedLine(sequence, line));
                }
            }
        }
        return List.copyOf(result);
    }

    static Component component(MarkdownLine line) {
        if (line.kind() == MarkdownLine.Kind.CODE) {
            return Component.literal(line.text()).withStyle(Style.EMPTY.withColor(CODE_COLOR));
        }
        if (line.kind() == MarkdownLine.Kind.BLANK) {
            return Component.literal(" ");
        }
        if (line.kind() == MarkdownLine.Kind.HORIZONTAL_RULE) {
            return Component.literal(line.text()).withStyle(ChatFormatting.DARK_GRAY);
        }

        Style base = baseStyle(line.kind());
        String text = line.text();
        MutableComponent result = Component.empty();
        if (line.kind() == MarkdownLine.Kind.UNORDERED_LIST) {
            int prefixLength = Math.min(2, text.length());
            result.append(Component.literal("  ".repeat(line.level()) + text.substring(0, prefixLength))
                    .withStyle(base.withColor(ChatFormatting.AQUA)));
            appendInline(result, text.substring(prefixLength), base);
        } else if (line.kind() == MarkdownLine.Kind.ORDERED_LIST) {
            int prefixLength = orderedPrefixLength(text);
            result.append(Component.literal("  ".repeat(line.level()) + text.substring(0, prefixLength))
                    .withStyle(base.withColor(ChatFormatting.AQUA)));
            appendInline(result, text.substring(prefixLength), base);
        } else {
            appendInline(result, text, base);
        }
        return result;
    }

    private static Style baseStyle(MarkdownLine.Kind kind) {
        return switch (kind) {
            case HEADING -> Style.EMPTY.withBold(true).withColor(HEADING_COLOR);
            case BLOCK_QUOTE -> Style.EMPTY.withColor(QUOTE_COLOR).withItalic(true);
            default -> Style.EMPTY;
        };
    }

    private static void appendInline(MutableComponent target, String text, Style base) {
        for (MarkdownInlineSpan span : MarkdownInlineSpan.parse(text)) {
            Style style = base;
            if (span.bold()) {
                style = style.withBold(true);
            }
            if (span.italic()) {
                style = style.withItalic(true);
            }
            if (span.strikethrough()) {
                style = style.withStrikethrough(true);
            }
            if (span.code()) {
                style = style.withColor(INLINE_CODE_COLOR);
            }
            if (span.link()) {
                style = style.withColor(LINK_COLOR).withUnderlined(true);
            }
            target.append(Component.literal(span.text()).withStyle(style));
        }
    }

    private static int orderedPrefixLength(String text) {
        int dot = text.indexOf(". ");
        return dot < 0 ? 0 : dot + 2;
    }

    public record RenderedLine(FormattedCharSequence sequence, MarkdownLine source) {
    }
}
