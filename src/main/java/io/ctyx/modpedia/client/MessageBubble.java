package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/** 消息气泡的布局结果；绘制仍由 MessageList 按当前滚动位置完成。 */
public record MessageBubble(
        ChatMessage message,
        int top,
        int width,
        int height,
        List<MarkdownRenderer.RenderedLine> lines
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
            List<MarkdownRenderer.RenderedLine> lines = MarkdownRenderer.layout(
                    message.markdown(),
                    font,
                    maxWidth - 24
            );
            int widest = lines.stream().mapToInt(line -> font.width(line.sequence())).max().orElse(80);
            int width = Math.min(maxWidth, Math.max(150, widest + 24));
            int height = 14 + lines.size() * font.lineHeight + message.sources().size() * 16;
            result.add(new MessageBubble(message, top, width, height, lines));
            top += height + 6;
        }
        return result;
    }
}
