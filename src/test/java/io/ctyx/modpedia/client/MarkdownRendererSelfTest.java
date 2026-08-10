package io.ctyx.modpedia.client;

import java.util.List;

/** Markdown 消息解析和样式转换回归测试，不启动 Minecraft 渲染窗口。 */
public final class MarkdownRendererSelfTest {
    private MarkdownRendererSelfTest() {
    }

    public static void main(String[] args) {
        String inlineCode = String.valueOf((char) 96);
        String fence = inlineCode.repeat(3);
        String markdown = "# 压力容器\n\n"
                + "这是 **重要** 的 " + inlineCode + "机器" + inlineCode + "。\n"
                + "- 接入能源\n"
                + "- 检查 *阀门*\n"
                + "1. 启动控制器\n\n"
                + fence + "text\n"
                + "pressure = ready\n"
                + fence + "\n\n"
                + "> [来源](https://example.invalid/manual)\n";

        List<MarkdownLine> lines = MarkdownParser.parse(markdown);
        check(lines.get(0).kind() == MarkdownLine.Kind.HEADING, "标题应识别为标题行");
        check(lines.get(0).text().equals("压力容器"), "标题标记不应显示在正文中");
        check(lines.stream().anyMatch(line -> line.kind() == MarkdownLine.Kind.UNORDERED_LIST
                        && line.text().contains("接入能源")),
                "无序列表应保留列表内容");
        check(lines.stream().anyMatch(line -> line.kind() == MarkdownLine.Kind.ORDERED_LIST
                        && line.text().startsWith("1. ")),
                "有序列表应保留编号");
        check(lines.stream().anyMatch(line -> line.kind() == MarkdownLine.Kind.CODE
                        && line.text().equals("pressure = ready")),
                "代码块应保留代码行");
        check(lines.stream().noneMatch(line -> line.text().contains(fence)),
                "代码围栏不应作为正文显示");

        List<MarkdownInlineSpan> inline = MarkdownInlineSpan.parse(
                "这是 **重要**、*强调*、" + inlineCode + "id" + inlineCode
                        + " 和 [手册](https://example.invalid/manual)。");
        check(inline.stream().map(MarkdownInlineSpan::text).reduce("", String::concat)
                        .equals("这是 重要、强调、id 和 手册。"),
                "行内 Markdown 标记应转换为可读文本");
        check(inline.stream().anyMatch(MarkdownInlineSpan::bold),
                "粗体片段应保留 Minecraft 粗体样式");
        check(inline.stream().anyMatch(MarkdownInlineSpan::italic),
                "斜体片段应保留 Minecraft 斜体样式");
        check(inline.stream().anyMatch(MarkdownInlineSpan::link),
                "链接片段应保留下划线样式");

        String longText = "这是一段用于检查布局输入的很长文本，".repeat(20);
        check(MarkdownParser.parse(longText).size() == 1,
                "长文本应作为一个 Markdown 段落交给 Font 进行宽度换行");

        System.out.println("ModPedia Markdown renderer self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
