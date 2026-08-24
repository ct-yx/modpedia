package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 Markdown 行转换为 1.12.2 Font 可绘制的格式化字符串。 */
public final class LegacyMarkdownRenderer {
    private static final Pattern ITEM_TOKEN = Pattern.compile(
            "\\[\\[item:([^\\]|]+)(?:\\|([^\\]|]*))?(?:\\|meta=[^\\]]+)?\\]\\]");
    private static final Pattern RECIPE_TOKEN = Pattern.compile(
            "\\[\\[recipe:([^\\]|]+)(?:\\|([^\\]|]*))?\\]\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_TOKEN = Pattern.compile(
            "\\[\\[source:([^|\\]]+)(?:\\|([^\\]]*))?\\]\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_SOURCE = Pattern.compile(
            "\\[(?:来源|source)\\s*[:：]\\s*([^\\]|]+?)(?:\\s*[|｜]\\s*(?:(?:标注|说明|label|annotation)\\s*[:：]\\s*)?([^\\]]+?))?\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private LegacyMarkdownRenderer() {
    }

    public static List<RenderedLine> layout(String markdown,
            List<LegacySourceReference> sources, boolean showIds) {
        List<RenderedLine> result = new ArrayList<RenderedLine>();
        for (LegacyMarkdownLine line : LegacyMarkdownParser.parse(markdown)) {
            String cleaned = removeCitationMarkup(line.getText());
            List<LegacySourceReference> annotations = sourceReferences(line.getText(), sources);
            String visible = replaceItems(cleaned, showIds);
            visible = replaceRecipes(visible);
            result.add(new RenderedLine(line, formatted(line.getKind(), line.getLevel(), visible),
                    visible, annotations));
        }
        if (result.isEmpty()) {
            result.add(new RenderedLine(new LegacyMarkdownLine(" ", LegacyMarkdownLine.Kind.BLANK, 0),
                    " ", " ", Collections.<LegacySourceReference>emptyList()));
        }
        // 兼容 Worker 只在 sources 字段返回来源、正文没有显式 [[source:...]]
        // 标记的旧会话。来源仍然嵌入回答正文的最后一条有效内容行，不再单独
        // 绘制一个会与输入框或页脚重叠的来源区域。
        if (!hasAnnotations(result) && sources != null && !sources.isEmpty()) {
            int last = result.size() - 1;
            while (last > 0 && result.get(last).getSource().getKind()
                    == LegacyMarkdownLine.Kind.BLANK) {
                last--;
            }
            RenderedLine previous = result.get(last);
            result.set(last, new RenderedLine(previous.getSource(), previous.getFormattedText(),
                    previous.getPlainText(), uniqueSources(sources)));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean hasAnnotations(List<RenderedLine> lines) {
        for (RenderedLine line : lines) {
            if (!line.getAnnotations().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static String visibleText(String markdown, boolean showIds) {
        StringBuilder result = new StringBuilder();
        List<RenderedLine> lines = layout(markdown, Collections.<LegacySourceReference>emptyList(), showIds);
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result.append('\n');
            }
            result.append(lines.get(index).getPlainText());
        }
        return result.toString();
    }

    private static String formatted(LegacyMarkdownLine.Kind kind, int level, String text) {
        String indent = repeat("  ", level);
        String prefix = indent;
        if (kind == LegacyMarkdownLine.Kind.UNORDERED_LIST
                || kind == LegacyMarkdownLine.Kind.ORDERED_LIST) {
            int prefixLength = kind == LegacyMarkdownLine.Kind.UNORDERED_LIST
                    ? Math.min(2, text.length()) : orderedPrefixLength(text);
            prefix += text.substring(0, prefixLength);
            text = text.substring(prefixLength);
        }
        StringBuilder result = new StringBuilder(prefix);
        if (kind == LegacyMarkdownLine.Kind.CODE) {
            result.append("§b").append(text).append("§r");
            return result.toString();
        }
        if (kind == LegacyMarkdownLine.Kind.HEADING
                || kind == LegacyMarkdownLine.Kind.TABLE_HEADER) {
            result.append("§e§l");
        } else if (kind == LegacyMarkdownLine.Kind.BLOCK_QUOTE) {
            result.append("§7§o");
        } else if (kind == LegacyMarkdownLine.Kind.UNORDERED_LIST
                || kind == LegacyMarkdownLine.Kind.ORDERED_LIST) {
            result.append("§b");
        }
        appendInline(result, text);
        result.append("§r");
        return result.toString();
    }

    private static void appendInline(StringBuilder result, String text) {
        for (LegacyMarkdownInlineSpan span : LegacyMarkdownInlineSpan.parse(text)) {
            StringBuilder style = new StringBuilder();
            if (span.isBold()) style.append("§l");
            if (span.isItalic()) style.append("§o");
            if (span.isStrike()) style.append("§m");
            if (span.isCode()) style.append("§6");
            if (span.isLink()) style.append("§b§n");
            result.append(style).append(span.getText());
            if (style.length() > 0) result.append("§r");
        }
    }

    private static String replaceItems(String value, boolean showIds) {
        Matcher matcher = ITEM_TOKEN.matcher(value == null ? "" : value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(0);
            String explicit = matcher.group(2);
            String shown = LegacyItemCatalogSyncService.get().displayName(raw, showIds);
            if (!showIds && explicit != null && !explicit.trim().isEmpty()) {
                shown = explicit.trim();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceRecipes(String value) {
        Matcher matcher = RECIPE_TOKEN.matcher(value == null ? "" : value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String recipeId = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String explicit = matcher.group(2);
            String shown = explicit == null || explicit.trim().isEmpty()
                    ? recipeId : explicit.trim();
            matcher.appendReplacement(result, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String removeCitationMarkup(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String result = SOURCE_TOKEN.matcher(value).replaceAll("");
        result = BRACKET_SOURCE.matcher(result).replaceAll("");
        return result;
    }

    private static List<LegacySourceReference> sourceReferences(String value,
            List<LegacySourceReference> available) {
        if (value == null || available == null || available.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, LegacySourceReference> byId = new LinkedHashMap<String, LegacySourceReference>();
        for (LegacySourceReference source : available) {
            if (source != null && !source.getDocumentId().isEmpty()) {
                byId.put(normalize(source.getDocumentId()), source);
            }
        }
        List<LegacySourceReference> result = new ArrayList<LegacySourceReference>();
        Matcher token = SOURCE_TOKEN.matcher(value);
        while (token.find()) {
            LegacySourceReference source = byId.get(normalize(token.group(1)));
            if (source != null) {
                result.add(withLabel(source, token.group(2)));
            }
        }
        Matcher bracket = BRACKET_SOURCE.matcher(value);
        while (bracket.find()) {
            String rawId = bracket.group(1);
            Matcher link = SOURCE_LINK.matcher(rawId == null ? "" : rawId);
            if (link.matches()) {
                rawId = link.group(2);
            }
            LegacySourceReference source = byId.get(normalize(rawId));
            if (source != null) {
                result.add(withLabel(source, bracket.group(2)));
            }
        }
        Matcher sourceLink = SOURCE_LINK.matcher(value);
        while (sourceLink.find()) {
            LegacySourceReference source = byId.get(normalize(sourceLink.group(2)));
            if (source != null) {
                String label = sourceLink.group(1);
                result.add(withLabel(source, label));
            }
        }
        return unique(result);
    }

    private static LegacySourceReference withLabel(LegacySourceReference source, String label) {
        return label == null || label.trim().isEmpty() ? source
                : new LegacySourceReference(source.getDocumentId(), source.getTitle(),
                        source.getSourcePath(), label.trim());
    }

    private static List<LegacySourceReference> unique(List<LegacySourceReference> values) {
        Map<String, LegacySourceReference> result = new LinkedHashMap<String, LegacySourceReference>();
        for (LegacySourceReference value : values) {
            result.put(normalize(value.getDocumentId()), value);
        }
        return new ArrayList<LegacySourceReference>(result.values());
    }

    private static List<LegacySourceReference> uniqueSources(
            List<LegacySourceReference> values) {
        List<LegacySourceReference> filtered = new ArrayList<LegacySourceReference>();
        if (values != null) {
            for (LegacySourceReference value : values) {
                if (value != null && !value.getDocumentId().trim().isEmpty()) {
                    filtered.add(value);
                }
            }
        }
        return unique(filtered);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("`", "").replace(" ", "");
    }

    private static int orderedPrefixLength(String value) {
        int dot = value.indexOf(". ");
        return dot < 0 ? 0 : dot + 2;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    public static final class RenderedLine {
        private final LegacyMarkdownLine source;
        private final String formattedText;
        private final String plainText;
        private final List<LegacySourceReference> annotations;

        private RenderedLine(LegacyMarkdownLine source, String formattedText,
                String plainText, List<LegacySourceReference> annotations) {
            this.source = source;
            this.formattedText = formattedText;
            this.plainText = plainText;
            this.annotations = Collections.unmodifiableList(
                    new ArrayList<LegacySourceReference>(annotations));
        }

        public LegacyMarkdownLine getSource() { return source; }
        public String getFormattedText() { return formattedText; }
        public String getPlainText() { return plainText; }
        public List<LegacySourceReference> getAnnotations() { return annotations; }
    }

}
