package io.ctyx.modpedia.client;


import io.ctyx.modpedia.api.SourceReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

        String tableMarkdown = "| 任务 | 所需物品 |\n"
                + "|---|:---:|\n"
                + "| 将 Craft 放入 Minecraft | 1 个 `minecraft:redstone` |\n";
        List<MarkdownLine> tableLines = MarkdownParser.parse(tableMarkdown).stream()
                .filter(line -> line.kind() != MarkdownLine.Kind.BLANK)
                .toList();
        check(tableLines.size() == 2, "Markdown 表格的分隔行不应作为正文显示");
        check(tableLines.get(0).kind() == MarkdownLine.Kind.TABLE_HEADER,
                "Markdown 表格首行应识别为表头");
        check(tableLines.get(1).kind() == MarkdownLine.Kind.TABLE_ROW,
                "Markdown 表格数据行应识别为表格行");
        check(tableLines.get(0).text().equals("任务  │  所需物品"),
                "表头应移除 Markdown 外框并保留列内容");
        check(tableLines.get(1).text().equals("将 Craft 放入 Minecraft  │  1 个 `minecraft:redstone`"),
                "表格行应移除 Markdown 外框并保留行内格式");
        check(tableLines.stream().noneMatch(line -> line.text().contains("---")),
                "表格对齐分隔线不应泄漏到渲染文本");

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

        SourceReference annotatedSource = new SourceReference(
                "demo:guide/pressure",
                "压力容器",
                "demo",
                "assets/demo/guide/pressure.md"
        );
        MarkdownLine citationLine = new MarkdownLine(
                "结论见 [来源: demo:guide/pressure | 标注: 启动条件和操作步骤]。",
                MarkdownLine.Kind.PARAGRAPH,
                0
        );
        List<SourceReference> annotations = MarkdownRenderer.sourceAnnotations(
                citationLine,
                List.of(annotatedSource)
        );
        check(annotations.size() == 1, "正文中的来源标记应解析为一个来源标注");
        check("启动条件和操作步骤".equals(annotations.get(0).displayLabel()),
                "正文来源标注应保留模型写的用途说明");
        List<SourceReference> protocolAnnotations = MarkdownRenderer.sourceAnnotations(
                new MarkdownLine(
                        "结论见 [[source:demo:guide/pressure|启动条件和操作步骤]]。",
                        MarkdownLine.Kind.PARAGRAPH,
                        0
                ),
                List.of(annotatedSource)
        );
        check(protocolAnnotations.size() == 1
                        && "启动条件和操作步骤".equals(protocolAnnotations.get(0).displayLabel()),
                "[[source:...]] 协议也应绑定到正文行的手册跳转按钮");
        check(MarkdownRenderer.sourceAnnotations(
                new MarkdownLine("[来源: other:unsearched]", MarkdownLine.Kind.PARAGRAPH, 0),
                List.of(annotatedSource)
        ).isEmpty(), "未出现在本轮搜索结果中的 ID 不得生成跳转按钮");

        ItemTokenParser.Parsed rawItem = ItemTokenParser.parse(
                "需要 ae2:controller 和 [[item:minecraft:iron_ingot|错误显示名]]。",
                false,
                id -> Optional.ofNullable(Map.of(
                        "ae2:controller", "ME 控制器",
                        "minecraft:iron_ingot", "铁锭"
                ).get(id))
        );
        check(rawItem.text().equals("需要 ME 控制器 和 铁锭。"),
                "回答中的裸物品 ID 和协议物品 ID 都应转换为本地化名称");
        check(rawItem.references().size() == 2
                        && rawItem.references().get(0).id().equals("ae2:controller"),
                "物品显示文本必须保留原始 ID 供配方跳转");
        ItemTokenParser.Parsed visibleIds = ItemTokenParser.parse(
                "需要 ae2:controller 和 [[item:minecraft:iron_ingot|铁锭]]。",
                true,
                id -> Optional.ofNullable(Map.of(
                        "ae2:controller", "ME 控制器",
                        "minecraft:iron_ingot", "铁锭"
                ).get(id))
        );
        check(visibleIds.text().equals("需要 ae2:controller 和 minecraft:iron_ingot。"),
                "Ctrl 模式应显示原始物品 ID");
        ItemTokenParser.Parsed translationKey = ItemTokenParser.parse(
                "模型错误输出 item.minecraft.iron_ingot、block.minecraft.stone 和 铁锭。",
                true,
                id -> Optional.ofNullable(Map.of(
                        "minecraft:iron_ingot", "铁锭",
                        "minecraft:stone", "石头"
                ).get(id)),
                Map.of("铁锭", "minecraft:iron_ingot", "石头", "minecraft:stone")
        );
        check(translationKey.text().equals(
                        "模型错误输出 minecraft:iron_ingot、minecraft:stone 和 minecraft:iron_ingot。"),
                "Cmd 模式应把物品翻译键转换为完整物品 ID");
        check(translationKey.references().stream().anyMatch(reference ->
                        reference.id().equals("minecraft:iron_ingot")),
                "翻译键和本地化名称转换后仍应保留物品引用");
        ItemTokenParser.Parsed trieMatched = ItemTokenParser.parse(
                "请检查 ME 控制器 和 铁锭。",
                true,
                id -> Optional.ofNullable(Map.of(
                        "ae2:controller", "ME 控制器",
                        "minecraft:iron_ingot", "铁锭"
                ).get(id)),
                ItemNameMatcher.from(Map.of(
                        "ME 控制器", "ae2:controller",
                        "铁锭", "minecraft:iron_ingot",
                        "弓", "minecraft:bow"
                ))
        );
        check(trieMatched.text().equals("请检查 ae2:controller 和 minecraft:iron_ingot。"),
                "Cmd 模式应使用一次构建的 Trie 匹配本地化名称");
        ItemTokenParser.Parsed singleCharacter = ItemTokenParser.parse(
                "弓可以远程攻击。",
                true,
                id -> Optional.of("minecraft:bow".equals(id) ? "弓" : ""),
                ItemNameMatcher.from(Map.of("弓", "minecraft:bow"))
        );
        check(singleCharacter.text().equals("minecraft:bow可以远程攻击。"),
                "单字中文物品名也应支持 Ctrl 模式 ID 显示");
        ItemTokenParser.Parsed unknown = ItemTokenParser.parse(
                "普通路径 example:unknown/path 不应被改写。",
                false,
                id -> Optional.empty()
        );
        check(unknown.text().equals("普通路径 example:unknown/path 不应被改写。"),
                "未注册的 namespace:path 不得误改为物品名称");

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
