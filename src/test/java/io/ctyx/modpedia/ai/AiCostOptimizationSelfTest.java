package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 成本回归：提示词、首轮工具输出、最终回答和历史工具证据均受预算约束。 */
public final class AiCostOptimizationSelfTest {
    private AiCostOptimizationSelfTest() {
    }

    public static void main(String[] args) {
        testPromptBudget();
        testRequestOutputBudget();
        testHistoricalToolCompaction();
        System.out.println("ModPedia AI cost optimization self-test passed");
    }

    private static void testPromptBudget() {
        String prompt = PromptBuilder.runtime().build(
                io.ctyx.modpedia.search.SearchLanguage.ZH_CN,
                SearchIntensity.STANDARD,
                3,
                8,
                16_000
        );
        check(prompt.length() < 5_000,
                "运行时系统提示词应保持在 5,000 字符以内，实际=" + prompt.length());
        check(prompt.contains("检索阶段只发送结构化工具调用"), "必须禁止检索阶段过程性输出");
        check(prompt.contains("首轮工具参数输出≤" + AiTokenBudget.FIRST_TOOL_CALL),
                "必须声明首轮工具输出预算");
        check(prompt.contains("最终回答≤" + AiTokenBudget.STANDARD_ANSWER), "标准模式必须声明最终回答预算");
    }

    private static void testRequestOutputBudget() {
        check(AiClient.usesCompletionTokenParameter("gpt-5.6-luna"),
                "GPT-5 兼容模型应使用 max_completion_tokens");
        check(!AiClient.usesCompletionTokenParameter("gpt-4o-mini"),
                "旧模型仍应使用 max_tokens");
        ChatRequest original = ChatRequest.builder()
                .messages(UserMessage.from("问题"))
                .toolSpecifications(ToolSpecification.builder().name("search_knowledge").build())
                .build();
        AtomicBoolean first = new AtomicBoolean(true);
        ChatRequest toolRequest = AiToolRouter.requireSearchOnFirstRequest(
                original, first, false, AiTokenBudget.STANDARD_ANSWER
        );
        check(toolRequest.toolChoice() != null
                        && toolRequest.maxOutputTokens() == AiTokenBudget.FIRST_TOOL_CALL,
                "首轮必须强制工具并使用较小输出预算");
        ChatRequest answerRequest = AiToolRouter.requireSearchOnFirstRequest(
                original, first, false, AiTokenBudget.STANDARD_ANSWER
        );
        check(answerRequest.toolChoice() == null
                        && answerRequest.maxOutputTokens() == AiTokenBudget.STANDARD_ANSWER,
                "工具结果后的请求应恢复自动选择并使用回答预算");

        AtomicBoolean gptFirst = new AtomicBoolean(true);
        ChatRequest gptRequest = AiToolRouter.requireSearchOnFirstRequest(
                original, gptFirst, false, AiTokenBudget.STANDARD_ANSWER, true
        );
        check(gptRequest.maxOutputTokens() == null
                        && gptRequest.parameters() instanceof OpenAiChatRequestParameters parameters
                        && parameters.maxCompletionTokens() == AiTokenBudget.REASONING_FIRST_TOOL_CALL,
                "GPT-5 首轮应只发送 max_completion_tokens，不应发送 max_tokens");
    }

    private static void testHistoricalToolCompaction() {
        ToolExecutionRequest oldestCall = ToolExecutionRequest.builder()
                .id("oldest-call").name("search_knowledge").arguments("{}").build();
        ToolExecutionRequest oldCall = ToolExecutionRequest.builder()
                .id("old-call").name("search_knowledge").arguments("{}").build();
        ToolExecutionRequest previousCall = ToolExecutionRequest.builder()
                .id("previous-call").name("search_knowledge").arguments("{}").build();
        ToolExecutionRequest currentCall = ToolExecutionRequest.builder()
                .id("current-call").name("search_knowledge").arguments("{}").build();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system"),
                UserMessage.from("最早问题"),
                AiMessage.from(oldestCall),
                ToolExecutionResultMessage.from(oldestCall, "{\"query\":\"最早问题\",\"results\":[{\"document_id\":\"mod:old\",\"title\":\"旧页面\",\"source_path\":\"assets/mod/old.json\",\"segment_markdown\":\"最早的完整步骤证据\"}] }"),
                AiMessage.from("最早回答"),
                UserMessage.from("旧问题"),
                AiMessage.from(oldCall),
                ToolExecutionResultMessage.from(oldCall, "旧的完整 Markdown 证据"),
                AiMessage.from("旧回答"),
                UserMessage.from("上一问题"),
                AiMessage.from(previousCall),
                ToolExecutionResultMessage.from(previousCall, "上一轮完整 Markdown 证据"),
                AiMessage.from("上一轮回答"),
                UserMessage.from("当前问题"),
                AiMessage.from(currentCall),
                ToolExecutionResultMessage.from(currentCall, "当前完整 Markdown 证据")
        );
        List<ChatMessage> compacted = PersistentChatMemoryStore.compactToolHistory(messages);
        check(compacted.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                        && result.text().contains("旧的完整")),
                "温和压缩不能删除旧工具证据");
        check(compacted.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                        && result.text().contains("上一轮完整")),
                "最近的上一轮工具证据必须完整保留");
        ToolExecutionResultMessage oldest = compacted.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(result -> "oldest-call".equals(result.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("较早工具回合仍必须存在"));
        check(oldest.text().contains("history_compacted")
                        && oldest.text().contains("mod:old")
                        && oldest.text().contains("assets/mod/old.json")
                        && oldest.text().contains("最早的完整步骤证据"),
                "较早工具结果应保留来源 ID、路径和关键正文片段：" + oldest.text());
        check(compacted.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                        && result.text().contains("当前完整")),
                "当前问题的完整工具证据必须保留");
        check(compacted.stream().anyMatch(message -> message instanceof AiMessage ai
                        && ai.text() != null && ai.text().equals("旧回答")),
                "旧回答文本应保留，历史对话仍可读");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
