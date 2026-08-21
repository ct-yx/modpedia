package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.ChatMessage;
import net.minecraft.client.gui.Font;

import java.util.List;

/** 消息列表布局适配器，隔离文本换行和窗口渲染层。 */
public final class MessageList {
    private MessageList() {
    }

    public static List<MessageBubble> layout(List<ChatMessage> messages, Font font, int contentWidth) {
        return MessageBubble.layout(messages, font, contentWidth, false);
    }

    public static List<MessageBubble> layout(
            List<ChatMessage> messages,
            Font font,
            int contentWidth,
            boolean showIds
    ) {
        return MessageBubble.layout(messages, font, contentWidth, showIds);
    }
}
