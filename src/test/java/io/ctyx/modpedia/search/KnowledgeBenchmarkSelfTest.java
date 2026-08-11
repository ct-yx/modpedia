package io.ctyx.modpedia.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.LocalGuideScanner;
import io.ctyx.modpedia.knowledge.ScannedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在本地知识库上执行三档规模、双语检索基准。
 *
 * <p>该类只属于测试 source set。它读取 JAR 和现有知识库，所有转换结果写入临时目录，
 * 最终只把 JSON/Markdown 报告写入 build/reports。</p>
 */
public final class KnowledgeBenchmarkSelfTest {
    private static final Gson JSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final int SCALE_FACTOR = 10;
    private static final int DEFAULT_WARMUP_SAMPLES = 5;
    private static final int DEFAULT_SEARCH_SAMPLES = 30;
    private static final long DEFAULT_SEARCH_BUDGET_MS = 50L;
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");

    private KnowledgeBenchmarkSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path knowledgeRoot = propertyPath(
                "modpedia.benchmark.knowledgeRoot",
                projectRoot.resolve("run/config/modpedia/knowledge")
        );
        Path runMods = propertyPath("modpedia.benchmark.runMods", projectRoot.resolve("run/mods"));
        Path downloads = propertyPath(
                "modpedia.benchmark.downloads",
                Path.of(System.getProperty("user.home", ".")).resolve("Downloads")
        );
        Path reportDirectory = propertyPath(
                "modpedia.benchmark.reportDirectory",
                projectRoot.resolve("build/reports/modpedia")
        );
        long searchBudgetMs = Long.getLong(
                "modpedia.benchmark.searchBudgetMs",
                DEFAULT_SEARCH_BUDGET_MS
        );
        int warmupSamples = Math.max(0, Integer.getInteger(
                "modpedia.benchmark.warmupSamples",
                DEFAULT_WARMUP_SAMPLES
        ));
        int searchSamples = Math.max(1, Integer.getInteger(
                "modpedia.benchmark.searchSamples",
                DEFAULT_SEARCH_SAMPLES
        ));

        List<Path> baselineJars = JarCorpusLoader.discoverJars(runMods);
        List<Path> expandedJars = mergeJars(baselineJars, JarCorpusLoader.discoverJars(downloads));
        if (baselineJars.isEmpty()) {
            throw new IllegalStateException("找不到当前实例 JAR 语料：" + runMods);
        }
        List<JarCorpusLoader.ResourceRoot> roots = List.of(
                new JarCorpusLoader.ResourceRoot(
                        projectRoot.resolve("src/main/resources"),
                        "modpedia",
                        "ModPedia · 模组百科",
                        "1.0.0"
                )
        );

        List<ScenarioReport> reports = new ArrayList<>();
        for (JarCorpusLoader.LanguageMode language : JarCorpusLoader.LanguageMode.values()) {
            LoadedScenario baseline = loadScenario(
                    "baseline",
                    baselineJars,
                    roots,
                    language
            );
            reports.add(runScenario(
                    "baseline",
                    language,
                    baseline,
                    knowledgeRoot,
                    searchBudgetMs,
                    warmupSamples,
                    searchSamples,
                    false
            ));

            LoadedScenario expanded = loadScenario(
                    "expanded",
                    expandedJars,
                    roots,
                    language
            );
            reports.add(runScenario(
                    "expanded",
                    language,
                    expanded,
                    knowledgeRoot,
                    searchBudgetMs,
                    warmupSamples,
                    searchSamples,
                    false
            ));

            LoadedScenario scaled = new LoadedScenario(
                    scaleCorpus(baseline.corpus(), SCALE_FACTOR),
                    baseline.loadNanos()
            );
            reports.add(runScenario(
                    "scale-" + SCALE_FACTOR + "x",
                    language,
                    scaled,
                    knowledgeRoot,
                    searchBudgetMs,
                    warmupSamples,
                    searchSamples,
                    true
            ));
        }

        LoadedScenario comparisonCorpus = loadScenario(
                "fts-comparison",
                expandedJars,
                roots,
                JarCorpusLoader.LanguageMode.ZH_CN
        );
        FtsComparison comparison = compareFtsStorage(
                comparisonCorpus,
                knowledgeRoot,
                searchBudgetMs,
                warmupSamples,
                searchSamples
        );

        Files.createDirectories(reportDirectory);
        Path jsonPath = reportDirectory.resolve("knowledge-benchmark.json");
        Path markdownPath = reportDirectory.resolve("knowledge-benchmark.md");
        BenchmarkReport report = new BenchmarkReport(
                Instant.now().toString(),
                projectRoot.toString(),
                searchBudgetMs,
                SCALE_FACTOR,
                reports,
                comparison
        );
        Files.writeString(jsonPath, JSON.toJson(report), StandardCharsets.UTF_8);
        Files.writeString(markdownPath, toMarkdown(report), StandardCharsets.UTF_8);

        System.out.println("ModPedia knowledge benchmark completed");
        System.out.println("JSON report: " + jsonPath);
        System.out.println("Markdown report: " + markdownPath);
        for (ScenarioReport scenario : reports) {
            System.out.printf(
                    Locale.ROOT,
                    "%s/%s: %d sources, %d documents, search p95 %.2f ms, budget %s%n",
                    scenario.scenario(),
                    scenario.language(),
                    scenario.corpus().sourceCount(),
                    scenario.files().documentCount(),
                    scenario.search().overallP95Ms(),
                    scenario.search().withinBudget() ? "PASS" : "OVER"
            );
        }
    }

    private static LoadedScenario loadScenario(
            String name,
            List<Path> jars,
            List<JarCorpusLoader.ResourceRoot> roots,
            JarCorpusLoader.LanguageMode language
    ) throws IOException {
        long start = System.nanoTime();
        JarCorpusLoader.LoadedCorpus corpus = JarCorpusLoader.load(jars, roots, language);
        long elapsed = System.nanoTime() - start;
        System.out.printf(
                Locale.ROOT,
                "Loaded %s/%s: %d jars, %d source resources in %.2f ms%n",
                name,
                language,
                corpus.stats().jarCount(),
                corpus.resources().size(),
                millis(elapsed)
        );
        return new LoadedScenario(corpus, elapsed);
    }

    private static ScenarioReport runScenario(
            String scenario,
            JarCorpusLoader.LanguageMode language,
            LoadedScenario loaded,
            Path existingKnowledgeRoot,
            long searchBudgetMs,
            int warmupSamples,
            int searchSamples,
            boolean synthetic
    ) throws Exception {
        Path temporaryConfig = Files.createTempDirectory("modpedia-knowledge-benchmark-");
        try {
            copyCustomDocuments(
                    existingKnowledgeRoot.resolve("custom"),
                    temporaryConfig.resolve("modpedia/knowledge/custom")
            );

            KnowledgeCompiler compiler = new KnowledgeCompiler();
            long coldStart = System.nanoTime();
            KnowledgeCompiler.CompileResult cold = compiler.compile(
                    temporaryConfig,
                    scanResult(loaded.corpus().resources(), loaded.corpus().stats()),
                    true
            );
            long coldNanos = System.nanoTime() - coldStart;

            long incrementalStart = System.nanoTime();
            KnowledgeCompiler.CompileResult incremental = compiler.compile(
                    temporaryConfig,
                    scanResult(loaded.corpus().resources(), loaded.corpus().stats()),
                    false
            );
            long incrementalNanos = System.nanoTime() - incrementalStart;

            List<ScannedResource> changedSources = changeOneFingerprint(loaded.corpus().resources());
            long changedStart = System.nanoTime();
            KnowledgeCompiler.CompileResult changed = compiler.compile(
                    temporaryConfig,
                    scanResult(changedSources, loaded.corpus().stats()),
                    false
            );
            long changedNanos = System.nanoTime() - changedStart;

            Path resultRoot = changed.knowledgeRoot();
            if (!Files.isRegularFile(KnowledgeDatabase.path(resultRoot))) {
                throw new IllegalStateException(
                        "基准编译没有生成当前版本 SQLite：" + resultRoot
                                + "；冷构建警告=" + cold.report().warnings()
                                + "；变更构建警告=" + changed.report().warnings()
                );
            }
            long reloadStart = System.nanoTime();
            RetrievalService service = new RetrievalService(resultRoot);
            service.reload();
            long reloadNanos = System.nanoTime() - reloadStart;
            SearchResponse indexCheck = service.search("__modpedia_benchmark_index_check__");
            if (indexCheck.status() == SearchStatus.INDEX_ERROR) {
                throw new AssertionError("基准知识库索引无法读取：" + resultRoot + "，" + indexCheck.error());
            }

            MeasuredFiles measuredFiles = measureFiles(resultRoot);
            FileMetrics files = measuredFiles.metrics();
            if (synthetic && files.documentCount() < loaded.corpus().stats().sourceCount()) {
                throw new AssertionError(
                        "10× 语料出现文档 ID 覆盖：来源=" + loaded.corpus().stats().sourceCount()
                                + "，文档=" + files.documentCount()
                );
            }
            SearchMetrics search = benchmarkSearch(
                    KnowledgeDatabase.path(resultRoot),
                    measuredFiles.documents(),
                    language,
                    searchBudgetMs,
                    warmupSamples,
                    searchSamples
            );
            if (!search.queryCasesPassed()) {
                throw new AssertionError(
                        "搜索正确性基准失败：" + scenario + "/" + language.preferredLocale()
                );
            }
            BuildMetrics build = new BuildMetrics(
                    millis(loaded.loadNanos()),
                    millis(coldNanos),
                    millis(incrementalNanos),
                    millis(changedNanos),
                    millis(reloadNanos),
                    cold.report().updatedCount(),
                    incremental.report().reusedCount(),
                    changed.report().updatedCount(),
                    cold.report().warnings().size()
            );

            JarCorpusLoader.CorpusStats stats = loaded.corpus().stats();
            return new ScenarioReport(
                    scenario,
                    language.preferredLocale(),
                    synthetic,
                    stats,
                    build,
                    files,
                    search
            );
        } finally {
            deleteTree(temporaryConfig);
        }
    }

    private static LocalGuideScanner.ScanResult scanResult(
            List<ScannedResource> resources,
            JarCorpusLoader.CorpusStats stats
    ) {
        return new LocalGuideScanner.ScanResult(resources, stats.warnings());
    }

    private static JarCorpusLoader.LoadedCorpus scaleCorpus(
            JarCorpusLoader.LoadedCorpus source,
            int factor
    ) {
        List<ScannedResource> scaled = new ArrayList<>(source.resources().size() * factor);
        for (int copy = 0; copy < factor; copy++) {
            for (ScannedResource resource : source.resources()) {
                if (copy == 0) {
                    scaled.add(resource);
                    continue;
                }
                String modId = "benchmark_" + copy + "_" + resource.modId();
                scaled.add(new ScannedResource(
                        modId,
                        "Benchmark " + resource.modName(),
                        resource.version(),
                        rewriteNamespace(resource.path(), resource.modId(), modId),
                        resource.sourceType(),
                        resource.sourceType().endsWith("markdown")
                                ? stripFrontMatter(resource.content())
                                : resource.content(),
                        resource.fingerprint() + "-benchmark-" + copy,
                        rewriteTranslations(resource.translations(), resource.modId(), modId)
                ));
            }
        }

        JarCorpusLoader.CorpusStats original = source.stats();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        original.sourceTypeCounts().forEach((type, count) -> typeCounts.put(type, count * factor));
        Set<String> syntheticMods = new LinkedHashSet<>();
        for (int copy = 0; copy < factor; copy++) {
            for (ScannedResource resource : source.resources()) {
                syntheticMods.add(copy == 0
                        ? resource.modId()
                        : "benchmark_" + copy + "_" + resource.modId());
            }
        }
        JarCorpusLoader.CorpusStats scaledStats = new JarCorpusLoader.CorpusStats(
                original.jarCount(),
                original.resourceRootCount(),
                original.declaredModCount() * factor,
                syntheticMods.size(),
                original.guideContainerCount(),
                original.dependencyOnlyContainerCount(),
                original.candidateCount() * factor,
                scaled.size(),
                0,
                original.languageFileCount(),
                original.skippedLocalePageCount() * factor,
                typeCounts,
                original.dependencyOnlyContainers(),
                original.dependencyOnlyMods(),
                original.missingRequiredDependencies(),
                original.warnings()
        );
        return new JarCorpusLoader.LoadedCorpus(List.copyOf(scaled), scaledStats);
    }

    private static String rewriteNamespace(String path, String oldModId, String newModId) {
        String prefixAssets = "assets/" + oldModId + "/";
        String prefixData = "data/" + oldModId + "/";
        if (path.startsWith(prefixAssets)) {
            return "assets/" + newModId + "/" + path.substring(prefixAssets.length());
        }
        if (path.startsWith(prefixData)) {
            return "data/" + newModId + "/" + path.substring(prefixData.length());
        }
        return "assets/" + newModId + "/guides/" + path.replace('/', '_');
    }

    private static Map<String, String> rewriteTranslations(
            Map<String, String> source,
            String oldModId,
            String newModId
    ) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        String oldPrefix = "." + oldModId + ".";
        String newPrefix = "." + newModId + ".";
        source.forEach((key, value) -> result.put(
                key.contains(oldPrefix) ? key.replace(oldPrefix, newPrefix) : key,
                value
        ));
        return Map.copyOf(result);
    }

    private static List<ScannedResource> changeOneFingerprint(List<ScannedResource> sources) {
        if (sources.isEmpty()) {
            return sources;
        }
        List<ScannedResource> changed = new ArrayList<>(sources);
        ScannedResource original = sources.get(0);
        changed.set(0, new ScannedResource(
                original.modId(),
                original.modName(),
                original.version(),
                original.path(),
                original.sourceType(),
                original.content(),
                original.fingerprint() + "-changed",
                original.translations()
        ));
        return List.copyOf(changed);
    }

    private static SearchMetrics benchmarkSearch(
            Path databasePath,
            List<ManifestDocument> documents,
            JarCorpusLoader.LanguageMode language,
            long budgetMs,
            int warmupSamples,
            int searchSamples
    ) {
        List<QueryCase> cases = queryCases(documents, language);
        List<QueryMetrics> metrics = new ArrayList<>();
        List<Long> allHotSamples = new ArrayList<>();
        List<Long> allColdSamples = new ArrayList<>();
        SearchLanguage searchLanguage = language == JarCorpusLoader.LanguageMode.ZH_CN
                ? SearchLanguage.ZH_CN
                : SearchLanguage.EN_US;
        try (KnowledgeDatabase.Reader hotReader = KnowledgeDatabase.openReader(databasePath)) {
            for (QueryCase queryCase : cases) {
                SearchQuery searchQuery = SearchQuery.of(queryCase.query());
                for (int index = 0; index < warmupSamples; index++) {
                    hotReader.search(searchQuery, searchLanguage, Map.of());
                }
                long[] hotSamples = new long[searchSamples];
                long[] coldSamples = new long[searchSamples];
                SearchResponse last = null;
                SearchResponse lastCold = null;
                for (int index = 0; index < searchSamples; index++) {
                    long hotStart = System.nanoTime();
                    last = hotReader.search(searchQuery, searchLanguage, Map.of());
                    hotSamples[index] = System.nanoTime() - hotStart;
                    allHotSamples.add(hotSamples[index]);

                    long coldStart = System.nanoTime();
                    try (KnowledgeDatabase.Reader coldReader = KnowledgeDatabase.openReader(databasePath)) {
                        lastCold = coldReader.search(searchQuery, searchLanguage, Map.of());
                    } catch (SQLException exception) {
                        throw new IllegalStateException("冷查询打开 SQLite 失败", exception);
                    }
                    coldSamples[index] = System.nanoTime() - coldStart;
                    allColdSamples.add(coldSamples[index]);
                }
                Arrays.sort(hotSamples);
                Arrays.sort(coldSamples);
                boolean passed = queryCase.mustMatch()
                        ? last != null && last.status() == SearchStatus.READY && last.hasResults()
                        : last != null && last.status() == SearchStatus.NO_MATCH;
                boolean coldPassed = queryCase.mustMatch()
                        ? lastCold != null && lastCold.status() == SearchStatus.READY && lastCold.hasResults()
                        : lastCold != null && lastCold.status() == SearchStatus.NO_MATCH;
                metrics.add(new QueryMetrics(
                        queryCase.name(),
                        queryCase.query(),
                        queryCase.mustMatch(),
                        passed && coldPassed,
                        last == null ? SearchStatus.INDEX_ERROR : last.status(),
                        last == null ? 0 : last.results().size(),
                        millis(percentile(hotSamples, 50)),
                        millis(percentile(hotSamples, 95)),
                        millis(percentile(hotSamples, 99)),
                        millis(percentile(coldSamples, 50)),
                        millis(percentile(coldSamples, 95)),
                        millis(percentile(coldSamples, 99))
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("打开 SQLite 基准连接失败", exception);
        }
        long[] hot = allHotSamples.stream().mapToLong(Long::longValue).toArray();
        long[] cold = allColdSamples.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(hot);
        Arrays.sort(cold);
        double hotP95 = millis(percentile(hot, 95));
        double coldP95 = millis(percentile(cold, 95));
        boolean casesPassed = metrics.stream().allMatch(QueryMetrics::passed);
        return new SearchMetrics(
                budgetMs,
                millis(percentile(hot, 50)),
                hotP95,
                millis(percentile(hot, 99)),
                millis(percentile(cold, 50)),
                coldP95,
                millis(percentile(cold, 99)),
                casesPassed,
                hotP95 <= budgetMs,
                List.copyOf(metrics)
        );
    }

    private static List<QueryCase> queryCases(
            List<ManifestDocument> documents,
            JarCorpusLoader.LanguageMode language
    ) {
        String exactId = documents.isEmpty() ? "ae2:controller" : documents.get(0).id();
        String localized = language == JarCorpusLoader.LanguageMode.ZH_CN
                ? firstKeywordContainingCjk(documents, "破坏面板")
                : firstEnglishPhrase(documents, "annihilation plane");
        String english = firstKeyword(documents, "autocrafting");
        String partial = firstKeyword(documents, "controller");
        String multi = language == JarCorpusLoader.LanguageMode.ZH_CN
                ? firstKeywordContainingCjk(documents, "网络控制器")
                : firstEnglishPhrase(documents, "network controller");
        return List.of(
                new QueryCase("exact-id", exactId, true),
                new QueryCase("localized-name", localized, true),
                new QueryCase("english-phrase", english, true),
                new QueryCase("partial-term", partial, true),
                new QueryCase("multi-term", multi, true),
                new QueryCase("no-match", "zzzxqvmodpediabenchmark9f3b", false)
        );
    }

    private static String firstKeywordContainingCjk(List<ManifestDocument> documents, String fallback) {
        if (containsText(documents, fallback)) {
            return fallback;
        }
        return documents.stream()
                .flatMap(document -> document.keywords().stream())
                .filter(value -> value != null && CJK.matcher(value).find() && value.length() >= 2)
                .findFirst()
                .orElse(fallback);
    }

    private static String firstEnglishPhrase(List<ManifestDocument> documents, String fallback) {
        if (containsText(documents, fallback)) {
            return fallback;
        }
        return documents.stream()
                .flatMap(document -> List.of(document.title()).stream())
                .filter(value -> value.contains(" ") && value.matches(".*[A-Za-z].*"))
                .findFirst()
                .orElse(fallback);
    }

    private static String firstKeyword(List<ManifestDocument> documents, String fallback) {
        if (containsText(documents, fallback)) {
            return fallback;
        }
        return documents.stream()
                .flatMap(document -> document.keywords().stream())
                .filter(value -> value != null && value.length() >= 4 && value.matches(".*[A-Za-z].*"))
                .findFirst()
                .orElse(fallback);
    }

    private static boolean containsText(List<ManifestDocument> documents, String expected) {
        String normalized = expected.toLowerCase(Locale.ROOT);
        return documents.stream().anyMatch(document ->
                document.title().toLowerCase(Locale.ROOT).contains(normalized)
                        || document.markdown().toLowerCase(Locale.ROOT).contains(normalized)
                        || document.keywords().stream()
                        .anyMatch(keyword -> keyword.toLowerCase(Locale.ROOT).contains(normalized))
        );
    }

    private static String stripFrontMatter(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            return normalized;
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return normalized;
        }
        int bodyStart = end + "\n---".length();
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return normalized.substring(bodyStart).trim();
    }

    private static FtsComparison compareFtsStorage(
            LoadedScenario loaded,
            Path existingKnowledgeRoot,
            long searchBudgetMs,
            int warmupSamples,
            int searchSamples
    ) throws Exception {
        List<FtsVariantReport> variants = new ArrayList<>();
        for (String storage : List.of("contentful", "external-content")) {
            Path temporaryConfig = Files.createTempDirectory("modpedia-fts-comparison-");
            try {
                copyCustomDocuments(
                        existingKnowledgeRoot.resolve("custom"),
                        temporaryConfig.resolve("modpedia/knowledge/custom")
                );
                KnowledgeCompiler compiler = new KnowledgeCompiler();
                long buildStart = System.nanoTime();
                KnowledgeCompiler.CompileResult build = withProperties(
                        Map.of(
                                "modpedia.benchmark.fts.storage", storage,
                                "modpedia.benchmark.fts.optimize", "false"
                        ),
                        () -> compiler.compile(
                                temporaryConfig,
                                scanResult(loaded.corpus().resources(), loaded.corpus().stats()),
                                true
                        )
                );
                double coldBuildMs = millis(System.nanoTime() - buildStart);
                Path database = KnowledgeDatabase.path(build.knowledgeRoot());
                MeasuredFiles measured = measureFiles(build.knowledgeRoot());
                List<QueryCase> cases = queryCases(
                        measured.documents(),
                        JarCorpusLoader.LanguageMode.ZH_CN
                );
                SearchMetrics beforeSearch = withProperties(
                        Map.of("modpedia.benchmark.fts.storage", storage),
                        () -> benchmarkSearch(
                                database,
                                measured.documents(),
                                JarCorpusLoader.LanguageMode.ZH_CN,
                                searchBudgetMs,
                                warmupSamples,
                                searchSamples
                        )
                );
                SearchQuery planQuery = cases.isEmpty()
                        ? SearchQuery.of("ae2:controller")
                        : SearchQuery.of(cases.get(0).query());
                KnowledgeDatabase.OptimizationStats optimization = withProperties(
                        Map.of(
                                "modpedia.benchmark.fts.storage", storage,
                                "modpedia.benchmark.fts.optimize", "true"
                        ),
                        () -> KnowledgeDatabase.optimize(
                                database,
                                planQuery,
                                SearchLanguage.ZH_CN,
                                Map.of(),
                                true
                        )
                );
                SearchMetrics afterSearch = withProperties(
                        Map.of("modpedia.benchmark.fts.storage", storage),
                        () -> benchmarkSearch(
                                database,
                                measured.documents(),
                                JarCorpusLoader.LanguageMode.ZH_CN,
                                searchBudgetMs,
                                warmupSamples,
                                searchSamples
                        )
                );
                variants.add(new FtsVariantReport(
                        storage,
                        coldBuildMs,
                        optimization.before(),
                        optimization.after(),
                        optimization,
                        beforeSearch,
                        afterSearch
                ));
            } finally {
                deleteTree(temporaryConfig);
            }
        }
        return new FtsComparison(
                "expanded",
                JarCorpusLoader.LanguageMode.ZH_CN.preferredLocale(),
                List.copyOf(variants)
        );
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T withProperties(
            Map<String, String> values,
            ThrowingSupplier<T> supplier
    ) throws Exception {
        Map<String, String> previous = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            return supplier.get();
        } finally {
            values.keySet().forEach(key -> {
                String old = previous.get(key);
                if (old == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, old);
                }
            });
        }
    }

    private static MeasuredFiles measureFiles(Path knowledgeRoot) throws IOException, SQLException {
        Path manifestPath = knowledgeRoot.resolve("manifest.json");
        Path keywordPath = knowledgeRoot.resolve("keyword-index.json");
        Path statePath = knowledgeRoot.resolve("state.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<ManifestDocument> documents = new ArrayList<>();
        JsonElement documentElement = manifest.get("documents");
        if (documentElement != null && documentElement.isJsonArray()) {
            for (JsonElement element : documentElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject value = element.getAsJsonObject();
                String relativePath = string(value, "path");
                Path documentPath = knowledgeRoot.resolve(relativePath).normalize();
                String markdown = Files.isRegularFile(documentPath)
                        ? Files.readString(documentPath, StandardCharsets.UTF_8)
                        : "";
                documents.add(new ManifestDocument(
                        string(value, "id"),
                        string(value, "title"),
                        stringList(value.get("keywords")),
                        relativePath,
                        markdown
                ));
            }
        }

        JsonObject index = JsonParser.parseString(Files.readString(keywordPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        int postings = 0;
        for (JsonElement value : index.entrySet().stream().map(Map.Entry::getValue).toList()) {
            if (value.isJsonArray()) {
                postings += value.getAsJsonArray().size();
            }
        }

        long markdownBytes = 0;
        try (var paths = Files.walk(knowledgeRoot)) {
            markdownBytes = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .mapToLong(path -> size(path))
                    .sum();
        }
        int segments = documents.stream()
                .mapToInt(document -> MarkdownSegmenter.split(document.markdown()).size())
                .sum();
        FileMetrics metrics = new FileMetrics(
                documents.size(),
                index.size(),
                postings,
                segments,
                markdownBytes,
                size(manifestPath),
                size(keywordPath),
                size(statePath),
                KnowledgeDatabase.inspect(KnowledgeDatabase.path(knowledgeRoot))
        );
        return new MeasuredFiles(metrics, List.copyOf(documents));
    }

    private static void copyCustomDocuments(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static List<Path> mergeJars(List<Path> first, List<Path> second) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        first.forEach(path -> result.add(path.toAbsolutePath().normalize()));
        second.forEach(path -> result.add(path.toAbsolutePath().normalize()));
        return result.stream().sorted().toList();
    }

    private static Path propertyPath(String property, Path fallback) {
        String value = System.getProperty(property);
        return value == null || value.isBlank()
                ? fallback.toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = Math.max(0, Math.min(sorted.length - 1,
                (int) Math.ceil(sorted.length * percentile / 100.0) - 1));
        return sorted[index];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static List<String> stringList(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                result.add(item.getAsString());
            }
        }
        return List.copyOf(result);
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

    private static String toMarkdown(BenchmarkReport report) {
        StringBuilder output = new StringBuilder();
        output.append("# ModPedia 知识库规模基准\n\n")
                .append("生成时间：").append(report.generatedAt()).append("\n\n")
                .append("搜索预算：热查询 p95 ≤ ").append(report.searchBudgetMs()).append(" ms\n\n")
                .append("冷查询定义：每次新建并关闭 JDBC Reader；不宣称清空操作系统页缓存。\n\n")
                .append("本轮未引入 prefix index、trigram、detail=column/none、WAL 或向量库。\n\n")
                .append("| 场景 | 语言 | JAR | 来源 | 文档 | 关键词 | 段落 | DB KiB | FTS KiB | FTS 正文 KiB | 装载 ms | 冷构建 ms | 增量 ms | 变更 ms | reload ms | 热 p95 ms | 冷 p95 ms | 预算 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (ScenarioReport scenario : report.scenarios()) {
            output.append('|').append(scenario.scenario())
                    .append('|').append(scenario.language())
                    .append('|').append(scenario.corpus().jarCount())
                    .append('|').append(scenario.corpus().sourceCount())
                    .append('|').append(scenario.files().documentCount())
                    .append('|').append(scenario.files().keywordCount())
                    .append('|').append(scenario.files().segmentCount())
                    .append('|').append(formatBytes(scenario.files().database().databaseBytes()))
                    .append('|').append(formatBytes(scenario.files().database().ftsBytes()))
                    .append('|').append(formatBytes(scenario.files().database().ftsContentBytes()))
                    .append('|').append(format(scenario.build().sourceLoadMs()))
                    .append('|').append(format(scenario.build().coldBuildMs()))
                    .append('|').append(format(scenario.build().incrementalBuildMs()))
                    .append('|').append(format(scenario.build().changedBuildMs()))
                    .append('|').append(format(scenario.build().reloadMs()))
                    .append('|').append(format(scenario.search().overallP95Ms()))
                    .append('|').append(format(scenario.search().coldOverallP95Ms()))
                    .append('|').append(scenario.search().withinBudget() ? "PASS" : "OVER")
                    .append('|').append('\n');
        }
        output.append("\n## 依赖型 JAR 与资源统计\n\n");
        for (ScenarioReport scenario : report.scenarios()) {
            output.append("### ").append(scenario.scenario()).append(" / ")
                    .append(scenario.language()).append("\n\n")
                    .append("- 识别来源：").append(scenario.corpus().sourceCount()).append('\n')
                    .append("- 文档/关键词/posting/段落：")
                    .append(scenario.files().documentCount()).append("/")
                    .append(scenario.files().keywordCount()).append("/")
                    .append(scenario.files().postingCount()).append("/")
                    .append(scenario.files().segmentCount()).append('\n')
                    .append("- Markdown/manifest/keyword-index/state 字节：")
                    .append(scenario.files().markdownBytes()).append("/")
                    .append(scenario.files().manifestBytes()).append("/")
                    .append(scenario.files().keywordIndexBytes()).append("/")
                    .append(scenario.files().stateBytes()).append('\n')
                    .append("- SQLite/FTS/FTS 正文/FTS 索引 字节：")
                    .append(scenario.files().database().databaseBytes()).append("/")
                    .append(scenario.files().database().ftsBytes()).append("/")
                    .append(scenario.files().database().ftsContentBytes()).append("/")
                    .append(scenario.files().database().ftsIndexBytes()).append('\n')
                    .append("- FTS 存储：").append(scenario.files().database().ftsStorage()).append('\n')
                    .append("- 语言文件：").append(scenario.corpus().languageFileCount()).append('\n')
                    .append("- 本地化页面回退/跳过：").append(scenario.corpus().skippedLocalePageCount()).append('\n')
                    .append("- 无手册资源的依赖型 JAR：")
                    .append(joinOrNone(scenario.corpus().dependencyOnlyContainers())).append('\n')
                    .append("- 依赖型模组 ID：")
                    .append(joinOrNone(scenario.corpus().dependencyOnlyMods())).append('\n')
                    .append("- 缺少的必需依赖（仅用于报告，不阻断离线基准）：")
                    .append(joinOrNone(scenario.corpus().missingRequiredDependencies())).append("\n\n")
                    .append("查询结果：\n\n")
                    .append("| 查询 | 通过 | 状态 | 热 p50 | 热 p95 | 热 p99 | 冷 p50 | 冷 p95 | 冷 p99 | 结果数 |\n")
                    .append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
            for (QueryMetrics query : scenario.search().queries()) {
                output.append('|').append(query.name())
                        .append('|').append(query.passed() ? "PASS" : "FAIL")
                        .append('|').append(query.status())
                        .append('|').append(format(query.p50Ms()))
                        .append('|').append(format(query.p95Ms()))
                        .append('|').append(format(query.p99Ms()))
                        .append('|').append(format(query.coldP50Ms()))
                        .append('|').append(format(query.coldP95Ms()))
                        .append('|').append(format(query.coldP99Ms()))
                        .append('|').append(query.resultCount())
                        .append('|').append('\n');
            }
            output.append('\n');
        }
        output.append("\n## FTS5 存储与 optimize A/B\n\n")
                .append("当前生产默认使用 external-content；contentful 仅用于同一语料的基准对照。\n\n")
                .append("| 存储 | 冷构建 ms | 优化前 DB KiB | 优化后 DB KiB | 优化前 FTS KiB | 优化后 FTS KiB | 优化前 FTS 正文 KiB | 优化后 FTS 正文 KiB | 优化前热 p95 | 优化后热 p95 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (FtsVariantReport variant : report.ftsComparison().variants()) {
            output.append('|').append(variant.storage())
                    .append('|').append(format(variant.coldBuildMs()))
                    .append('|').append(formatBytes(variant.beforeOptimize().databaseBytes()))
                    .append('|').append(formatBytes(variant.afterOptimize().databaseBytes()))
                    .append('|').append(formatBytes(variant.beforeOptimize().ftsBytes()))
                    .append('|').append(formatBytes(variant.afterOptimize().ftsBytes()))
                    .append('|').append(formatBytes(variant.beforeOptimize().ftsContentBytes()))
                    .append('|').append(formatBytes(variant.afterOptimize().ftsContentBytes()))
                    .append('|').append(format(variant.beforeSearch().overallP95Ms()))
                    .append('|').append(format(variant.afterSearch().overallP95Ms()))
                    .append('|').append('\n');
        }
        output.append('\n');
        for (FtsVariantReport variant : report.ftsComparison().variants()) {
            output.append("### ").append(variant.storage()).append(" optimize 细节\n\n")
                    .append("- PRAGMA optimize：").append(format(variant.optimization().pragmaOptimizeMs())).append(" ms\n")
                    .append("- FTS5 optimize/merge：").append(format(variant.optimization().ftsOptimizeMs())).append(" ms\n")
                    .append("- 优化前查询计划：").append(String.join("；", variant.optimization().planBefore())).append("\n")
                    .append("- 优化后查询计划：").append(String.join("；", variant.optimization().planAfter())).append("\n\n");
        }
        return output.toString();
    }

    private static String joinOrNone(List<String> values) {
        return values.isEmpty() ? "无" : String.join(", ", values);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1024.0);
    }

    private record LoadedScenario(JarCorpusLoader.LoadedCorpus corpus, long loadNanos) {
    }

    private record BenchmarkReport(
            String generatedAt,
            String projectRoot,
            long searchBudgetMs,
            int scaleFactor,
            List<ScenarioReport> scenarios,
            FtsComparison ftsComparison
    ) {
    }

    private record ScenarioReport(
            String scenario,
            String language,
            boolean synthetic,
            JarCorpusLoader.CorpusStats corpus,
            BuildMetrics build,
            FileMetrics files,
            SearchMetrics search
    ) {
    }

    private record BuildMetrics(
            double sourceLoadMs,
            double coldBuildMs,
            double incrementalBuildMs,
            double changedBuildMs,
            double reloadMs,
            int coldUpdatedCount,
            int incrementalReusedCount,
            int changedUpdatedCount,
            int coldWarningCount
    ) {
    }

    private record FileMetrics(
            int documentCount,
            int keywordCount,
            int postingCount,
            int segmentCount,
            long markdownBytes,
            long manifestBytes,
            long keywordIndexBytes,
            long stateBytes,
            KnowledgeDatabase.DatabaseStats database
    ) {
    }

    private record MeasuredFiles(FileMetrics metrics, List<ManifestDocument> documents) {
    }

    private record SearchMetrics(
            long budgetMs,
            double overallP50Ms,
            double overallP95Ms,
            double overallP99Ms,
            double coldOverallP50Ms,
            double coldOverallP95Ms,
            double coldOverallP99Ms,
            boolean queryCasesPassed,
            boolean withinBudget,
            List<QueryMetrics> queries
    ) {
    }

    private record QueryMetrics(
            String name,
            String query,
            boolean mustMatch,
            boolean passed,
            SearchStatus status,
            int resultCount,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double coldP50Ms,
            double coldP95Ms,
            double coldP99Ms
    ) {
    }

    private record FtsComparison(
            String scenario,
            String language,
            List<FtsVariantReport> variants
    ) {
    }

    private record FtsVariantReport(
            String storage,
            double coldBuildMs,
            KnowledgeDatabase.DatabaseStats beforeOptimize,
            KnowledgeDatabase.DatabaseStats afterOptimize,
            KnowledgeDatabase.OptimizationStats optimization,
            SearchMetrics beforeSearch,
            SearchMetrics afterSearch
    ) {
    }

    private record QueryCase(String name, String query, boolean mustMatch) {
    }

    private record ManifestDocument(
            String id,
            String title,
            List<String> keywords,
            String path,
            String markdown
    ) {
    }
}
