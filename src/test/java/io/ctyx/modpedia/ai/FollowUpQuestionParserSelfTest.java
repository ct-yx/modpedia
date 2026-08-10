package io.ctyx.modpedia.ai;

import java.util.List;

/** 后续问题协议解析和正文清理的纯 Java 回归测试。 */
public final class FollowUpQuestionParserSelfTest {
    private FollowUpQuestionParserSelfTest() {
    }

    public static void main(String[] args) {
        FollowUpQuestionParser.Parsed tagged = FollowUpQuestionParser.parse(
                "## 结论\n先检查压力。\n\n"
                        + "<modpedia_follow_up_questions>\n"
                        + "- 压力范围是多少？\n"
                        + "- 需要哪些前置条件？\n"
                        + "- 如何排查漏气？\n"
                        + "- 不应显示这一项\n"
                        + "</modpedia_follow_up_questions>"
        );
        check("## 结论\n先检查压力。".equals(tagged.markdown()), "协议块不应出现在玩家正文中");
        check(tagged.questions().equals(List.of("压力范围是多少？", "需要哪些前置条件？", "如何排查漏气？")),
                "标签协议应提取并限制为三个问题");

        FollowUpQuestionParser.Parsed heading = FollowUpQuestionParser.parse(
                "答案\n\n## 你可能还会问\n1. 需要什么材料？\n2. 如何搭建？\n"
        );
        check("答案".equals(heading.markdown()), "兼容标题协议后应清理标题和列表");
        check(heading.questions().size() == 2, "应兼容中文标题形式");

        FollowUpQuestionParser.Parsed unchanged = FollowUpQuestionParser.parse("普通回答");
        check(unchanged.questions().isEmpty() && "普通回答".equals(unchanged.markdown()),
                "没有协议时正文和问题列表都应保持稳定");
        System.out.println("ModPedia follow-up question parser self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
