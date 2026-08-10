package io.ctyx.modpedia.client;

/**
 * Markdown 解析后的一个可绘制行。
 *
 * <p>这里保留结构信息而不是只保留纯文本，客户端可以据此选择标题、代码和列表的视觉样式，
 * 同时仍然把完整回答交给 Minecraft 的 Font 做最终换行。</p>
 */
public record MarkdownLine(String text, Kind kind, int level) {
    public MarkdownLine {
        text = text == null ? "" : text;
        kind = kind == null ? Kind.PARAGRAPH : kind;
        level = Math.max(0, level);
    }

    public enum Kind {
        BLANK,
        PARAGRAPH,
        HEADING,
        UNORDERED_LIST,
        ORDERED_LIST,
        BLOCK_QUOTE,
        CODE,
        HORIZONTAL_RULE
    }
}
