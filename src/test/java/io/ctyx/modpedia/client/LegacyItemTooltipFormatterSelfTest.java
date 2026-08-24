package io.ctyx.modpedia.client;

import java.util.Arrays;
import java.util.Collections;

/** 验证旧版 Tooltip 不会把名称重复写入简介，并正确转换为 Markdown。 */
public final class LegacyItemTooltipFormatterSelfTest {
    private LegacyItemTooltipFormatterSelfTest() {
    }

    public static void main(String[] args) {
        check("".equals(LegacyItemTooltipFormatter.toMarkdown(null)), "null tooltip");
        check("".equals(LegacyItemTooltipFormatter.toMarkdown(Collections.singletonList("名称"))),
                "name only");
        String markdown = LegacyItemTooltipFormatter.toMarkdown(Arrays.asList(
                "名称",
                "\u00a7a第一行简介",
                "",
                "第二行简介"
        ));
        check("- 第一行简介\n- 第二行简介".equals(markdown), "markdown conversion: " + markdown);
        System.out.println("LegacyItemTooltipFormatterSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
