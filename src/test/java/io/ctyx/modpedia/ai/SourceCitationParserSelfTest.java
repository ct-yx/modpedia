package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import io.ctyx.modpedia.api.SourceReference;

import java.util.ArrayList;
import java.util.List;

/** AI 来源引用格式、标注和旧格式兼容回归测试。 */
public final class SourceCitationParserSelfTest {
    private SourceCitationParserSelfTest() {
    }

    public static void main(String[] args) {
        List<SourceCitationParser.Citation> citations = SourceCitationParser.parse(
                "步骤见 [来源: ae2:guide/controller | 标注: 控制器的搭建条件和启动步骤]，"
                        + "补充见 [来源: ae2:guide/energy]。"
        );
        check(citations.size() == 2, "应解析多个来源引用");
        check("ae2:guide/controller".equals(citations.get(0).documentId()),
                "来源 ID 应完整保留");
        check("控制器的搭建条件和启动步骤".equals(citations.get(0).annotation()),
                "中文 AI 标注应被解析");
        check(citations.get(1).annotation().isBlank(), "旧版无标注格式应保持兼容");

        List<SourceCitationParser.Citation> plainAnnotation = SourceCitationParser.parse(
                "[来源: pneumaticcraft:patchouli_books/book/en_us/entries/tubes/pressure_tubes | "
                        + "这份资料解释了压力管连接和漏气问题]"
        );
        check(plainAnnotation.size() == 1, "不带‘标注’前缀的 AI 来源也应可解析");
        check("pneumaticcraft:patchouli_books/book/en_us/entries/tubes/pressure_tubes"
                        .equals(plainAnnotation.get(0).documentId()),
                "Patchouli 来源 ID 不应丢失下划线和语言路径");
        check("这份资料解释了压力管连接和漏气问题".equals(plainAnnotation.get(0).annotation()),
                "管道符后的普通说明应作为 AI 标注保存");
        String cleaned = SourceCitationParser.removeCitationMarkup(
                "**结论**\n答案正文。\n\n**来源**\n"
                        + "[来源: doc:1 | 这是一条来源说明]"
        );
        check(!cleaned.contains("[来源:"), "正文不应重复显示已转换的来源标记");
        check(!cleaned.contains("来源"), "单独的来源标题应由来源卡片替代");
        check(cleaned.contains("答案正文"), "移除来源标记时应保留回答正文");
        check(SourceCitationParser.removeCitationMarkup("## 来源").isBlank(),
                "来源协议标题应在正文渲染前隐藏，即使该行本身没有 ID");

        List<SourceCitationParser.Citation> english = SourceCitationParser.parse(
                "[source: ae2:guide/energy | annotation: energy prerequisites]"
        );
        check(english.size() == 1, "英文来源格式应可解析");
        check("energy prerequisites".equals(english.get(0).annotation()),
                "英文 AI 标注应被解析");

        List<SourceCitationParser.Citation> protocol = SourceCitationParser.parse(
                "控制器说明 [[source:ae2:guide/controller|搭建条件和启动步骤]]。"
        );
        check(protocol.size() == 1
                        && "ae2:guide/controller".equals(protocol.get(0).documentId())
                        && "搭建条件和启动步骤".equals(protocol.get(0).annotation()),
                "正文来源协议 [[source:...]] 应解析为可点击标注");
        check(!SourceCitationParser.removeCitationMarkup(
                "控制器说明 [[source:ae2:guide/controller|搭建条件]]。"
        ).contains("[[source:"), "正文来源协议应在渲染文本中隐藏");

        List<SourceCitationParser.Citation> markdownLink = SourceCitationParser.parse(
                "参见 [压力容器](ae2:guide/pressure#page=2)。"
        );
        check(markdownLink.size() == 1
                        && "ae2:guide/pressure".equals(markdownLink.get(0).documentId())
                        && "压力容器".equals(markdownLink.get(0).annotation()),
                "直接 Markdown 来源链接应解析文档 ID、锚点和 AI 标注");
        List<SourceCitationParser.Citation> nested = SourceCitationParser.parse(
                "[来源: [压力容器](ae2:guide/pressure) | 标注: 启动条件]"
        );
        check(nested.size() == 1 && "ae2:guide/pressure".equals(nested.get(0).documentId())
                        && "启动条件".equals(nested.get(0).annotation()),
                "带 Markdown 链接的来源格式应保持显式标注优先");

        List<SourceCitationParser.Citation> normalized = SourceCitationParser.parse(
                "[来源： `AE2:GUIDE/ENERGY` | 标注：能源前置条件]"
        );
        check(normalized.size() == 1 && "AE2:GUIDE/ENERGY".equals(normalized.get(0).documentId()),
                "中文全角标点和反引号来源 ID 应可解析");

        SourceReference source = new SourceReference(
                "ae2:guide/controller", "原始标题", "ae2", "guide/controller.md"
        ).withAnnotation("AI 针对当前回答的说明");
        check("AI 针对当前回答的说明".equals(source.displayLabel()),
                "来源卡片应优先显示 AI 标注");
        check("原始标题".equals(new SourceReference(
                "id", "原始标题", "mod", "path"
        ).displayLabel()), "无 AI 标注时应回退到原始标题");

        List<SourceReference> candidates = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            candidates.add(new SourceReference(
                    "doc:" + index,
                    "自动标题 " + index,
                    "mod",
                    "path/" + index
            ));
        }
        SearchTrace trace = new SearchTrace(
                "问题", "zh_cn", "identify", 1, "READY", true, candidates, 1L
        );
        List<SourceReference> selected = AiAssistantSession.selectCitedSources(
                List.of(trace),
                "[来源: DOC:6 | 标注: 第六条] [来源: doc:2 | 标注: 第二条] "
                        + "[来源: doc:1] [来源: doc:3] [来源: doc:4] [来源: doc:5]"
        );
        check(selected.size() == 5, "来源卡片最多应保留五个明确引用");
        check("doc:6".equals(selected.get(0).documentId()), "来源应按 AI 回答中的引用顺序稳定显示");
        check("第六条".equals(selected.get(0).annotation()), "来源应保留 AI 标注");
        check(selected.stream().noneMatch(candidate -> "doc:5".equals(candidate.documentId())),
                "超过上限的候选来源不应自动展示");
        check(AiAssistantSession.selectCitedSources(List.of(trace), "没有来源标记").isEmpty(),
                "没有明确引用时不应回退展示全部候选来源");

        Gson gson = new Gson();
        SourceReference restoredLegacy = gson.fromJson(
                "{\"documentId\":\"legacy:id\",\"title\":\"旧标题\","
                        + "\"sourceMod\":\"mod\",\"sourcePath\":\"legacy.md\"}",
                SourceReference.class
        );
        check(restoredLegacy.annotation().isBlank(), "旧会话缺少 annotation 时应安全回退");
        SourceReference restoredAnnotated = gson.fromJson(gson.toJson(source), SourceReference.class);
        check("AI 针对当前回答的说明".equals(restoredAnnotated.annotation()),
                "AI 标注应可持久化到历史会话");

        System.out.println("ModPedia source citation self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
