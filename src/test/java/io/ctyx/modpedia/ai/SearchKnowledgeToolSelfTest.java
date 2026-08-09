package io.ctyx.modpedia.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** search_knowledge 工具参数、来源去重、has_more 和上下文限制回归测试。 */
public final class SearchKnowledgeToolSelfTest {
    private SearchKnowledgeToolSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-ai-search-tool-");
        try {
            KnowledgeDatabase.sync(root, List.of(
                    input("ae2:pressure", "压力容器", "压力容器需要能量和密封材料。\n\n启动步骤：先安装控制器，再连接能源。"),
                    input("ae2:controller", "控制器", "控制器是压力容器的前置机器。\n\n控制器启动步骤和配方说明。")
            ), true);
            RetrievalService retrieval = new RetrievalService(root);
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
            JsonObject second = parse(tool.search("控制器", "zh_cn", 8, "steps", List.of(firstId)));
            check(second.get("returned_count").getAsInt() >= 1, "改写查询并排除已读文档后应继续返回新来源");
            check(!second.get("results").getAsJsonArray().get(0).getAsJsonObject()
                    .get("document_id").getAsString().equals(firstId), "补搜不应重复第一篇来源");

            JsonObject repeated = parse(tool.search("压力容器", "zh_cn", 8, "prerequisite", List.of()));
            check(repeated.get("hint").getAsString().contains("重复查询"), "相同查询应提示模型改写");
            check(traces.size() == 3, "每次工具调用都应保存搜索轨迹");
            System.out.println("ModPedia search knowledge tool self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static KnowledgeDatabase.DocumentInput input(String id, String title, String body) {
        KnowledgeDocument document = new KnowledgeDocument(
                id,
                "Applied Energistics 2",
                "manual_markdown",
                title,
                "guide",
                List.of(title, id),
                "19.2.17",
                "assets/ae2/guides/" + id.substring(id.indexOf(':') + 1) + ".md",
                "# " + title + "\n\n" + body
        );
        return new KnowledgeDatabase.DocumentInput(
                "generated:" + id,
                id + "-fingerprint",
                "generated/" + id + ".md",
                "zh_cn",
                10,
                document
        );
    }

    private static JsonObject parse(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
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
