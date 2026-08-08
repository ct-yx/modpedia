package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** 消息气泡的布局结果；绘制仍由 MessageList 按当前滚动位置完成。 */
public record MessageBubble(
        ChatMessage message,
        int top,
        int width,
        int height,
        List<FormattedCharSequence> lines
) {
    public MessageBubble {
        lines = List.copyOf(lines);
    }

    public int bottom() {
        return top + height + 8;
    }

    public static List<MessageBubble> layout(List<ChatMessage> messages, Font font, int contentWidth) {
        List<MessageBubble> result = new ArrayList<>();
        int top = 0;
        for (ChatMessage message : messages) {
            int maxWidth = Math.max(140, (int) (contentWidth * 0.78));
            List<FormattedCharSequence> lines = wrap(message.markdown(), font, maxWidth - 24);
            int widest = lines.stream().mapToInt(font::width).max().orElse(80);
            int width = Math.min(maxWidth, Math.max(150, widest + 24));
            int height = 14 + lines.size() * font.lineHeight + message.sources().size() * 16;
            result.add(new MessageBubble(message, top, width, height, lines));
            top += height + 6;
        }
        return result;
    }

    private static List<FormattedCharSequence> wrap(String text, Font font, int width) {
        List<FormattedCharSequence> result = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            String value = paragraph.isEmpty() ? " " : paragraph;
            result.addAll(font.split(Component.literal(value), width));
        }
        return result;
    }
}
