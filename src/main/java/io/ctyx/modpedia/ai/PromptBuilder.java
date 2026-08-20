package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.search.SearchLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 系统提示词构造器；静态规则来自资源文件，运行参数由会话动态补充。 */
public final class PromptBuilder {
    private final String systemTemplate;
    private final String answerFormat;

    public PromptBuilder(String systemTemplate, String answerFormat) {
        this.systemTemplate = systemTemplate == null ? "" : systemTemplate.strip();
        this.answerFormat = answerFormat == null ? "" : answerFormat.strip();
    }

    public static PromptBuilder runtime() {
        return new PromptBuilder(
                readResource("/assets/modpedia/prompts/system.md"),
                readResource("/assets/modpedia/prompts/answer-format.md")
        );
    }

    public String build(SearchLanguage language, SearchIntensity intensity, int rounds, int results, int contextChars) {
        SearchLanguage actual = language == null || language == SearchLanguage.AUTO
                ? SearchLanguage.ZH_CN
                : language;
        String languageInstruction = actual == SearchLanguage.EN_US
                ? "Use the player's language. Search the other language when an ID or term requires it."
                : "使用玩家提问的语言；出现英文 ID 或术语时也搜索英文资料。";
        StringBuilder prompt = new StringBuilder(systemTemplate);
        // 测试夹具和第三方调用方可以传入自定义 systemTemplate；只有自定义模板没有
        // 内置协议时补一份短协议，运行时资源自身已经包含完整协议，不会重复发送。
        if (!systemTemplate.contains("search_knowledge")) {
            prompt.append("\n\n## 工具协议\n")
                    .append("模组事实调用 search_knowledge，Wiki 调用 search_wiki，任务调用 search_tasks；")
                    .append("复杂算术调用 calculate，配方调用 query_item_recipes。检索阶段只发送工具调用，")
                    .append("不要输出思考过程；证据不足时改写 query 继续搜索。不要要求玩家知道或输入内部 ID；")
                    .append("首次或语言不确定时 search_knowledge 的 language 必须为 auto，不要固定当前语言；")
                    .append("使用玩家语言，交叉搜索中文和英文。只引用本轮 3 到 5 个来源，格式为 ")
                    .append("[来源: document_id | 标注: 支持的内容]。复杂计算不要依靠心算。")
                    .append("未指定其他模组时，‘如何开始使用这个模组’指向 ModPedia 的 assistant-usage。");
        }
        if (!answerFormat.isBlank()) {
            prompt.append("\n\n").append(answerFormat);
        }
        prompt.append("\n\n## 本次预算\n")
                .append(languageInstruction)
                .append("\n搜索轮数≤").append(rounds)
                .append("；每轮结果≤").append(results)
                .append("；证据字符≤").append(contextChars)
                .append("；普通模型首轮工具参数输出≤").append(AiTokenBudget.FIRST_TOOL_CALL)
                .append("，GPT-5/o 首轮≤").append(AiTokenBudget.REASONING_FIRST_TOOL_CALL)
                .append(" tokens；最终回答≤").append(AiTokenBudget.answerTokens(intensity))
                .append(" tokens。检索阶段保持静默，资料足够后直接回答。");
        return prompt.toString();
    }

    private static String readResource(String path) {
        try (InputStream stream = PromptBuilder.class.getResourceAsStream(path)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }
}
