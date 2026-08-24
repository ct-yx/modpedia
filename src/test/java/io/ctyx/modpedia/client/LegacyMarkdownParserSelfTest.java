package io.ctyx.modpedia.client;

import java.util.List;

/** Markdown 块、行内样式、来源和物品令牌回归。 */
public final class LegacyMarkdownParserSelfTest {
    private LegacyMarkdownParserSelfTest() {
    }

    public static void main(String[] args) {
        String markdown = "# 标题\n\n- **列表**\n\n> 引用\n\n```\ncode\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n[[item:minecraft:stone|石头]] [[recipe:tconstruct:tools/pattern|tconstruct:tools/pattern]] [[source:doc-1|手册]]";
        List<LegacyMarkdownLine> lines = LegacyMarkdownParser.parse(markdown);
        check(lines.size() >= 8, "Markdown 块数量不足");
        check(lines.get(0).getKind() == LegacyMarkdownLine.Kind.HEADING, "标题未识别");
        check(LegacyMarkdownRenderer.visibleText(markdown, false).contains("列表"),
                "粗体文本丢失");
        check(LegacyMarkdownRenderer.visibleText(markdown, false).contains("stone")
                || LegacyMarkdownRenderer.visibleText(markdown, false).contains("石头"),
                "物品令牌未转换");
        LegacySourceReference source = new LegacySourceReference("doc-1", "手册", "", "手册");
        List<LegacyMarkdownRenderer.RenderedLine> rendered = LegacyMarkdownRenderer.layout(
                "正文 [[source:doc-1|手册]]", java.util.Collections.singletonList(source), false);
        check(!rendered.get(0).getPlainText().contains("[[source:"), "来源协议泄漏到正文");
        String recipe = LegacyMarkdownRenderer.visibleText(
                "[[recipe:tconstruct:tools/pattern|tconstruct:tools/pattern]]", false);
        check("tconstruct:tools/pattern".equals(recipe), "配方协议应显示为配方 ID");
        check(!recipe.contains("[[recipe:"), "配方协议不能泄漏到正文");
        check(rendered.get(0).getAnnotations().size() == 1, "来源标注未绑定");
        List<LegacyMarkdownRenderer.RenderedLine> legacySources = LegacyMarkdownRenderer.layout(
                "只有 sources 字段的旧回答", java.util.Collections.singletonList(source), false);
        check(!legacySources.get(0).getAnnotations().isEmpty(),
                "没有正文来源协议时应把来源嵌入最后一行");
        List<LegacyMarkdownRenderer.RenderedLine> linkedSource = LegacyMarkdownRenderer.layout(
                "依据 [手册](doc-1)", java.util.Collections.singletonList(source), false);
        check(linkedSource.get(0).getAnnotations().size() == 1,
                "Markdown 来源链接未绑定");
        System.out.println("LegacyMarkdownParserSelfTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
