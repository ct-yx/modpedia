package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 不依赖 Minecraft 的轻量 Markdown 块解析器。 */
public final class LegacyMarkdownParser {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern UNORDERED = Pattern.compile("^(\\s*)([-*+])\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");

    private LegacyMarkdownParser() {
    }

    public static List<LegacyMarkdownLine> parse(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] sourceLines = normalized.split("\n", -1);
        List<LegacyMarkdownLine> result = new ArrayList<LegacyMarkdownLine>();
        boolean inFence = false;
        for (int index = 0; index < sourceLines.length; index++) {
            String source = sourceLines[index];
            String trimmed = source.trim();
            if (isFence(trimmed)) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                result.add(new LegacyMarkdownLine(source, LegacyMarkdownLine.Kind.CODE, 0));
                continue;
            }
            if (source.trim().isEmpty()) {
                result.add(new LegacyMarkdownLine(" ", LegacyMarkdownLine.Kind.BLANK, 0));
                continue;
            }

            List<String> headerCells = splitTableCells(source);
            if (headerCells.size() >= 2 && index + 1 < sourceLines.length
                    && isTableSeparator(sourceLines[index + 1], headerCells.size())) {
                result.add(tableLine(headerCells, LegacyMarkdownLine.Kind.TABLE_HEADER));
                index += 2;
                while (index < sourceLines.length) {
                    List<String> rowCells = splitTableCells(sourceLines[index]);
                    if (rowCells.size() < 2 || rowCells.size() > headerCells.size()) {
                        break;
                    }
                    result.add(tableLine(rowCells, LegacyMarkdownLine.Kind.TABLE_ROW));
                    index++;
                }
                index--;
                continue;
            }

            Matcher heading = HEADING.matcher(source);
            if (heading.matches()) {
                result.add(new LegacyMarkdownLine(heading.group(2).trim(),
                        LegacyMarkdownLine.Kind.HEADING, heading.group(1).length()));
                continue;
            }
            if (trimmed.matches("^(\\*{3,}|-{3,}|_{3,})$")) {
                result.add(new LegacyMarkdownLine("────────", LegacyMarkdownLine.Kind.HORIZONTAL_RULE, 0));
                continue;
            }
            Matcher quote = QUOTE.matcher(source);
            if (quote.matches()) {
                result.add(new LegacyMarkdownLine("│ " + quote.group(1),
                        LegacyMarkdownLine.Kind.BLOCK_QUOTE, 0));
                continue;
            }
            Matcher unordered = UNORDERED.matcher(source);
            if (unordered.matches()) {
                result.add(new LegacyMarkdownLine("• " + unordered.group(3),
                        LegacyMarkdownLine.Kind.UNORDERED_LIST, indentation(unordered.group(1))));
                continue;
            }
            Matcher ordered = ORDERED.matcher(source);
            if (ordered.matches()) {
                result.add(new LegacyMarkdownLine(ordered.group(2) + ". " + ordered.group(3),
                        LegacyMarkdownLine.Kind.ORDERED_LIST, indentation(ordered.group(1))));
                continue;
            }
            result.add(new LegacyMarkdownLine(source, LegacyMarkdownLine.Kind.PARAGRAPH, 0));
        }
        if (result.isEmpty()) {
            result.add(new LegacyMarkdownLine(" ", LegacyMarkdownLine.Kind.BLANK, 0));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean isFence(String value) {
        return value.startsWith("```") || value.startsWith("~~~");
    }

    private static boolean isTableSeparator(String source, int columns) {
        List<String> cells = splitTableCells(source);
        if (cells.size() != columns) {
            return false;
        }
        for (String cell : cells) {
            if (!cell.trim().matches(":?-{3,}:?")) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitTableCells(String source) {
        if (source == null || source.indexOf('|') < 0) {
            return Collections.emptyList();
        }
        String value = source.trim();
        if (value.startsWith("|")) {
            value = value.substring(1);
        }
        if (value.endsWith("|") && !value.endsWith("\\|")) {
            value = value.substring(0, value.length() - 1);
        }
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length() && value.charAt(index + 1) == '|') {
                cell.append('|');
                index++;
                continue;
            }
            if (current == '`') {
                code = !code;
            }
            if (current == '|' && !code) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static LegacyMarkdownLine tableLine(List<String> cells, LegacyMarkdownLine.Kind kind) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                value.append("  │  ");
            }
            value.append(cells.get(index));
        }
        return new LegacyMarkdownLine(value.toString(), kind, 0);
    }

    private static int indentation(String value) {
        String spaces = value == null ? "" : value.replace("\t", "    ");
        return Math.min(4, spaces.length() / 2);
    }
}
