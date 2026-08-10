package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 助手消息使用的轻量 Markdown 解析器。
 *
 * <p>它只负责把 Markdown 分成可布局的行，不依赖 Minecraft，因此可以在没有渲染线程的自测试中回归。
 * 未识别的 Markdown 保留为普通文本，避免 AI 回答内容丢失。</p>
 */
public final class MarkdownParser {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)([-*+])\\s+(.+)$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.+)$");
    private static final Pattern BLOCK_QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");

    private MarkdownParser() {
    }

    public static List<MarkdownLine> parse(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] sourceLines = normalized.split("\\n", -1);
        List<MarkdownLine> result = new ArrayList<>();
        boolean inFence = false;

        for (String sourceLine : sourceLines) {
            String trimmed = sourceLine.trim();
            if (isFence(trimmed)) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                result.add(new MarkdownLine(sourceLine, MarkdownLine.Kind.CODE, 0));
                continue;
            }
            if (sourceLine.isBlank()) {
                result.add(new MarkdownLine(" ", MarkdownLine.Kind.BLANK, 0));
                continue;
            }

            Matcher heading = HEADING.matcher(sourceLine);
            if (heading.matches()) {
                result.add(new MarkdownLine(
                        heading.group(2).strip(),
                        MarkdownLine.Kind.HEADING,
                        heading.group(1).length()
                ));
                continue;
            }
            if (trimmed.matches("^(\\*{3,}|-{3,}|_{3,})$")) {
                result.add(new MarkdownLine("────────", MarkdownLine.Kind.HORIZONTAL_RULE, 0));
                continue;
            }

            Matcher quote = BLOCK_QUOTE.matcher(sourceLine);
            if (quote.matches()) {
                result.add(new MarkdownLine("│ " + quote.group(1), MarkdownLine.Kind.BLOCK_QUOTE, 0));
                continue;
            }

            Matcher unordered = UNORDERED_LIST.matcher(sourceLine);
            if (unordered.matches()) {
                result.add(new MarkdownLine(
                        "• " + unordered.group(3),
                        MarkdownLine.Kind.UNORDERED_LIST,
                        indentationLevel(unordered.group(1))
                ));
                continue;
            }

            Matcher ordered = ORDERED_LIST.matcher(sourceLine);
            if (ordered.matches()) {
                result.add(new MarkdownLine(
                        ordered.group(2) + ". " + ordered.group(3),
                        MarkdownLine.Kind.ORDERED_LIST,
                        indentationLevel(ordered.group(1))
                ));
                continue;
            }

            result.add(new MarkdownLine(sourceLine, MarkdownLine.Kind.PARAGRAPH, 0));
        }

        // 空字符串 split 后仍需要一个可见行，否则气泡高度会变成 0。
        if (result.isEmpty()) {
            result.add(new MarkdownLine(" ", MarkdownLine.Kind.BLANK, 0));
        }
        return List.copyOf(result);
    }

    private static boolean isFence(String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static int indentationLevel(String indentation) {
        return Math.min(4, indentation.replace("\\t", "    ").length() / 2);
    }
}
