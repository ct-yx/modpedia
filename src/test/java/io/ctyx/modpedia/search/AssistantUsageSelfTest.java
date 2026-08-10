package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.LocalGuideScanner;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 确保欢迎页的 ModPedia 自助问题不会落到内容模组文档。 */
public final class AssistantUsageSelfTest {
    private AssistantUsageSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-assistant-usage-");
        try {
            String markdown = "---\n"
                    + "id: modpedia:guide/assistant-usage\n"
                    + "title: ModPedia 助手使用说明\n"
                    + "category: bootstrap\n"
                    + "keywords: ['ModPedia', '助手', '仅搜索']\n"
                    + "---\n\n"
                    + "# ModPedia 助手使用说明\n\n"
                    + "按 K 打开助手，设置中可以切换 AI 回答和仅搜索模式。\n";
            ScannedResource builtin = new ScannedResource(
                    "modpedia",
                    "ModPedia",
                    "1.0.0-beta.2",
                    "assets/modpedia/guides/modpedia/guide/assistant-usage.md",
                    "builtin_markdown",
                    markdown,
                    "assistant-usage-v1",
                    Map.of()
            );
            KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                    root,
                    new LocalGuideScanner.ScanResult(List.of(builtin), List.of()),
                    true
            );

            SearchResponse response = new RetrievalService(result.knowledgeRoot()).search(
                    "modpedia:guide/assistant-usage"
            );
            check(response.status() == SearchStatus.READY, "ModPedia 自助文档应可精确检索");
            check(!response.results().isEmpty(), "ModPedia 自助查询应返回结果");
            check("modpedia:guide/assistant-usage".equals(response.results().get(0).documentId()),
                    "自助查询不应被内容模组文档抢占结果");
            check(response.results().get(0).segmentMarkdown().contains("仅搜索模式"),
                    "自助查询应返回完整 Markdown 段落");
            System.out.println("ModPedia assistant usage self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
