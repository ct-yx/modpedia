package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 回归测试：完整 Markdown 工具结果必须在补搜请求中继续可见。 */
public final class AiContextWindowSelfTest {
    private AiContextWindowSelfTest() {
    }

    public static void main(String[] args) {
        InMemoryStore store = new InMemoryStore();
        TokenWindowChatMemory memory = TokenWindowChatMemory.builder()
                .id("conversation")
                .maxTokens(AiAssistantSession.memoryTokenBudget(16_000), new ApproximateEstimator())
                .chatMemoryStore(store)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.add(SystemMessage.from("你是模组知识助手，请依据搜索结果回答。"));
        memory.add(UserMessage.from("me成型面板的用法？"));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("search_knowledge")
                .arguments("{\"query\":\"成型面板\"}")
                .build();
        memory.add(AiMessage.from(request));
        String evidence = "# Formation Plane\n\n" + "成型面板用于把网络中的物品投射到世界中。\n".repeat(600);
        memory.add(ToolExecutionResultMessage.from(request, evidence));

        List<ChatMessage> messages = memory.messages();
        check(messages.stream().anyMatch(ToolExecutionResultMessage.class::isInstance),
                "完整搜索结果不能因字符/token换算错误被移出上下文");
        check(messages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .anyMatch(result -> result.text().contains("成型面板用于")),
                "模型补搜请求必须仍能读取搜索证据");
        List<ChatMessage> nextRequest = UserMessage.replaceLast(
                new ArrayList<>(messages),
                UserMessage.from("me成型面板的用法？")
        );
        check(nextRequest.stream().anyMatch(ToolExecutionResultMessage.class::isInstance),
                "LangChain4j 替换原始用户消息后仍应保留工具结果");
        check(AiAssistantSession.memoryTokenBudget(16_000) >= 16_000,
                "16,000 字符搜索预算应至少对应 16,000 token 的保守窗口");
        check(AiAssistantSession.toolCallingRoundTrips(1) == 4,
                "快速档位必须容纳预算外工具尝试后再生成最终回答");
        check(AiAssistantSession.toolCallingRoundTrips(3) == 6,
                "三轮搜索预算应为偶发的额外工具尝试保留整理回答的往返");
        System.out.println("ModPedia AI context window self-test passed");
    }

    private static final class ApproximateEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInText(String text) {
            return Math.max(1, text == null ? 0 : text.codePointCount(0, text.length()) / 2);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return estimateTokenCountInText(message == null ? "" : message.toString());
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 3;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }

    private static final class InMemoryStore implements ChatMemoryStore {
        private final Map<Object, List<ChatMessage>> messages = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return messages.getOrDefault(memoryId, List.of());
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> next) {
            messages.put(memoryId, new ArrayList<>(next));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messages.remove(memoryId);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
