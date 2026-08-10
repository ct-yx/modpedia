package io.ctyx.modpedia.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** search_knowledge 工具参数、来源去重、has_more 和上下文限制回归测试。 */
public final class SearchKnowledgeToolSelfTest {
    private SearchKnowledgeToolSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-ai-search-tool-");
        Path conversationsRoot = Files.createTempDirectory("modpedia-ai-conversations-");
        try {
            KnowledgeDatabase.sync(root, List.of(
                    input("ae2:pressure", "压力容器", "压力容器需要能量和密封材料。\n\n启动步骤：先安装控制器，再连接能源。"),
                    input("ae2:controller", "控制器", "控制器是压力容器的前置机器。\n\n控制器启动步骤和配方说明。"),
                    input("modpedia:generic", "搜索提示", "设置、前置条件和步骤是通用的搜索提示。"),
                    input("pneumaticcraft:pressure_tubes", "压力管道", "压力管道用于连接机器并防止漏气。"),
                    input("modpedia:pressure-note", "压力提示", "压力是一个通用概念。", "zh_cn", List.of("pressure")),
                    input("pneumaticcraft:pressure-tubes-en", "Pressure Tubes",
                            "Pressure tubes connect machines and prevent leaks.", "en_us",
                            List.of("pressure tubes", "pressure"))
            ), true);
            RetrievalService retrieval = new RetrievalService(root);

            SearchKnowledgeTool relevanceTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    20,
                    4_000,
                    1,
                    ignored -> { }
            );
            JsonObject relevance = parse(relevanceTool.search(
                    "压力管道 如何连接 设置 防止漏气 前置条件 操作步骤",
                    "zh_cn",
                    8,
                    "连接 设置 防止漏气 前置条件 操作步骤",
                    List.of()
            ));
            check(relevance.get("focus").getAsString().equals("steps"), "中文自然语言 focus 应归一化为 steps");
            check(relevance.get("results").getAsJsonArray().size() >= 1, "实体查询应有结果");
            check(relevance.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString().equals("pneumaticcraft:pressure_tubes"),
                    "实体命中应优先于通用的设置、前置条件和步骤页面");

            JsonObject bilingual = parse(relevanceTool.search(
                    "pressure tubes",
                    "auto",
                    8,
                    "identify",
                    List.of()
            ));
            check(bilingual.get("language").getAsString().equals("auto"), "auto 语言应保留为 auto");
            check(bilingual.get("results").getAsJsonArray().size() >= 1, "auto 查询应返回结果");
            check(bilingual.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString().equals("pneumaticcraft:pressure-tubes-en"),
                    "中文主语言存在低相关结果时仍应合并并优先返回英文高相关结果");

            JsonObject irrelevant = parse(relevanceTool.search(
                    "完全不存在的物品如何使用",
                    "auto",
                    8,
                    "steps",
                    List.of()
            ));
            check(irrelevant.get("returned_count").getAsInt() == 0,
                    "明确实体没有命中时不能返回无关的通用页面");

            List<SearchTrace> traces = new ArrayList<>();
            SearchKnowledgeTool tool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    20,
                    4_000,
                    1,
                    traces::add
            );

            JsonObject first = parse(tool.search("压力容器", "zh_cn", 1, "identify", List.of()));
            check(first.get("status").getAsString().equals("READY"), "精确查询应返回 READY");
            check(first.get("returned_count").getAsInt() == 1, "limit=1 应只返回一条");
            check(first.get("new_source_count").getAsInt() == 1, "工具应报告本轮新增来源数量");
            check(first.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("segment_markdown").getAsString().contains("压力容器"),
                    "工具应返回完整 Markdown 段落");
            check(first.get("has_more").getAsBoolean(), "存在第二篇候选时应报告 has_more");

            String firstId = first.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString();
            JsonObject second = parse(tool.search(
                    "控制器",
                    "zh_cn",
                    8,
                    "需要哪些前置条件",
                    List.of(firstId.toUpperCase(Locale.ROOT))
            ));
            check(second.get("focus").getAsString().equals("prerequisite"), "前置条件 focus 应归一化为 prerequisite");
            check(second.get("returned_count").getAsInt() >= 1, "改写查询并排除已读文档后应继续返回新来源");
            check(!second.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString().equals(firstId), "补搜不应重复第一篇来源");

            JsonObject repeated = parse(tool.search("压力容器", "zh_cn", 8, "prerequisite", List.of()));
            check(repeated.get("hint").getAsString().contains("重复查询"), "相同查询应提示模型改写");
            check(traces.size() == 3, "每次工具调用都应保存搜索轨迹");
            testIncompleteToolTurnCleanup();
            testPersistentContextRepair(conversationsRoot);
            System.out.println("ModPedia search knowledge tool self-test passed");
        } finally {
            deleteTree(root);
            deleteTree(conversationsRoot);
        }
    }

    private static KnowledgeDatabase.DocumentInput input(String id, String title, String body) {
        return input(id, title, body, "zh_cn", List.of(title, id));
    }

    private static KnowledgeDatabase.DocumentInput input(
            String id,
            String title,
            String body,
            String language,
            List<String> keywords
    ) {
        String sourceMod = id.substring(0, id.indexOf(':'));
        KnowledgeDocument document = new KnowledgeDocument(
                id,
                sourceMod,
                "manual_markdown",
                title,
                "guide",
                keywords,
                "19.2.17",
                "assets/" + sourceMod + "/guides/" + id.substring(id.indexOf(':') + 1) + ".md",
                "# " + title + "\n\n" + body
        );
        return new KnowledgeDatabase.DocumentInput(
                "generated:" + id,
                id + "-fingerprint",
                "generated/" + id + ".md",
                language,
                10,
                document
        );
    }

    private static JsonObject parse(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static void testIncompleteToolTurnCleanup() {
        ToolExecutionRequest completeRequest = ToolExecutionRequest.builder()
                .id("complete-call")
                .name("search_knowledge")
                .arguments("{}")
                .build();
        ToolExecutionRequest brokenRequest = ToolExecutionRequest.builder()
                .id("broken-call")
                .name("search_knowledge")
                .arguments("{}")
                .build();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system"),
                UserMessage.from("first question"),
                AiMessage.from(completeRequest),
                ToolExecutionResultMessage.from(completeRequest, "{}"),
                AiMessage.from("first answer"),
                AiMessage.from(brokenRequest),
                UserMessage.from("retry question")
        );
        List<ChatMessage> sanitized = PersistentChatMemoryStore.removeIncompleteToolTurn(messages);
        check(sanitized.size() == 5, "未完成工具调用及其后续消息应整体截断");
        check(sanitized.get(4) instanceof AiMessage
                        && ((AiMessage) sanitized.get(4)).text().equals("first answer"),
                "完整工具调用和最终回答应保留");
    }

    private static void testPersistentContextRepair(Path root) {
        ConversationStore conversations = new ConversationStore(root);
        String id = conversations.activeId();
        ToolExecutionRequest completeRequest = ToolExecutionRequest.builder()
                .id("fc_call-complete")
                .name("search_knowledge")
                .arguments("{}")
                .build();
        ToolExecutionRequest incompleteRequest = ToolExecutionRequest.builder()
                .id("fc_call-incomplete")
                .name("search_knowledge")
                .arguments("{}")
                .build();
        conversations.updateMemoryMessages(id, ChatMessageSerializer.messagesToJson(List.of(
                SystemMessage.from("system"),
                UserMessage.from("previous question"),
                AiMessage.from(completeRequest),
                ToolExecutionResultMessage.from(completeRequest, "{}"),
                AiMessage.from("previous answer"),
                UserMessage.from("retry question"),
                AiMessage.from(incompleteRequest)
        )));

        PersistentChatMemoryStore persistentStore = new PersistentChatMemoryStore(conversations);
        List<ChatMessage> migrated = persistentStore.getMessages(id);
        check(migrated.size() == 7, "旧会话 JSON 应能迁移到 Community SQL SQLite store");
        check(Files.isRegularFile(persistentStore.databasePath()), "AI 上下文 SQLite 文件应已创建");
        check(conversations.memoryMessagesJson(id).isBlank(), "迁移成功后旧上下文 JSON 应清空，避免双写");

        persistentStore.updateMessages(id, List.of(
                SystemMessage.from("system"),
                UserMessage.from("new question"),
                AiMessage.from(incompleteRequest)
        ));
        check(persistentStore.getMessages(id).stream().anyMatch(message -> message instanceof AiMessage ai
                        && ai.hasToolExecutionRequests()
                        && "fc_call-incomplete".equals(ai.toolExecutionRequests().get(0).id())),
                "工具调用进行中必须保留未完成的 AiMessage，等待随后写入 tool 结果");
        int transientRepair = persistentStore.repair(id);
        check(transientRepair == 1 && persistentStore.getMessages(id).stream().noneMatch(message -> message instanceof AiMessage ai
                        && ai.hasToolExecutionRequests()
                        && "fc_call-incomplete".equals(ai.toolExecutionRequests().get(0).id())),
                "真正开始下一次请求前才应清理孤立工具调用");

        persistentStore.deleteMessages(id);
        conversations.updateMemoryMessages(id, ChatMessageSerializer.messagesToJson(List.of(
                SystemMessage.from("system"),
                UserMessage.from("previous question"),
                AiMessage.from(completeRequest),
                ToolExecutionResultMessage.from(completeRequest, "{}"),
                AiMessage.from("previous answer"),
                UserMessage.from("retry question"),
                AiMessage.from(incompleteRequest)
        )));

        int repaired = persistentStore.repair(id);
        check(repaired == 1, "启动新请求时应删除未完成工具调用尾部");
        check(persistentStore.getMessages(id).stream().noneMatch(message -> message instanceof AiMessage ai
                        && ai.hasToolExecutionRequests()
                        && "fc_call-incomplete".equals(ai.toolExecutionRequests().get(0).id())),
                "修复后不能再次持久化旧 function call ID");

        int reset = persistentStore.prepareForRetry(id);
        check(reset == 1, "重试应移除当前用户输入，但保留更早的完整轮次");
        List<ChatMessage> retained = persistentStore.getMessages(id);
        check(retained.size() == 5, "重试上下文应保留 system、旧问题、工具结果和旧回答");
        AiMessage retainedToolMessage = (AiMessage) retained.get(2);
        check("fc_call-complete".equals(retainedToolMessage.toolExecutionRequests().get(0).id()),
                "持久化上下文不能改写上游工具调用 ID，否则网关会拒绝 tool 输出");
        String gatewayCallId = "fc_WshHFFGPA9Ry0Wr6zoppvEFe";
        List<ChatMessage> gatewayRoundTrip = ChatMessageDeserializer.messagesFromJson(
                ChatMessageSerializer.messagesToJson(List.of(
                        AiMessage.from(ToolExecutionRequest.builder()
                                .id(gatewayCallId)
                                .name("search_knowledge")
                                .arguments("{}")
                                .build())
                ))
        );
        check(gatewayCallId.equals(((AiMessage) gatewayRoundTrip.get(0))
                        .toolExecutionRequests().get(0).id()),
                "网关格式的 fc_ 工具调用 ID 往返序列化后必须保持不变");
        check(retained.stream().noneMatch(message -> message instanceof AiMessage ai
                        && ai.hasToolExecutionRequests()
                        && ai.toolExecutionRequests().stream()
                        .anyMatch(request -> "fc_call-incomplete".equals(request.id()))),
                "重试上下文不能包含未完成工具调用");

        int untouched = persistentStore.prepareForRetry(id, "a prompt that was never persisted");
        check(untouched == 0, "当前用户消息尚未持久化时，自动重试不能误删上一轮历史");

        PersistentChatMemoryStore restoredStore = new PersistentChatMemoryStore(
                new ConversationStore(root)
        );
        check(restoredStore.getMessages(id).size() == 5, "重启后应从 Community SQL SQLite store 恢复上下文");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
