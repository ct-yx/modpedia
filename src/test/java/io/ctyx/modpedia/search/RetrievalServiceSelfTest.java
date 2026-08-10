package io.ctyx.modpedia.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 不引入额外测试运行时的纯 Java 搜索回归测试。由 Gradle test 任务调用。 */
public final class RetrievalServiceSelfTest {
    private RetrievalServiceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path tempDirectory = Files.createTempDirectory("modpedia-search-test");
        try {
            Fixture fixture = new Fixture(tempDirectory);
            fixture.writeInitialData();
            testWholeParagraph(fixture);
            testCompactChinesePhrase(fixture);
            testIdentifierAndRanking(fixture);
            testFencedCodeBlock(fixture);
            testLimitAndDeduplication(fixture);
            testSynonyms(fixture);
            testIndexStates(fixture);
            testReload(fixture);
            System.out.println("ModPedia search self-test passed");
        } finally {
            deleteTree(tempDirectory);
        }
    }

    private static void testWholeParagraph(Fixture fixture) {
        SearchResponse response = fixture.service().search("网络控制器");
        check(response.status() == SearchStatus.READY, "中文段落查询应返回 READY");
        check(!response.results().isEmpty(), "中文段落查询应有结果");
        SearchResult result = response.results().get(0);
        check("ae2:controller".equals(result.documentId()), "中文查询应命中控制器文档");
        check("ME Controller".equals(result.headingPath()), "结果应带最近标题");
        check(result.segmentMarkdown().contains("网络控制器负责管理网络的核心连接。"), "结果应保留命中句");
        check(result.segmentMarkdown().contains("这一行也必须随着完整段落返回。"), "结果应保留完整段落");
    }

    private static void testIdentifierAndRanking(Fixture fixture) {
        SearchResponse identifier = fixture.service().search("AE2:CONTROLLER");
        check("ae2:controller".equals(identifier.results().get(0).documentId()), "ID 查询应忽略大小写");

        SearchResponse title = fixture.service().search("controller");
        check("ae2:controller".equals(title.results().get(0).documentId()), "标题命中应优先于正文命中");
    }

    private static void testCompactChinesePhrase(Fixture fixture) {
        SearchResponse response = fixture.service().search("网络控制器怎么连接");
        check(response.status() == SearchStatus.READY, "没有空格的中文实体问题应返回 READY");
        check(!response.results().isEmpty(), "没有空格的中文实体问题应有结果");
        check("ae2:controller".equals(response.results().get(0).documentId()),
                "中文实体完整短语命中时不能返回泛相关页面");
    }

    private static void testFencedCodeBlock(Fixture fixture) {
        SearchResponse response = fixture.service().search("example line two");
        check(response.status() == SearchStatus.READY, "代码块查询应返回 READY");
        String segment = response.results().get(0).segmentMarkdown();
        check(segment.contains("network example line one"), "代码块结果应保留前一行");
        check(segment.contains("network example line two"), "代码块结果应保留命中行");
    }

    private static void testLimitAndDeduplication(Fixture fixture) {
        SearchResponse response = fixture.service().search(new SearchQuery("controller", 1));
        check(response.results().size() == 1, "limit 应限制结果数量");
        check("ae2:controller".equals(response.results().get(0).documentId()), "相同文档应只保留一个最高分段落");
    }

    private static void testSynonyms(Fixture fixture) throws IOException {
        Files.writeString(
                fixture.tempDirectory().resolve("search-synonyms.json"),
                "{\"groups\":[[\"自动合成\",\"autocrafting\"]]}\n",
                StandardCharsets.UTF_8
        );
        fixture.writeDocument(
                "autocrafting.md",
                "---\n"
                        + "id: 'ae2:autocrafting'\n"
                        + "source_mod: 'ae2'\n"
                        + "source_type: 'guideme_markdown'\n"
                        + "title: 'Autocrafting'\n"
                        + "category: 'guide'\n"
                        + "keywords: ['autocrafting']\n"
                        + "source_version: '19.2.17'\n"
                        + "source_path: 'assets/ae2/ae2guide/autocrafting.md'\n"
                        + "---\n\n"
                        + "# Autocrafting\n\n"
                        + "Autocrafting uses a crafting CPU.\n"
        );
        fixture.writeManifest(true);
        fixture.writeKeywordIndex(true);
        fixture.service().reload();

        SearchResponse response = fixture.service().search("自动合成");
        check(response.status() == SearchStatus.READY, "同义词查询应返回 READY");
        check("ae2:autocrafting".equals(response.results().get(0).documentId()), "同义词应命中英文文档");
        check(response.results().get(0).matchedTerms().contains("autocrafting"), "结果应记录同义词命中");
    }

    private static void testIndexStates(Fixture fixture) throws IOException {
        RetrievalService missing = new RetrievalService(fixture.tempDirectory().resolve("missing"));
        check(missing.search(" ").status() == SearchStatus.EMPTY_QUERY, "空查询应返回 EMPTY_QUERY");
        check(missing.search("network").status() == SearchStatus.INDEX_NOT_READY, "缺失索引应返回 INDEX_NOT_READY");

        Files.writeString(
                fixture.knowledgeRoot().resolve("keyword-index.json"),
                "{broken",
                StandardCharsets.UTF_8
        );
        RetrievalService corrupt = new RetrievalService(fixture.knowledgeRoot());
        check(corrupt.search("network").status() == SearchStatus.INDEX_ERROR, "损坏索引应返回 INDEX_ERROR");
    }

    private static void testReload(Fixture fixture) throws IOException {
        fixture.writeInitialData();
        fixture.service().reload();
        fixture.writeDocument(
                "new.md",
                "---\n"
                        + "id: 'pneumaticcraft:new'\n"
                        + "source_mod: 'pneumaticcraft'\n"
                        + "source_type: 'patchouli_json'\n"
                        + "title: 'Pressure Chamber'\n"
                        + "category: 'entries'\n"
                        + "keywords: ['pressure chamber']\n"
                        + "source_version: '8.2.23'\n"
                        + "source_path: 'assets/pneumaticcraft/patchouli_books/book/en_us/entries/new.json'\n"
                        + "---\n\n"
                        + "# Pressure Chamber\n\n"
                        + "The pressure chamber is a new searchable machine.\n"
        );
        fixture.writeManifest(false, true);
        fixture.writeKeywordIndex(false, true);

        SearchResponse response = fixture.service().search("pressure chamber");
        check(response.status() == SearchStatus.READY, "reload 后查询应返回 READY");
        check("pneumaticcraft:new".equals(response.results().get(0).documentId()), "reload 后应命中新文档");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class Fixture {
        private final Path tempDirectory;
        private final Path knowledgeRoot;
        private final RetrievalService service;

        private Fixture(Path tempDirectory) throws IOException {
            this.tempDirectory = tempDirectory;
            this.knowledgeRoot = tempDirectory.resolve("knowledge");
            Files.createDirectories(knowledgeRoot.resolve("generated"));
            this.service = new RetrievalService(knowledgeRoot);
        }

        private Path tempDirectory() {
            return tempDirectory;
        }

        private Path knowledgeRoot() {
            return knowledgeRoot;
        }

        private RetrievalService service() {
            return service;
        }

        private void writeInitialData() throws IOException {
            writeDocument(
                    "controller.md",
                    "---\n"
                            + "id: 'ae2:controller'\n"
                            + "source_mod: 'ae2'\n"
                            + "source_type: 'guideme_markdown'\n"
                            + "title: 'ME Controller'\n"
                            + "category: 'guide'\n"
                            + "keywords: ['ae2', 'controller', '网络控制器', 'network']\n"
                            + "source_version: '19.2.17'\n"
                            + "source_path: 'assets/ae2/ae2guide/controller.md'\n"
                            + "---\n\n"
                            + "# ME Controller\n\n"
                            + "网络控制器负责管理网络的核心连接。\n"
                            + "这一行也必须随着完整段落返回。\n\n"
                            + "## 连接方式\n\n"
                            + "使用 cable 连接设备。\n\n"
                            + "```text\n"
                            + "network example line one\n\n"
                            + "network example line two\n"
                            + "```\n"
            );
            writeDocument(
                    "automation.md",
                    "---\n"
                            + "id: 'ae2:automation'\n"
                            + "source_mod: 'ae2'\n"
                            + "source_type: 'guideme_markdown'\n"
                            + "title: 'Automation'\n"
                            + "category: 'guide'\n"
                            + "keywords: ['automation', 'network']\n"
                            + "source_version: '19.2.17'\n"
                            + "source_path: 'assets/ae2/ae2guide/automation.md'\n"
                            + "---\n\n"
                            + "# Automation\n\n"
                            + "This paragraph explains how a controller supports network automation.\n"
            );
            writeManifest(false, false);
            writeKeywordIndex(false, false);
        }

        private void writeDocument(String name, String content) throws IOException {
            Files.writeString(knowledgeRoot.resolve("generated").resolve(name), content, StandardCharsets.UTF_8);
        }

        private void writeManifest(boolean withAutocrafting) throws IOException {
            writeManifest(withAutocrafting, false);
        }

        private void writeManifest(boolean withAutocrafting, boolean withNewDocument) throws IOException {
            StringBuilder documents = new StringBuilder();
            documents.append(documentJson("ae2:controller", "controller.md", "ME Controller", "['ae2','controller','网络控制器','network']"));
            documents.append(",\n").append(documentJson("ae2:automation", "automation.md", "Automation", "['automation','network']"));
            if (withAutocrafting) {
                documents.append(",\n").append(documentJson("ae2:autocrafting", "autocrafting.md", "Autocrafting", "['autocrafting']"));
            }
            if (withNewDocument) {
                documents.append(",\n").append(documentJson("pneumaticcraft:new", "new.md", "Pressure Chamber", "['pressure chamber']"));
            }
            Files.writeString(
                    knowledgeRoot.resolve("manifest.json"),
                    "{\n  \"schema_version\": 1,\n  \"documents\": [\n"
                            + documents
                            + "\n  ]\n}\n",
                    StandardCharsets.UTF_8
            );
        }

        private String documentJson(String id, String path, String title, String keywords) {
            return "    {\"id\":\"" + id + "\",\"path\":\"generated/" + path + "\","
                    + "\"source_mod\":\"" + id.substring(0, id.indexOf(':')) + "\","
                    + "\"source_type\":\"guideme_markdown\",\"title\":\"" + title + "\","
                    + "\"category\":\"guide\",\"keywords\":" + keywords + ","
                    + "\"source_version\":\"1\",\"source_path\":\"assets/" + path + "\"}";
        }

        private void writeKeywordIndex(boolean withAutocrafting) throws IOException {
            writeKeywordIndex(withAutocrafting, false);
        }

        private void writeKeywordIndex(boolean withAutocrafting, boolean withNewDocument) throws IOException {
            StringBuilder index = new StringBuilder();
            index.append("{\n")
                    .append("  \"ae2\": [\"ae2:controller\", \"ae2:automation\"");
            if (withAutocrafting) {
                index.append(", \"ae2:autocrafting\"");
            }
            index.append("],\n")
                    .append("  \"controller\": [\"ae2:controller\", \"ae2:automation\"],\n")
                    .append("  \"网络控制器\": [\"ae2:controller\"],\n")
                    .append("  \"network\": [\"ae2:controller\", \"ae2:automation\"],\n")
                    .append("  \"example\": [\"ae2:controller\"],\n")
                    .append("  \"line\": [\"ae2:controller\"]");
            if (withAutocrafting) {
                index.append(",\n  \"autocrafting\": [\"ae2:autocrafting\"]");
            }
            if (withNewDocument) {
                index.append(",\n  \"pressure chamber\": [\"pneumaticcraft:new\"],\n")
                        .append("  \"pressure\": [\"pneumaticcraft:new\"],\n")
                        .append("  \"chamber\": [\"pneumaticcraft:new\"]");
            }
            index.append("\n}\n");
            Files.writeString(knowledgeRoot.resolve("keyword-index.json"), index, StandardCharsets.UTF_8);
        }
    }
}
