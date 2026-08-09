package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.search.SearchLanguage;

/** 提示词中的补搜、双语、预算和来源约束回归测试。 */
public final class PromptBuilderSelfTest {
    private PromptBuilderSelfTest() {
    }

    public static void main(String[] args) {
        PromptBuilder builder = new PromptBuilder(
                "系统规则",
                "回答要简洁，并列出来源。"
        );
        String prompt = builder.build(SearchLanguage.EN_US, SearchIntensity.STANDARD, 3, 8, 16_000);
        check(prompt.contains("search_knowledge"), "提示词应声明搜索工具");
        check(prompt.contains("改写 query") || prompt.contains("rewrite"), "提示词应要求证据不足时补搜");
        check(prompt.contains("中文") && prompt.contains("英文"), "提示词应声明中英文交叉检索");
        check(prompt.contains("[来源: document_id]"), "提示词应固定来源格式");
        check(prompt.contains("16000"), "提示词应包含上下文预算");
        System.out.println("ModPedia prompt builder self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
