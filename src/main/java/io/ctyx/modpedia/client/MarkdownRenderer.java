package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.SourceReference;
import io.ctyx.modpedia.ai.SourceCitationParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将轻量 Markdown 行转换为 Minecraft 的带样式文本并按字体宽度换行。 */
public final class MarkdownRenderer {
    private static final int CODE_COLOR = 0xFFD5E7FF;
    private static final int HEADING_COLOR = 0xFF8ED8FF;
    private static final int QUOTE_COLOR = 0xFFB8C3D3;
    private static final int LINK_COLOR = 0xFF8ED8FF;
    private static final int INLINE_CODE_COLOR = 0xFFFFD58A;

    private MarkdownRenderer() {
    }

    public static List<RenderedLine> layout(String markdown, Font font, int width) {
        return layout(markdown, font, width, List.of(), false);
    }

    /**
     * 布局回答正文，同时把与本轮搜索结果匹配的来源标记绑定到原始 Markdown 行。
     *
     * <p>来源协议不会在这里被绘制成一串原始 ID，而是由 AssistantScreen 在对应行之后
     * 绘制成可点击的短标注。这样来源位置随正文滚动，不会再统一堆在气泡底部。</p>
     */
    public static List<RenderedLine> layout(
            String markdown,
            Font font,
            int width,
            List<SourceReference> availableSources
    ) {
        return layout(markdown, font, width, availableSources, false);
    }

    /** 布局正文；按住 Ctrl 时显示稳定 ID，否则显示当前语言的本地化名称。 */
    public static List<RenderedLine> layout(
            String markdown,
            Font font,
            int width,
            List<SourceReference> availableSources,
            boolean showIds
    ) {
        List<RenderedLine> result = new ArrayList<>();
        int lineWidth = Math.max(1, width);
        List<SourceReference> normalizedSources = uniqueSources(availableSources);
        ItemNameMatcher displayNameMatcher = showIds
                ? ItemNameResolver.displayNameMatcher()
                : ItemNameMatcher.empty();
        boolean hasInlineAnnotations = false;
        for (MarkdownLine line : MarkdownParser.parse(markdown)) {
            List<SourceReference> annotations = sourceAnnotations(line, normalizedSources);
            ItemTokenParser.Parsed itemText = line.kind() == MarkdownLine.Kind.CODE
                    ? new ItemTokenParser.Parsed(line.text(), List.of())
                    : ItemTokenParser.parse(
                            line.text(),
                            showIds,
                            ItemNameResolver::registeredName,
                            displayNameMatcher
                    );
            String displayText = line.kind() == MarkdownLine.Kind.CODE
                    ? line.text()
                    : SourceCitationParser.removeCitationMarkup(itemText.text());
            MarkdownLine displayLine = displayLine(line, displayText);
            Component component = component(displayLine);
            List<FormattedCharSequence> wrapped = font.split(component, lineWidth);
            List<ItemReference> remainingItems = new ArrayList<>(itemText.references());
            if (wrapped.isEmpty()) {
                result.add(new RenderedLine(
                        FormattedCharSequence.EMPTY,
                        displayLine,
                        annotations,
                        List.of()
                ));
            } else {
                for (int index = 0; index < wrapped.size(); index++) {
                    FormattedCharSequence sequence = wrapped.get(index);
                    String wrappedText = plainText(sequence);
                    MarkdownLine renderedLine = displayLine(
                            displayLine,
                            wrappedText
                    );
                    result.add(new RenderedLine(
                            sequence,
                            renderedLine,
                            index + 1 == wrapped.size() ? annotations : List.of(),
                            takeItems(remainingItems, wrappedText)
                    ));
                }
            }
            hasInlineAnnotations |= !annotations.isEmpty();
        }

        // 旧会话和内置文档可能只有 sources 字段，没有把协议标记保存到正文。为了兼容这些
        // 数据，仍把来源放回正文末尾的标注行，而不是恢复旧的“来源区域”整段堆叠。
        if (!hasInlineAnnotations && !normalizedSources.isEmpty() && !result.isEmpty()) {
            int last = result.size() - 1;
            while (last > 0 && result.get(last).source().kind() == MarkdownLine.Kind.BLANK) {
                last--;
            }
            RenderedLine previous = result.get(last);
            result.set(last, previous.withAnnotations(normalizedSources));
        }
        return List.copyOf(result);
    }

    /** 当前 Markdown 行对应的、且确实存在于本轮搜索结果中的来源。 */
    static List<SourceReference> sourceAnnotations(MarkdownLine line, List<SourceReference> availableSources) {
        if (line == null || line.kind() == MarkdownLine.Kind.CODE
                || availableSources == null || availableSources.isEmpty()) {
            return List.of();
        }
        List<SourceCitationParser.Citation> citations = SourceCitationParser.parse(line.text());
        if (citations.isEmpty()) {
            return List.of();
        }
        Map<String, SourceReference> sources = new LinkedHashMap<>();
        for (SourceReference source : availableSources) {
            if (source != null && !source.documentId().isBlank()) {
                sources.putIfAbsent(
                        SourceCitationParser.normalizeDocumentId(source.documentId()),
                        source
                );
            }
        }
        Map<String, SourceReference> resolved = new LinkedHashMap<>();
        for (SourceCitationParser.Citation citation : citations) {
            String key = SourceCitationParser.normalizeDocumentId(citation.documentId());
            SourceReference source = sources.get(key);
            if (source == null) {
                continue;
            }
            SourceReference annotated = citation.annotation().isBlank()
                    ? source
                    : source.withAnnotation(citation.annotation());
            resolved.putIfAbsent(key, annotated);
        }
        return List.copyOf(resolved.values());
    }

    private static List<ItemReference> takeItems(List<ItemReference> remaining, String lineText) {
        if (remaining.isEmpty() || lineText == null || lineText.isBlank()) {
            return List.of();
        }
        List<ItemReference> result = new ArrayList<>();
        for (int index = 0; index < remaining.size();) {
            ItemReference reference = remaining.get(index);
            if (reference.displayText().isBlank() || !lineText.contains(reference.displayText())) {
                index++;
                continue;
            }
            result.add(reference);
            remaining.remove(index);
        }
        return List.copyOf(result);
    }

    private static String plainText(FormattedCharSequence sequence) {
        if (sequence == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        sequence.accept((codePointIndex, style, codePoint) -> {
            result.appendCodePoint(codePoint);
            return true;
        });
        return result.toString();
    }

    private static MarkdownLine displayLine(MarkdownLine source, String displayText) {
        if (displayText == null || displayText.isBlank()) {
            return new MarkdownLine(" ", MarkdownLine.Kind.BLANK, source.level());
        }
        return new MarkdownLine(displayText, source.kind(), source.level());
    }

    private static List<SourceReference> uniqueSources(List<SourceReference> sources) {
        Map<String, SourceReference> unique = new LinkedHashMap<>();
        if (sources == null) {
            return List.of();
        }
        for (SourceReference source : sources) {
            if (source != null && !source.documentId().isBlank()) {
                unique.putIfAbsent(SourceCitationParser.normalizeDocumentId(source.documentId()), source);
            }
        }
        return List.copyOf(unique.values());
    }

    static Component component(MarkdownLine line) {
        if (line.kind() == MarkdownLine.Kind.CODE) {
            return Component.literal(line.text()).withStyle(Style.EMPTY.withColor(CODE_COLOR));
        }
        if (line.kind() == MarkdownLine.Kind.BLANK) {
            return Component.literal(" ");
        }
        if (line.kind() == MarkdownLine.Kind.HORIZONTAL_RULE) {
            return Component.literal(line.text()).withStyle(ChatFormatting.DARK_GRAY);
        }

        Style base = baseStyle(line.kind());
        String text = line.text();
        MutableComponent result = Component.empty();
        if (line.kind() == MarkdownLine.Kind.UNORDERED_LIST) {
            int prefixLength = Math.min(2, text.length());
            result.append(Component.literal("  ".repeat(line.level()) + text.substring(0, prefixLength))
                    .withStyle(base.withColor(ChatFormatting.AQUA)));
            appendInline(result, text.substring(prefixLength), base);
        } else if (line.kind() == MarkdownLine.Kind.ORDERED_LIST) {
            int prefixLength = orderedPrefixLength(text);
            result.append(Component.literal("  ".repeat(line.level()) + text.substring(0, prefixLength))
                    .withStyle(base.withColor(ChatFormatting.AQUA)));
            appendInline(result, text.substring(prefixLength), base);
        } else {
            appendInline(result, text, base);
        }
        return result;
    }

    private static Style baseStyle(MarkdownLine.Kind kind) {
        return switch (kind) {
            case HEADING -> Style.EMPTY.withBold(true).withColor(HEADING_COLOR);
            case TABLE_HEADER -> Style.EMPTY.withBold(true).withColor(HEADING_COLOR);
            case BLOCK_QUOTE -> Style.EMPTY.withColor(QUOTE_COLOR).withItalic(true);
            default -> Style.EMPTY;
        };
    }

    private static void appendInline(MutableComponent target, String text, Style base) {
        for (MarkdownInlineSpan span : MarkdownInlineSpan.parse(text)) {
            Style style = base;
            if (span.bold()) {
                style = style.withBold(true);
            }
            if (span.italic()) {
                style = style.withItalic(true);
            }
            if (span.strikethrough()) {
                style = style.withStrikethrough(true);
            }
            if (span.code()) {
                style = style.withColor(INLINE_CODE_COLOR);
            }
            if (span.link()) {
                style = style.withColor(LINK_COLOR).withUnderlined(true);
            }
            target.append(Component.literal(span.text()).withStyle(style));
        }
    }

    private static int orderedPrefixLength(String text) {
        int dot = text.indexOf(". ");
        return dot < 0 ? 0 : dot + 2;
    }

    public record RenderedLine(
            FormattedCharSequence sequence,
            MarkdownLine source,
            List<SourceReference> annotations,
            List<ItemReference> items
    ) {
        public RenderedLine(FormattedCharSequence sequence, MarkdownLine source) {
            this(sequence, source, List.of(), List.of());
        }

        public RenderedLine(
                FormattedCharSequence sequence,
                MarkdownLine source,
                List<SourceReference> annotations
        ) {
            this(sequence, source, annotations, List.of());
        }

        public RenderedLine {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            items = items == null ? List.of() : List.copyOf(items);
        }

        public RenderedLine withAnnotations(List<SourceReference> value) {
            return new RenderedLine(sequence, source, value, items);
        }
    }
}
