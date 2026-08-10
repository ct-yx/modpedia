package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;

/** 一个纯文本的行内 Markdown 片段，供客户端样式转换和自测试共同使用。 */
record MarkdownInlineSpan(
        String text,
        boolean bold,
        boolean italic,
        boolean code,
        boolean link,
        boolean strikethrough
) {
    static List<MarkdownInlineSpan> parse(String text) {
        List<MarkdownInlineSpan> result = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '\\' && index + 1 < text.length()) {
                plain.append(text.charAt(index + 1));
                index += 2;
                continue;
            }

            Match match = findMatch(text, index);
            if (match == null) {
                plain.append(text.charAt(index++));
                continue;
            }
            flushPlain(result, plain);
            result.add(new MarkdownInlineSpan(
                    match.content(),
                    match.bold(),
                    match.italic(),
                    match.code(),
                    match.link(),
                    match.strikethrough()
            ));
            index = match.end();
        }
        flushPlain(result, plain);
        return result.isEmpty()
                ? List.of(new MarkdownInlineSpan("", false, false, false, false, false))
                : result;
    }

    private static Match findMatch(String text, int start) {
        String[] strongTokens = {"**", "__"};
        for (String token : strongTokens) {
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
        if (text.charAt(start) == (char) 96) {
            int end = text.indexOf((char) 96, start + 1);
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

    private static void flushPlain(List<MarkdownInlineSpan> result, StringBuilder plain) {
        if (!plain.isEmpty()) {
            result.add(new MarkdownInlineSpan(plain.toString(), false, false, false, false, false));
            plain.setLength(0);
        }
    }

    private record Match(
            String content,
            int end,
            boolean bold,
            boolean italic,
            boolean code,
            boolean link,
            boolean strikethrough
    ) {
    }
}
