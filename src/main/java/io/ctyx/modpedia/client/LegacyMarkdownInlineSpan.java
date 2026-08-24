package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;

/** 行内 Markdown 片段。 */
public final class LegacyMarkdownInlineSpan {
    private final String text;
    private final boolean bold;
    private final boolean italic;
    private final boolean code;
    private final boolean link;
    private final boolean strike;

    public LegacyMarkdownInlineSpan(String text, boolean bold, boolean italic,
            boolean code, boolean link, boolean strike) {
        this.text = text == null ? "" : text;
        this.bold = bold;
        this.italic = italic;
        this.code = code;
        this.link = link;
        this.strike = strike;
    }

    public String getText() { return text; }
    public boolean isBold() { return bold; }
    public boolean isItalic() { return italic; }
    public boolean isCode() { return code; }
    public boolean isLink() { return link; }
    public boolean isStrike() { return strike; }

    public static List<LegacyMarkdownInlineSpan> parse(String value) {
        String text = value == null ? "" : value;
        List<LegacyMarkdownInlineSpan> result = new ArrayList<LegacyMarkdownInlineSpan>();
        StringBuilder plain = new StringBuilder();
        for (int index = 0; index < text.length();) {
            if (text.charAt(index) == '\\' && index + 1 < text.length()) {
                plain.append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            Match match = match(text, index);
            if (match == null) {
                plain.append(text.charAt(index++));
                continue;
            }
            flush(result, plain);
            result.add(new LegacyMarkdownInlineSpan(match.text, match.bold, match.italic,
                    match.code, match.link, match.strike));
            index = match.end;
        }
        flush(result, plain);
        if (result.isEmpty()) {
            result.add(new LegacyMarkdownInlineSpan("", false, false, false, false, false));
        }
        return result;
    }

    private static Match match(String text, int start) {
        String[] strong = {"**", "__"};
        for (String token : strong) {
            if (text.startsWith(token, start)) {
                int end = text.indexOf(token, start + token.length());
                if (end > start + token.length()) {
                    return new Match(text.substring(start + token.length(), end), end + token.length(),
                            true, false, false, false, false);
                }
            }
        }
        if (text.startsWith("~~", start)) {
            int end = text.indexOf("~~", start + 2);
            if (end > start + 2) {
                return new Match(text.substring(start + 2, end), end + 2,
                        false, false, false, false, true);
            }
        }
        if (text.charAt(start) == '`') {
            int end = text.indexOf('`', start + 1);
            if (end > start + 1) {
                return new Match(text.substring(start + 1, end), end + 1,
                        false, false, true, false, false);
            }
        }
        if (text.charAt(start) == '[') {
            int close = text.indexOf("](", start + 1);
            if (close > start + 1) {
                int end = text.indexOf(')', close + 2);
                if (end > close + 2) {
                    return new Match(text.substring(start + 1, close), end + 1,
                            false, false, false, true, false);
                }
            }
        }
        if (text.charAt(start) == '*' || text.charAt(start) == '_') {
            char token = text.charAt(start);
            int end = text.indexOf(token, start + 1);
            if (end > start + 1 && !Character.isWhitespace(text.charAt(start + 1))) {
                return new Match(text.substring(start + 1, end), end + 1,
                        false, true, false, false, false);
            }
        }
        return null;
    }

    private static void flush(List<LegacyMarkdownInlineSpan> result, StringBuilder value) {
        if (value.length() > 0) {
            result.add(new LegacyMarkdownInlineSpan(value.toString(), false, false,
                    false, false, false));
            value.setLength(0);
        }
    }

    private static final class Match {
        private final String text;
        private final int end;
        private final boolean bold;
        private final boolean italic;
        private final boolean code;
        private final boolean link;
        private final boolean strike;

        private Match(String text, int end, boolean bold, boolean italic, boolean code,
                boolean link, boolean strike) {
            this.text = text;
            this.end = end;
            this.bold = bold;
            this.italic = italic;
            this.code = code;
            this.link = link;
            this.strike = strike;
        }
    }
}
