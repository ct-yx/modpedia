package io.ctyx.modpedia.client;

/** Java 8 兼容的 Markdown 块级行模型。 */
public final class LegacyMarkdownLine {
    public enum Kind {
        BLANK,
        PARAGRAPH,
        HEADING,
        TABLE_HEADER,
        TABLE_ROW,
        UNORDERED_LIST,
        ORDERED_LIST,
        BLOCK_QUOTE,
        CODE,
        HORIZONTAL_RULE
    }

    private final String text;
    private final Kind kind;
    private final int level;

    public LegacyMarkdownLine(String text, Kind kind, int level) {
        this.text = text == null ? "" : text;
        this.kind = kind == null ? Kind.PARAGRAPH : kind;
        this.level = Math.max(0, level);
    }

    public String getText() {
        return text;
    }

    public Kind getKind() {
        return kind;
    }

    public int getLevel() {
        return level;
    }
}
