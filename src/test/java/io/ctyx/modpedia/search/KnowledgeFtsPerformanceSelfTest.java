package io.ctyx.modpedia.search;

import io.ctyx.modpedia.knowledge.KnowledgeDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * FTS5 结构回归：验证 external-content、rank 排序、optimize 和 50 ms 热查询预算。
 * 大型整合包的最终数字由 knowledgeBenchmark 任务写入 build/reports。
 */
public final class KnowledgeFtsPerformanceSelfTest {
    private KnowledgeFtsPerformanceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("modpedia-fts-performance-");
        String previousStorage = System.getProperty("modpedia.benchmark.fts.storage");
        String previousOptimize = System.getProperty("modpedia.benchmark.fts.optimize");
        try {
            System.clearProperty("modpedia.benchmark.fts.storage");
            System.clearProperty("modpedia.benchmark.fts.optimize");
            List<KnowledgeDatabase.DocumentInput> inputs = buildInputs(400);
            KnowledgeDatabase.SyncResult sync = KnowledgeDatabase.sync(
                    temporary.resolve("config/modpedia/knowledge"),
                    inputs,
                    true
            );
            check(sync.documentCount() == 400, "性能夹具文档数量不正确");

            Path database = KnowledgeDatabase.path(temporary.resolve("config/modpedia/knowledge"));
            KnowledgeDatabase.DatabaseStats stats = KnowledgeDatabase.inspect(database);
            check("external-content".equals(stats.ftsStorage()), "生产默认应使用 external-content");
            check(stats.ftsContentBytes() == 0, "external-content 不应创建 FTS 正文副本");
            check(stats.ftsRowCount() == stats.segmentCount(), "FTS 行数应与段落数一致");

            List<String> plan = KnowledgeDatabase.explainSearchPlan(
                    database,
                    SearchQuery.of("压力容器"),
                    SearchLanguage.ZH_CN,
                    Map.of()
            );
            check(plan.stream().noneMatch(value -> value.contains("TEMP B-TREE")),
                    "ORDER BY rank 不应额外创建排序临时表：" + plan);

            try (KnowledgeDatabase.Reader reader = KnowledgeDatabase.openReader(database)) {
                SearchResponse response = reader.search(
                        SearchQuery.of("压力容器"),
                        SearchLanguage.ZH_CN,
                        Map.of()
                );
                check(response.hasResults(), "中文短语应命中 FTS5");
                check(response.results().get(0).segmentMarkdown().contains("完整段落"),
                        "结果必须从 segments 事实表读取完整 Markdown");

                for (int index = 0; index < 8; index++) {
                    reader.search(SearchQuery.of("压力容器"), SearchLanguage.ZH_CN, Map.of());
                }
                long[] samples = new long[30];
                for (int index = 0; index < samples.length; index++) {
                    long start = System.nanoTime();
                    reader.search(SearchQuery.of("压力容器"), SearchLanguage.ZH_CN, Map.of());
                    samples[index] = System.nanoTime() - start;
                }
                Arrays.sort(samples);
                double p95Ms = samples[(int) Math.ceil(samples.length * .95) - 1] / 1_000_000.0;
                check(p95Ms <= 50.0, "FTS5 热查询 p95 超过 50 ms：" + p95Ms);
                System.out.printf("ModPedia FTS5 performance self-test: %d docs, %d segments, p95 %.2f ms%n",
                        stats.documentCount(), stats.segmentCount(), p95Ms);
            }

            KnowledgeDatabase.OptimizationStats optimization = KnowledgeDatabase.optimize(
                    database,
                    SearchQuery.of("压力容器"),
                    SearchLanguage.ZH_CN,
                    Map.of(),
                    true
            );
            check(optimization.planAfter().stream().noneMatch(value -> value.contains("TEMP B-TREE")),
                    "optimize 后查询计划出现排序临时表：" + optimization.planAfter());
            System.out.println("ModPedia FTS5 optimization self-test passed");
        } finally {
            restoreProperty("modpedia.benchmark.fts.storage", previousStorage);
            restoreProperty("modpedia.benchmark.fts.optimize", previousOptimize);
            deleteTree(temporary);
        }
    }

    private static List<KnowledgeDatabase.DocumentInput> buildInputs(int count) {
        List<KnowledgeDatabase.DocumentInput> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = "fixture:pressure_vessel_" + index;
            KnowledgeDocument document = new KnowledgeDocument(
                    id,
                    "fixture",
                    "guideme_markdown",
                    "压力容器 " + index,
                    "machines",
                    List.of("压力容器", "automation", "recipe", "fixture:item_" + index),
                    "1.21.1",
                    "assets/fixture/guides/pressure_" + index + ".md",
                    "# 压力容器 " + index + "\n\n"
                            + "完整段落：压力容器用于自动化测试。\n\n"
                            + "## 配方\n\n"
                            + "配方需要 fixture:item_" + index + " 和铁锭。\n\n"
                            + "## 步骤\n\n"
                            + "先连接管道，再启动机器。\n"
            );
            result.add(new KnowledgeDatabase.DocumentInput(
                    "fixture:" + id,
                    "fixture-fingerprint-" + index,
                    "generated/fixture/pressure_" + index + ".md",
                    index % 2 == 0 ? "zh_cn" : "neutral",
                    0,
                    document
            ));
        }
        return List.copyOf(result);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
