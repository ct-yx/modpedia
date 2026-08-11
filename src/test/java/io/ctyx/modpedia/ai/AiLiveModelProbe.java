package io.ctyx.modpedia.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用当前设置对当前模型执行一次真实 LangChain4j + 本地搜索工具冒烟测试。 */
public final class AiLiveModelProbe {
    private AiLiveModelProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path settingsPath = Path.of(System.getProperty(
                "modpedia.aiSettings",
                "run/config/modpedia/ai.json"
        ));
        AiSettings settings = new AiSettingsStore(settingsPath).load();
        if (!settings.configured() || settings.effectiveApiKey().isBlank()) {
            throw new IllegalStateException("当前配置没有可用的 API 地址、模型名称或 API Key。");
        }

        Path conversationRoot = Files.createTempDirectory("modpedia-ai-live-probe-");
        try {
            ConversationStore conversations = new ConversationStore(conversationRoot);
            RetrievalService retrieval = new RetrievalService(Path.of(
                    System.getProperty("modpedia.knowledgeRoot", "run/config/modpedia/knowledge")
            ));
            List<SearchTrace> traces = new ArrayList<>();
            SearchKnowledgeTool tool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    4,
                    8_000,
                    1,
                    1,
                    traces::add
            );
            OpenAiChatModel model = AiClient.blockingModel(settings);
            AtomicBoolean firstRequest = new AtomicBoolean(true);
            ProbeService service = AiServices.builder(ProbeService.class)
                    .chatModel(model)
                    .systemMessage(PromptBuilder.runtime().build(
                            SearchLanguage.ZH_CN,
                            SearchIntensity.FAST,
                            1,
                            4,
                            8_000
                    ))
                    .chatMemoryProvider(id -> TokenWindowChatMemory.builder()
                            .id(String.valueOf(id))
                            .maxTokens(AiAssistantSession.memoryTokenBudget(8_000), new ApproximateEstimator())
                            .chatMemoryStore(new PersistentChatMemoryStore(conversations))
                            .alwaysKeepSystemMessageFirst(true)
                            .build())
                    .tools(tool)
                    .chatRequestTransformer(request -> AiAssistantSession.requireSearchOnFirstRequest(
                            request, firstRequest
                    ))
                    .maxToolCallingRoundTrips(AiAssistantSession.toolCallingRoundTrips(1))
                    .build();
            String answer;
            try {
                String question = System.getProperty(
                        "modpedia.aiProbeQuestion",
                        "请先调用 search_knowledge 查询 modpedia:guide/assistant-usage，然后用一句话说明第一步。"
                );
                answer = service.chat(
                        conversations.activeId(),
                        question
                );
            } catch (Throwable failure) {
                throw failure;
            }
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("模型返回了空回答。");
            }
            if (traces.isEmpty()) {
                throw new IllegalStateException("真实模型没有调用本地 search_knowledge 工具。");
            }
            System.out.println("ModPedia live AI probe passed: model="
                    + settings.model()
                    + ", searchCalls=" + traces.size()
                    + ", answerChars=" + answer.length());
        } finally {
            try (var paths = Files.walk(conversationRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    interface ProbeService {
        String chat(@MemoryId String id, @UserMessage String prompt);
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

}
