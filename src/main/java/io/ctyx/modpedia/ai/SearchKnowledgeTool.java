package io.ctyx.modpedia.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.ctyx.modpedia.client.SourceReference;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.search.SearchQuery;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** LangChain4j 工具：让模型按证据完整度继续查询本地知识库。 */
public final class SearchKnowledgeTool {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> FOCUSES = Set.of(
            "identify", "steps", "recipe", "prerequisite", "troubleshooting", "related"
    );

    private final RetrievalService retrievalService;
    private final SearchLanguage defaultLanguage;
    private final int maxResults;
    private final int maxContextChars;
    private final AtomicInteger roundCounter;
    private final Consumer<SearchTrace> traceSink;
    private final Set<String> seenQueries = new HashSet<>();
    private final Set<String> seenDocuments = new LinkedHashSet<>();

    public SearchKnowledgeTool(
            RetrievalService retrievalService,
            SearchLanguage defaultLanguage,
            int maxResults,
            int maxContextChars,
            int round,
            Consumer<SearchTrace> traceSink
    ) {
        this.retrievalService = retrievalService;
        this.defaultLanguage = defaultLanguage == null || defaultLanguage == SearchLanguage.AUTO
                ? SearchLanguage.ZH_CN
                : defaultLanguage;
        this.maxResults = Math.max(1, Math.min(20, maxResults));
        this.maxContextChars = Math.max(4_000, maxContextChars);
        this.roundCounter = new AtomicInteger(Math.max(1, round));
        this.traceSink = traceSink == null ? ignored -> { } : traceSink;
    }

    @Tool(
            name = "search_knowledge",
            value = "搜索本地模组知识库并返回完整 Markdown 段落。先使用精确模组、物品、方块或机器 ID；" +
                    "如果结果只覆盖问题的一部分，请改写 query 并继续调用。focus 用于指定当前缺失的资料类型。"
    )
    public String search(
            @P(name = "query", value = "要检索的模组问题、ID、名称或补充查询词") String query,
            @P(name = "language", value = "auto、zh_cn、en_us 或 neutral", defaultValue = "auto", required = false)
            String language,
            @P(name = "limit", value = "结果数量，范围 1 到 20", defaultValue = "8", required = false)
            Integer limit,
            @P(name = "focus", value = "identify、steps、recipe、prerequisite、troubleshooting 或 related",
                    defaultValue = "identify", required = false)
            String focus,
            @P(name = "exclude_document_ids", value = "已经看过的文档 ID 数组", defaultValue = "[]", required = false)
            List<String> excludeDocumentIds
    ) {
        String normalizedQuery = query == null ? "" : query.strip();
        String normalizedLanguage = language == null || language.isBlank() ? "auto" : language.strip().toLowerCase(Locale.ROOT);
        String normalizedFocus = normalizeFocus(focus);
        int requestedLimit = limit == null ? Math.min(8, maxResults) : Math.max(1, Math.min(maxResults, limit));
        Set<String> excluded = excludeDocumentIds == null
                ? Set.of()
                : excludeDocumentIds.stream().filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.toSet());

        JsonObject output = new JsonObject();
        int currentRound = roundCounter.getAndIncrement();
        output.addProperty("query", normalizedQuery);
        output.addProperty("language", normalizedLanguage);
        output.addProperty("focus", normalizedFocus);
        output.addProperty("round", currentRound);

        if (normalizedQuery.isBlank()) {
            return finish(output, currentRound, SearchStatus.EMPTY_QUERY, List.of(), false, "查询为空", normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        String queryKey = normalizedLanguage + "|" + normalizedQuery.toLowerCase(Locale.ROOT);
        if (!seenQueries.add(queryKey)) {
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "重复查询，请改写关键词或针对缺失的资料类型继续搜索", normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        SearchLanguage requestedLanguage = parseLanguage(normalizedLanguage);
        SearchResponse response = retrievalService.search(new SearchQuery(
                normalizedQuery,
                SearchQuery.MAX_LIMIT,
                requestedLanguage
        ));
        List<SearchResult> candidates = new ArrayList<>(response.results());

        // AUTO 查询在主语言无结果时尝试另一语言，保留双语切换能力。
        if (requestedLanguage == SearchLanguage.AUTO && candidates.isEmpty()) {
            SearchLanguage alternate = defaultLanguage == SearchLanguage.ZH_CN
                    ? SearchLanguage.EN_US
                    : SearchLanguage.ZH_CN;
            candidates.addAll(retrievalService.search(new SearchQuery(
                    normalizedQuery,
                    SearchQuery.MAX_LIMIT,
                    alternate
            )).results());
        }

        List<SearchResult> fresh = candidates.stream()
                .filter(result -> !excluded.contains(result.documentId()))
                .filter(result -> !seenDocuments.contains(result.documentId()))
                .sorted(Comparator.comparingInt(SearchResult::score).reversed()
                        .thenComparing(SearchResult::documentId))
                .toList();
        boolean hasMore = candidates.size() > requestedLimit;
        List<SearchResult> selected = fresh.subList(0, Math.min(requestedLimit, fresh.size()));
        selected.forEach(result -> seenDocuments.add(result.documentId()));
        return finish(output, currentRound, selected.isEmpty() ? response.status() : SearchStatus.READY, selected, hasMore,
                fresh.isEmpty() ? "当前查询没有新增来源，请改写查询或缩小到具体 ID" : "",
                normalizedQuery, normalizedLanguage, normalizedFocus);
    }

    private String finish(
            JsonObject output,
            int round,
            SearchStatus status,
            List<SearchResult> results,
            boolean hasMore,
            String hint,
            String query,
            String language,
            String focus
    ) {
        JsonArray documents = new JsonArray();
        List<SourceReference> sources = new ArrayList<>();
        int usedChars = 0;
        for (SearchResult result : results) {
            int nextChars = result.segmentMarkdown().length();
            if (usedChars > 0 && usedChars + nextChars > maxContextChars) {
                break;
            }
            usedChars += nextChars;
            JsonObject document = new JsonObject();
            document.addProperty("document_id", result.documentId());
            document.addProperty("title", result.title());
            document.addProperty("source_mod", result.sourceMod());
            document.addProperty("source_type", result.sourceType());
            document.addProperty("category", result.category());
            document.addProperty("source_version", result.sourceVersion());
            document.addProperty("source_path", result.sourcePath());
            document.addProperty("heading_path", result.headingPath());
            document.addProperty("segment_markdown", result.segmentMarkdown());
            document.addProperty("score", result.score());
            document.add("matched_terms", JSON.toJsonTree(result.matchedTerms()));
            documents.add(document);
            sources.add(new SourceReference(
                    result.documentId(),
                    result.title().isBlank() ? result.documentId() : result.title(),
                    result.sourceMod(),
                    result.sourcePath()
            ));
        }
        output.addProperty("status", status.name());
        output.addProperty("returned_count", documents.size());
        output.addProperty("new_source_count", sources.size());
        output.addProperty("has_more", hasMore);
        output.addProperty("context_chars", usedChars);
        output.add("results", documents);
        if (!hint.isBlank()) {
            output.addProperty("hint", hint);
        }
        traceSink.accept(new SearchTrace(
                query,
                language,
                focus,
                round,
                status.name(),
                hasMore,
                sources,
                System.currentTimeMillis()
        ));
        return JSON.toJson(output);
    }

    private static SearchLanguage parseLanguage(String value) {
        return switch (value.replace('-', '_')) {
            case "zh", "zh_cn" -> SearchLanguage.ZH_CN;
            case "en", "en_us" -> SearchLanguage.EN_US;
            case "neutral" -> SearchLanguage.NEUTRAL;
            default -> SearchLanguage.AUTO;
        };
    }

    private static String normalizeFocus(String value) {
        String normalized = value == null ? "identify" : value.strip().toLowerCase(Locale.ROOT);
        return FOCUSES.contains(normalized) ? normalized : "identify";
    }
}
