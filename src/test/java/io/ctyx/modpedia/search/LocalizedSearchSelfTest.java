package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.JsonGuideDocumentConverter;
import io.ctyx.modpedia.knowledge.LocalGuideScanner;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 验证手册文档可以通过模组语言表中的中文物品名检索。 */
public final class LocalizedSearchSelfTest {
    private LocalizedSearchSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testPatchouliPageAlias();
        Path tempDirectory = Files.createTempDirectory("modpedia-localized-search-test");
        try {
            ScannedResource source = new ScannedResource(
                    "ae2",
                    "Applied Energistics 2",
                    "19.2.17",
                    "assets/ae2/ae2guide/items-blocks-machines/annihilation_plane.md",
                    "guideme_markdown",
                    "# Annihilation Plane\n\n<RecipeFor id=\"annihilation_plane\" />\n",
                    "test",
                    Map.of("item.ae2.annihilation_plane", "ME破坏面板")
            );
            ScannedResource relatedPage = new ScannedResource(
                    "ae2",
                    "Applied Energistics 2",
                    "19.2.17",
                    "assets/ae2/ae2guide/example-setups/ore-fortuner.md",
                    "guideme_markdown",
                    "# Automatic Ore Fortuner\n\n<ItemLink id=\"annihilation_plane\" />\n",
                    "test-related",
                    Map.of("item.ae2.annihilation_plane", "ME破坏面板")
            );
            KnowledgeCompiler.CompileResult build = new KnowledgeCompiler().compile(
                    tempDirectory,
                    new LocalGuideScanner.ScanResult(List.of(source, relatedPage), List.of()),
                    true
            );

            SearchResponse response = new RetrievalService(build.knowledgeRoot()).search("破坏面板");
            check(response.status() == SearchStatus.READY, "中文物品名查询应返回 READY");
            check(response.results().size() == 1, "中文物品名不应被关联页面的引用扩大结果");
            check(
                    "ae2:ae2guide/items-blocks-machines/annihilation_plane".equals(
                            response.results().get(0).documentId()
                    ),
                    "中文物品名应命中破坏面板手册"
            );
            System.out.println("ModPedia localized search self-test passed");
        } finally {
            deleteTree(tempDirectory);
        }
    }

    private static void testPatchouliPageAlias() {
        KnowledgeDocument document = new JsonGuideDocumentConverter().convert(new ScannedResource(
                "pneumaticcraft",
                "PneumaticCraft",
                "8.2.23",
                "assets/pneumaticcraft/patchouli_books/book/en_us/entries/tools/drone.json",
                "patchouli_json",
                "{\"name\":\"Drone\"}",
                "test",
                Map.of("item.pneumaticcraft.drone", "气动无人机")
        ));
        check(document.keywords().contains("气动无人机"), "Patchouli 页面自身的物品名应加入本地化搜索词");
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
