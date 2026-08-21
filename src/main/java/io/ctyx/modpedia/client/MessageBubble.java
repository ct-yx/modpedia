package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.api.MessageRole;
import io.ctyx.modpedia.task.TaskSearchSummary;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/** 消息气泡的布局结果；绘制仍由 MessageList 按当前滚动位置完成。 */
public record MessageBubble(
        ChatMessage message,
        int top,
        int width,
        int height,
        List<MarkdownRenderer.RenderedLine> lines,
        boolean showFollowUps
) {
    private static final int FOLLOW_UP_ROW_HEIGHT = 20;
    private static final int FOLLOW_UP_ROW_GAP = 3;
    private static final int SECTION_LABEL_GAP = 3;
    private static final int SECTION_BOTTOM_GAP = 4;
    private static final int TASK_SUMMARY_TOP_GAP = 3;
    private static final int TASK_SUMMARY_BOTTOM_GAP = 3;

    public MessageBubble {
        lines = List.copyOf(lines);
    }

    public int bottom() {
        return top + height + 8;
    }

    public static List<MessageBubble> layout(List<ChatMessage> messages, Font font, int contentWidth) {
        return layout(messages, font, contentWidth, false);
    }

    public static List<MessageBubble> layout(
            List<ChatMessage> messages,
            Font font,
            int contentWidth,
            boolean showIds
    ) {
        List<MessageBubble> result = new ArrayList<>();
        int top = 0;
        int lastAssistantIndex = -1;
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).role() == MessageRole.ASSISTANT) {
                lastAssistantIndex = index;
            }
        }
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            int maxWidth = Math.max(140, (int) (contentWidth * 0.78));
            List<MarkdownRenderer.RenderedLine> lines = MarkdownRenderer.layout(
                    message.markdown(),
                    font,
                    maxWidth - 24,
                    message.sources(),
                    showIds
            );
            int widest = lines.stream().mapToInt(line -> font.width(line.sequence())).max().orElse(80);
            int width = Math.min(maxWidth, Math.max(150, widest + 24));
            boolean showFollowUps = index == lastAssistantIndex && !message.followUpQuestions().isEmpty();
            int height = 14 + bodyHeight(lines, font, width)
                    + taskSummaryHeight(message, font, width)
                    + (showFollowUps ? followUpSectionHeight(font, message.followUpQuestions().size()) : 0);
            result.add(new MessageBubble(message, top, width, height, lines, showFollowUps));
            top += height + 6;
        }
        return result;
    }

    static int bodyHeight(List<MarkdownRenderer.RenderedLine> lines, Font font, int bubbleWidth) {
        int height = 0;
        int annotationWidth = Math.max(1, bubbleWidth - 20);
        for (MarkdownRenderer.RenderedLine line : lines) {
            height += font.lineHeight;
            height += SourceCard.inlineHeight(line.annotations(), font, annotationWidth);
        }
        return height;
    }

    static List<FormattedCharSequence> taskSummaryLines(
            ChatMessage message,
            Font font,
            int bubbleWidth
    ) {
        if (message == null || message.taskSummary() == null || !message.taskSummary().visible()) {
            return List.of();
        }
        return font.split(
                taskSummaryComponent(message.taskSummary()),
                Math.max(80, bubbleWidth - 24)
        );
    }

    static int taskSummaryHeight(ChatMessage message, Font font, int bubbleWidth) {
        List<FormattedCharSequence> lines = taskSummaryLines(message, font, bubbleWidth);
        return lines.isEmpty()
                ? 0
                : TASK_SUMMARY_TOP_GAP
                + lines.size() * font.lineHeight
                + TASK_SUMMARY_BOTTOM_GAP;
    }

    static int taskSummaryTopGap() {
        return TASK_SUMMARY_TOP_GAP;
    }

    static Component taskSummaryComponent(TaskSearchSummary summary) {
        if (summary == null) {
            return Component.empty();
        }
        String key = summary.runtimeProgressAvailable()
                ? "screen.modpedia.task_summary"
                : "screen.modpedia.task_summary_unavailable";
        return summary.runtimeProgressAvailable()
                ? Component.translatable(
                        key,
                        summary.taskDefinitionCount(),
                        summary.runtimeStateCount(),
                        summary.progressItemCount(),
                        summary.timelineEntryCount()
                )
                : Component.translatable(
                        key,
                        summary.taskDefinitionCount(),
                        summary.runtimeStateCount(),
                        summary.timelineEntryCount()
                );
    }

    static int followUpSectionHeight(Font font, int questionCount) {
        if (questionCount <= 0) {
            return 0;
        }
        return SECTION_LABEL_GAP
                + font.lineHeight
                + SECTION_LABEL_GAP
                + questionCount * FOLLOW_UP_ROW_HEIGHT
                + Math.max(0, questionCount - 1) * FOLLOW_UP_ROW_GAP
                + SECTION_BOTTOM_GAP;
    }

    static int followUpRowHeight() {
        return FOLLOW_UP_ROW_HEIGHT;
    }

    static int followUpRowGap() {
        return FOLLOW_UP_ROW_GAP;
    }

    static int sectionLabelGap() {
        return SECTION_LABEL_GAP;
    }

    static int sectionBottomGap() {
        return SECTION_BOTTOM_GAP;
    }
}
