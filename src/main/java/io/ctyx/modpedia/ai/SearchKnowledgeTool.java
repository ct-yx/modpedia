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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** LangChain4j 工具：让模型按证据完整度继续查询本地知识库。 */
public final class SearchKnowledgeTool {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}\\u3400-\\u9fff]+(?:[:_./-][\\p{L}\\p{N}\\u3400-\\u9fff]+)*"
    );
    private static final Set<String> FOCUSES = Set.of(
            "identify", "steps", "recipe", "prerequisite", "troubleshooting", "related"
    );
    private static final Map<String, List<String>> FOCUS_ALIASES = Map.of(
            "steps", List.of("步骤", "操作", "连接", "设置", "安装", "用法", "使用", "如何使用",
                    "step", "procedure", "connect", "connection", "configure", "configuration", "setup"),
            "recipe", List.of("配方", "合成", "制作", "材料", "recipe", "craft", "crafting", "materials"),
            "prerequisite", List.of("前置条件", "前置", "前提", "需要", "依赖", "条件",
                    "prerequisite", "prerequisites", "requirement", "requirements", "dependency"),
            "troubleshooting", List.of("故障", "错误", "问题", "排查", "修复", "漏气", "异常",
                    "troubleshooting", "error", "problem", "leak", "leakage", "fix"),
            "related", List.of("相关", "兼容", "联动", "区别", "related", "compatibility", "compatible"),
            "identify", List.of("识别", "名称", "是什么", "identify", "name", "what")
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is",
            "it", "of", "on", "or", "the", "to", "what", "where", "which", "with", "step", "steps",
            "procedure", "procedures", "setup", "prerequisite", "prerequisites", "requirement",
            "requirements", "usage", "instructions", "start", "begin", "using", "怎么", "如何", "什么",
            "哪个", "哪些", "怎么做", "如何使用", "使用方法", "用法", "可以", "需要", "这个", "那个",
            "设置", "前置", "前置条件", "前提", "条件", "步骤", "操作", "操作步骤", "防止", "模组",
            "minecraft", "mod", "mods"
    );

    private final RetrievalService retrievalService;
    private final SearchLanguage defaultLanguage;
    private final int maxResults;
    private final int maxContextChars;
    private final int maxRounds;
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
        this(retrievalService, defaultLanguage, maxResults, maxContextChars, round,
                Integer.MAX_VALUE, traceSink);
    }

    /** 创建带硬搜索轮数上限的工具；旧构造器保留给纯搜索测试和兼容调用方。 */
    public SearchKnowledgeTool(
            RetrievalService retrievalService,
            SearchLanguage defaultLanguage,
            int maxResults,
            int maxContextChars,
            int round,
            int maxRounds,
            Consumer<SearchTrace> traceSink
    ) {
        this.retrievalService = retrievalService;
        this.defaultLanguage = defaultLanguage == null || defaultLanguage == SearchLanguage.AUTO
                ? SearchLanguage.ZH_CN
                : defaultLanguage;
        this.maxResults = Math.max(1, Math.min(20, maxResults));
        this.maxContextChars = Math.max(4_000, maxContextChars);
        this.roundCounter = new AtomicInteger(Math.max(1, round));
        this.maxRounds = Math.max(1, Math.min(8, maxRounds));
        this.traceSink = traceSink == null ? ignored -> { } : traceSink;
    }

    @Tool(
            name = "search_knowledge",
            value = "搜索本地模组知识库并返回完整 Markdown 段落。可以直接使用玩家的游戏显示名称、" +
                    "自然语言描述、标签或内部 ID。首次或语言不确定时 language 使用 auto；" +
                    "focus 只能使用 identify、steps、recipe、prerequisite、troubleshooting、related，" +
                    "也会把中文自然语言 focus 归一化为上述值。如果结果只覆盖问题的一部分，请改写 query 并继续调用。"
    )
    public String search(
            @P(name = "query", value = "要检索的模组问题、游戏显示名称、自然语言描述、ID 或补充查询词") String query,
            @P(name = "language", value = "语言过滤器：auto（默认，自动合并当前语言和另一语言）、zh_cn、en_us 或 neutral",
                    defaultValue = "auto", required = false)
            String language,
            @P(name = "limit", value = "结果数量，范围 1 到 20", defaultValue = "8", required = false)
            Integer limit,
            @P(name = "focus", value = "identify、steps、recipe、prerequisite、troubleshooting 或 related；支持中文描述",
                    defaultValue = "identify", required = false)
            String focus,
            @P(name = "exclude_document_ids", value = "已经看过的文档 ID 数组", defaultValue = "[]", required = false)
            List<String> excludeDocumentIds
    ) {
        String normalizedQuery = query == null ? "" : query.strip();
        SearchLanguage requestedLanguage = parseLanguage(language);
        String normalizedLanguage = requestedLanguage.code();
        String normalizedFocus = normalizeFocus(focus);
        int requestedLimit = limit == null ? Math.min(8, maxResults) : Math.max(1, Math.min(maxResults, limit));
        Set<String> excluded = excludeDocumentIds == null
                ? Set.of()
                : excludeDocumentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SearchKnowledgeTool::documentKey)
                .collect(java.util.stream.Collectors.toSet());

        JsonObject output = new JsonObject();
        int currentRound = roundCounter.getAndIncrement();
        output.addProperty("query", normalizedQuery);
        output.addProperty("language", normalizedLanguage);
        output.addProperty("focus", normalizedFocus);
        output.addProperty("round", currentRound);

        if (currentRound > maxRounds) {
            output.addProperty("budget_exhausted", true);
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "已达到搜索预算，请根据已返回资料整理回答，并列出仍缺失的资料项。",
                    normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        if (normalizedQuery.isBlank()) {
            return finish(output, currentRound, SearchStatus.EMPTY_QUERY, List.of(), false, "查询为空", normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        String queryKey = normalizedLanguage + "|" + queryKey(normalizedQuery);
        if (!seenQueries.add(queryKey)) {
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "重复查询，请改写关键词或针对缺失的资料类型继续搜索", normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        List<SearchResponse> responses = searchLanguages(normalizedQuery, requestedLanguage);
        List<SearchResult> candidates = mergeCandidates(responses);
        candidates = keepEntityMatches(candidates, normalizedQuery);

        List<SearchResult> fresh = candidates.stream()
                .filter(result -> !excluded.contains(documentKey(result.documentId())))
                .filter(result -> !seenDocuments.contains(documentKey(result.documentId())))
                .sorted(focusComparator(normalizedFocus))
                .toList();
        List<SearchResult> selected = new ArrayList<>();
        int selectedChars = 0;
        for (SearchResult result : fresh) {
            if (selected.size() >= requestedLimit) {
                break;
            }
            int nextChars = result.segmentMarkdown().length();
            if (!selected.isEmpty() && selectedChars + nextChars > maxContextChars) {
                break;
            }
            selected.add(result);
            selectedChars += nextChars;
        }
        boolean hasMore = fresh.size() > selected.size();
        return finish(output, currentRound, selected.isEmpty() ? combinedStatus(responses) : SearchStatus.READY, selected, hasMore,
                fresh.isEmpty() ? "当前查询没有新增来源，请改写查询或缩小到具体名称、机器或步骤" : "",
                normalizedQuery, normalizedLanguage, normalizedFocus);
    }

    private List<SearchResponse> searchLanguages(
            String query,
            SearchLanguage requestedLanguage
    ) {
        List<SearchResponse> responses = new ArrayList<>();
        if (requestedLanguage == SearchLanguage.AUTO) {
            SearchLanguage primary = defaultLanguage == SearchLanguage.EN_US
                    ? SearchLanguage.EN_US
                    : SearchLanguage.ZH_CN;
            SearchLanguage alternate = primary == SearchLanguage.ZH_CN
                    ? SearchLanguage.EN_US
                    : SearchLanguage.ZH_CN;
            // AUTO 不再等到主语言完全无结果才切换；两种语言都查，再按文档和分数合并。
            responses.add(searchOne(query, primary));
            responses.add(searchOne(query, alternate));
        } else {
            responses.add(searchOne(query, requestedLanguage));
        }
        return responses;
    }

    private SearchResponse searchOne(String query, SearchLanguage language) {
        return retrievalService.search(new SearchQuery(
                query,
                SearchQuery.MAX_LIMIT,
                language
        ));
    }

    private static List<SearchResult> mergeCandidates(List<SearchResponse> responses) {
        Map<String, SearchResult> bestByDocument = new LinkedHashMap<>();
        for (SearchResponse response : responses) {
            for (SearchResult result : response.results()) {
                String key = documentKey(result.documentId());
                SearchResult previous = bestByDocument.get(key);
                if (previous == null || better(result, previous)) {
                    bestByDocument.put(key, result);
                }
            }
        }
        return new ArrayList<>(bestByDocument.values());
    }

    private static boolean better(SearchResult candidate, SearchResult previous) {
        int score = Integer.compare(candidate.score(), previous.score());
        if (score != 0) {
            return score > 0;
        }
        int path = candidate.sourcePath().compareTo(previous.sourcePath());
        if (path != 0) {
            return path < 0;
        }
        return candidate.documentId().compareTo(previous.documentId()) < 0;
    }

    /**
     * 自然语言问题中常见的通用词不能压过真正的模组/物品实体。
     * 有明确实体时只保留至少命中一个实体锚点的结果；若索引没有任何锚点命中则返回空，
     * 避免把通用页面误判成当前实体的答案。
     */
    private static List<SearchResult> keepEntityMatches(List<SearchResult> candidates, String query) {
        List<String> anchors = queryAnchors(query);
        if (anchors.isEmpty()) {
            return candidates;
        }
        // 查询开头的实体通常是玩家真正要问的对象。只要索引中存在它，就不让
        // “防止漏气/步骤/设置”这类后续描述把示例文档抬到实体手册之前。
        List<SearchResult> primaryMatches = candidates.stream()
                .filter(result -> matchesAnchor(result, List.of(anchors.get(0))))
                .toList();
        if (!primaryMatches.isEmpty()) {
            return primaryMatches;
        }
        List<SearchResult> filtered = candidates.stream()
                .filter(result -> matchesAnchor(result, anchors))
                .toList();
        // 查询包含明确实体但当前语言没有命中该实体时，返回空结果而不是把通用页面
        // 当成答案。模型会看到 NO_MATCH，再用英文名称、ID 或其他实体改写查询。
        return filtered;
    }

    private static List<String> queryAnchors(String query) {
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(rawNormalize(query));
        while (matcher.find()) {
            String token = matcher.group();
            if (!GENERIC_QUERY_TERMS.contains(token)
                    && (token.length() >= 3 || token.matches("[a-z0-9_:/.-]{4,}"))) {
                anchors.add(token);
            }
        }
        return List.copyOf(anchors);
    }

    private static boolean matchesAnchor(SearchResult result, List<String> anchors) {
        String haystack = normalize(
                result.documentId() + " "
                        + result.title() + " "
                        + result.sourceMod() + " "
                        + result.sourcePath() + " "
                        + result.headingPath() + " "
                        + result.segmentMarkdown() + " "
                        + String.join(" ", result.matchedTerms())
        );
        String compactHaystack = haystack.replace(" ", "");
        return anchors.stream().anyMatch(anchor -> {
            if (haystack.contains(anchor)) {
                return true;
            }
            // 只有资源 ID、路径或英文连字符词需要忽略标点比较；中文实体必须保持
            // 连续匹配，不能把“压力”和“管道”两个独立命中拼成“压力管道”。
            boolean hasIdentifierSeparator = anchor.matches(".*[:_./-].*");
            String compactAnchor = normalize(anchor).replace(" ", "");
            return hasIdentifierSeparator && compactHaystack.contains(compactAnchor);
        });
    }

    private static Comparator<SearchResult> focusComparator(String focus) {
        return Comparator
                .comparingInt((SearchResult result) -> focusBonus(result, focus)).reversed()
                .thenComparing(Comparator.comparingInt(SearchResult::score).reversed())
                .thenComparing(SearchResult::documentId)
                .thenComparing(SearchResult::sourcePath);
    }

    private static int focusBonus(SearchResult result, String focus) {
        List<String> terms = FOCUS_ALIASES.getOrDefault(focus, List.of());
        if (terms.isEmpty()) {
            return 0;
        }
        String haystack = normalize(result.title() + " " + result.headingPath() + " " + result.segmentMarkdown());
        int hits = 0;
        for (String term : terms) {
            if (haystack.contains(normalize(term))) {
                hits++;
            }
        }
        return hits * 160;
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
            // 只有真正放进模型上下文的来源才标记为已读；上下文预算裁掉的结果必须
            // 留给下一轮补搜，避免 has_more 之后出现“已排除但从未返回”的来源。
            seenDocuments.add(documentKey(result.documentId()));
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
        String normalized = value == null || value.isBlank()
                ? "auto"
                : value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "zh", "zh_cn" -> SearchLanguage.ZH_CN;
            case "en", "en_us" -> SearchLanguage.EN_US;
            case "neutral" -> SearchLanguage.NEUTRAL;
            default -> SearchLanguage.AUTO;
        };
    }

    private static String normalizeFocus(String value) {
        String normalized = normalize(value);
        if (FOCUSES.contains(normalized)) {
            return normalized;
        }
        if (normalized.isBlank()) {
            return "identify";
        }
        int bestIndex = Integer.MAX_VALUE;
        String bestFocus = "identify";
        for (Map.Entry<String, List<String>> entry : FOCUS_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                int index = normalized.indexOf(normalize(alias));
                if (index >= 0 && index < bestIndex) {
                    bestIndex = index;
                    bestFocus = entry.getKey();
                }
            }
        }
        return bestFocus;
    }

    private static SearchStatus combinedStatus(List<SearchResponse> responses) {
        if (responses.stream().anyMatch(response -> response.status() == SearchStatus.INDEX_ERROR)) {
            return SearchStatus.INDEX_ERROR;
        }
        if (responses.stream().anyMatch(response -> response.status() == SearchStatus.INDEX_NOT_READY)) {
            return SearchStatus.INDEX_NOT_READY;
        }
        if (responses.stream().anyMatch(response -> response.status() == SearchStatus.EMPTY_QUERY)) {
            return SearchStatus.EMPTY_QUERY;
        }
        return SearchStatus.NO_MATCH;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", " ")
                .strip();
    }

    private static String rawNormalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u0000', ' ');
    }

    private static String queryKey(String value) {
        return normalize(value);
    }

    private static String documentKey(String value) {
        return normalize(value).replace(" ", "");
    }
}
