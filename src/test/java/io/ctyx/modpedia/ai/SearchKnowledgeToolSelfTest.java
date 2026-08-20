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
import io.ctyx.modpedia.search.ItemCatalogEntry;
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
            List<KnowledgeDatabase.DocumentInput> documents = new ArrayList<>(List.of(
                    input("ae2:pressure", "压力容器", "压力容器需要能量和密封材料。\n\n细节段落：启动步骤是先安装控制器，再连接能源。"),
                    input("ae2:controller", "控制器", "控制器是压力容器的前置机器。\n\n控制器启动步骤和配方说明。"),
                    input("modpedia:generic", "搜索提示", "设置、前置条件和步骤是通用的搜索提示。"),
                    input("pneumaticcraft:pressure_tubes", "压力管道", "压力管道用于连接机器并防止漏气。"),
                    input("tfc:overview", "TerraFirmaCraft", "TerraFirmaCraft 从石器时代开始，逐步发展到金属时代。", "zh_cn",
                            List.of("tfc", "TerraFirmaCraft", "terrafirmacraft")),
                    input("modpedia:pressure-note", "压力提示", "压力是一个通用概念。", "zh_cn", List.of("pressure")),
                    input("pneumaticcraft:pressure-tubes-en", "Pressure Tubes",
                            "Pressure tubes connect machines and prevent leaks.", "en_us",
                            List.of("pressure tubes", "pressure")),
                    input("pneumaticcraft:drone-interface", "Drone Interface",
                            "The Drone Interface can be connected with Pressure Tubes, but this page is a machine reference.",
                            "en_us", List.of("pressure tubes", "drone interface", "pressure")),
                    input("ae2:installer", "安装器", "安装器用于安装控制器。", "zh_cn", List.of("安装器"))
            ));
            KnowledgeDatabase.sync(root, documents, true);
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

            JsonObject modIntroduction = parse(relevanceTool.search(
                    "TFC TerraFirmaCraft 是什么、核心玩法、如何开始",
                    "auto",
                    8,
                    "identify",
                    List.of()
            ));
            check(modIntroduction.get("returned_count").getAsInt() >= 1
                            && modIntroduction.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals("tfc:overview"),
                    "模组介绍类问题应保留 TFC 实体，不能被‘介绍/玩法/如何开始’等意图词带偏："
                            + modIntroduction);
            check(modIntroduction.get("results").getAsJsonArray().asList().stream()
                            .noneMatch(element -> element.getAsJsonObject().get("document_id").getAsString()
                                    .startsWith("modpedia:")),
                    "模组介绍类问题不应返回 ModPedia 通用页面");

            SearchKnowledgeTool compactTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    20,
                    4_000,
                    1,
                    ignored -> { }
            );
            JsonObject compactChinese = parse(compactTool.search(
                    "压力管道怎么连接",
                    "zh_cn",
                    8,
                    "steps",
                    List.of()
            ));
            check(compactChinese.get("returned_count").getAsInt() >= 1,
                    "没有空格的中文连续句也必须拆出实体和双字词");
            check(compactChinese.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().contains("pressure"),
                    "没有空格的中文实体查询不能返回无关页面");

            JsonObject prerequisiteQuestion = parse(compactTool.search(
                    "压力容器需要哪些前置条件",
                    "zh_cn",
                    8,
                    "prerequisite",
                    List.of()
            ));
            check(prerequisiteQuestion.get("returned_count").getAsInt() >= 1
                            && prerequisiteQuestion.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals("ae2:pressure"),
                    "前置条件自然语言中的‘需要’不能把压力容器实体误删");

            SearchKnowledgeTool troubleshootingTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    20,
                    4_000,
                    1,
                    ignored -> { }
            );
            JsonObject troubleshootingQuestion = parse(troubleshootingTool.search(
                    "如何避免漏气",
                    "zh_cn",
                    8,
                    "troubleshooting",
                    List.of()
            ));
            check(troubleshootingQuestion.get("returned_count").getAsInt() >= 1
                            && troubleshootingQuestion.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().contains("pressure"),
                    "故障问题中的‘避免’不能把漏气相关手册误判为无结果");

            JsonObject actionNamed = parse(compactTool.search(
                    "安装器怎么用",
                    "zh_cn",
                    8,
                    "steps",
                    List.of()
            ));
            check(actionNamed.get("returned_count").getAsInt() >= 1
                            && actionNamed.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals("ae2:installer"),
                    "实体名称包含动作词时不能被中文问题清理逻辑误删");

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

            SearchKnowledgeTool directRankingTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.EN_US,
                    20,
                    4_000,
                    1,
                    ignored -> { }
            );
            JsonObject directEntity = parse(directRankingTool.search(
                    "pressure tubes",
                    "en_us",
                    8,
                    "steps",
                    List.of()
            ));
            check(directEntity.get("returned_count").getAsInt() >= 1
                            && directEntity.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals("pneumaticcraft:pressure-tubes-en"),
                    "标题直接命中的 Pressure Tubes 页面必须排在正文引用 Pressure Tubes 的 Drone Interface 页面之前："
                            + directEntity);

            JsonObject irrelevant = parse(relevanceTool.search(
                    "完全不存在的物品如何使用",
                    "auto",
                    8,
                    "steps",
                    List.of()
            ));
            check(irrelevant.get("returned_count").getAsInt() == 0,
                    "明确实体没有命中时不能返回无关的通用页面");

            JsonObject unresolved = parse(compactTool.search(
                    "这个机器怎么用",
                    "auto",
                    8,
                    "steps",
                    List.of()
            ));
            check(unresolved.get("returned_count").getAsInt() == 0,
                    "没有给出机器名称时不能用 ModPedia 或其它模组页面冒充答案");

            documents.add(input("appliedpneumatics:overview", "Applied Pneumatics",
                    "Applied Pneumatics 是气动附属模组的总览页面。", "zh_cn",
                    List.of("appliedpneumatics", "Applied Pneumatics")));
            documents.add(input("appliedpneumatics:pressure_tube", "压力管道",
                    "压力管道用于连接机器并防止漏气。", "zh_cn",
                    List.of("appliedpneumatics", "压力管道", "pressure tube")));
            KnowledgeDatabase.sync(root, documents, true);
            retrieval.reload();
            JsonObject displayNameAndEntity = parse(relevanceTool.search(
                    "Applied Pneumatics压力管道怎么连接",
                    "auto",
                    8,
                    "steps",
                    List.of()
            ));
            check(displayNameAndEntity.get("returned_count").getAsInt() >= 1,
                    "模组显示名和中文实体连写时仍必须有结果");
            check(displayNameAndEntity.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals("appliedpneumatics:pressure_tube"),
                    "模组名前缀不能把结果泛化到模组总览页面");

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
            check(first.get("results").getAsJsonArray().get(0).getAsJsonObject().has("source_path")
                            && first.get("results").getAsJsonArray().get(0).getAsJsonObject().has("source_type")
                            && first.get("results").getAsJsonArray().get(0).getAsJsonObject().has("content_kind")
                            && first.get("results").getAsJsonArray().get(0).getAsJsonObject().has("matched_terms")
                            && first.get("results").getAsJsonArray().get(0).getAsJsonObject().has("heading_path"),
                    "模型结果应保留来源、内容类型、匹配词和标题路径，避免上下文压缩后丢失检索事实");
            check(first.get("has_more").getAsBoolean(), "存在第二篇候选时应报告 has_more");

            String firstId = first.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString();
            JsonObject sameDocumentExpansion = parse(tool.search(
                    "细节段落",
                    "zh_cn",
                    8,
                    "steps",
                    List.of(firstId)
            ));
            check(sameDocumentExpansion.get("returned_count").getAsInt() >= 1,
                    "排除已读文档后仍应允许返回该文档未读的细节段落");
            check(sameDocumentExpansion.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("document_id").getAsString().equals(firstId)
                            && sameDocumentExpansion.get("results").getAsJsonArray().get(0).getAsJsonObject()
                            .get("segment_markdown").getAsString().contains("启动步骤"),
                    "补搜应返回同一文档中新的步骤段落，而不是重复首段或错误页面：" + sameDocumentExpansion);
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

            JsonObject focusVariant = parse(tool.search("压力容器", "zh_cn", 8, "prerequisite", List.of()));
            check(!focusVariant.get("hint").getAsString().contains("重复查询"),
                    "同一查询补充不同 focus 时应允许重新检索");
            JsonObject repeated = parse(tool.search("压力容器", "zh_cn", 8, "identify", List.of()));
            check(repeated.get("hint").getAsString().contains("重复查询"), "相同查询应提示模型改写");
            check(traces.size() == 5, "每次工具调用都应保存搜索轨迹");

            SearchKnowledgeTool multiEntityTool = new SearchKnowledgeTool(
                    retrieval,
                    SearchLanguage.ZH_CN,
                    20,
                    8_000,
                    1,
                    ignored -> { }
            );
            JsonObject multiEntity = parse(multiEntityTool.search(
                    "压力容器和控制器怎么启动",
                    "zh_cn",
                    8,
                    "steps",
                    List.of()
            ));
            List<String> multiIds = new ArrayList<>();
            multiEntity.get("results").getAsJsonArray().forEach(element -> multiIds.add(
                    element.getAsJsonObject().get("document_id").getAsString()
            ));
            check(multiIds.contains("ae2:pressure") && multiIds.contains("ae2:controller"),
                    "中文多实体查询应拆开召回压力容器和控制器，而不是要求同一段同时出现两者");
            testChineseItemNameToEnglishManual(root);
            testIncompleteToolTurnCleanup();
            testPersistentContextRepair(conversationsRoot);
            System.out.println("ModPedia search knowledge tool self-test passed");
        } finally {
            deleteTree(root);
            deleteTree(conversationsRoot);
        }
    }

    private static void testChineseItemNameToEnglishManual(Path root) throws Exception {
        KnowledgeDatabase.syncItemCatalog(
                root,
                "zh_cn",
                List.of(new ItemCatalogEntry(
                        "pneumaticcraft:pressure_tube",
                        "zh_cn",
                        "压力管道",
                        "- 用于连接压缩空气并防止漏气",
                        "pneumaticcraft",
                        "pressure-tube-zh-v1"
                ))
        );
        // 真实整合包中中文名称来自当前语言物品目录，手册可能只有英文；关键词
        // 中保留原始物品 ID，检索必须先用 ID 收窄，再返回英文手册，而不是返回
        // assistant-usage 中的示例句。
        KnowledgeDatabase.sync(
                root,
                List.of(input(
                        "pneumaticcraft:pressure-tubes-en",
                        "Pressure Tubes",
                        "Pressure Tubes connect machines and prevent leaks.",
                        "en_us",
                        List.of("pressure tubes", "pneumaticcraft:pressure_tube")
                )),
                false
        );
        RetrievalService retrieval = new RetrievalService(root);
        SearchKnowledgeTool tool = new SearchKnowledgeTool(
                retrieval,
                SearchLanguage.ZH_CN,
                8,
                8_000,
                1,
                ignored -> { }
        );
        JsonObject output = parse(tool.search(
                "压力管道怎么连接并防止漏气",
                "auto",
                8,
                "steps",
                List.of()
        ));
        check(output.get("item_context_count").getAsInt() == 1,
                "中文物品名应先解析到物品目录 ID");
        check(output.get("returned_count").getAsInt() >= 1,
                "中文物品名应能回退到英文手册：" + output);
        check(output.get("results").getAsJsonArray().asList().stream()
                        .anyMatch(element -> element.getAsJsonObject().get("document_id").getAsString()
                                .equals("pneumaticcraft:pressure-tubes-en")),
                "中文物品名不能只返回 ModPedia 示例页或其它压力设备");
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

        ToolExecutionRequest otherRequest = ToolExecutionRequest.builder()
                .id("other-call")
                .name("search_knowledge")
                .arguments("{}")
                .build();
        List<ChatMessage> orphanResult = List.of(
                SystemMessage.from("system"),
                UserMessage.from("complete"),
                AiMessage.from(completeRequest),
                ToolExecutionResultMessage.from(completeRequest, "{}"),
                AiMessage.from("answer"),
                ToolExecutionResultMessage.from(otherRequest, "orphan")
        );
        check(PersistentChatMemoryStore.removeIncompleteToolTurn(orphanResult).size() == 5,
                "孤立 Tool Result 也必须从自身位置截断，不能进入下一次模型请求");

        List<ChatMessage> mismatchedResult = List.of(
                SystemMessage.from("system"),
                UserMessage.from("question"),
                AiMessage.from(brokenRequest),
                ToolExecutionResultMessage.from(otherRequest, "wrong id")
        );
        check(PersistentChatMemoryStore.removeIncompleteToolTurn(mismatchedResult).size() == 2,
                "工具结果 ID 不匹配时应删除整个未完成工具回合");
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
