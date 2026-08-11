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
import io.ctyx.modpedia.search.KnowledgeScope;
import io.ctyx.modpedia.search.SearchQuery;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import io.ctyx.modpedia.search.SearchTextNormalizer;
import io.ctyx.modpedia.task.TaskKnowledgeStore;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskQueryMode;
import io.ctyx.modpedia.task.TaskResponse;
import io.ctyx.modpedia.task.TaskResult;
import io.ctyx.modpedia.task.TaskStatus;

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
    private static final Pattern CJK_RUN_PATTERN = Pattern.compile("[\\u3400-\\u9fff]{2,}");
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
            "identify", List.of("识别", "名称", "是什么", "介绍", "简介", "概览", "核心玩法",
                    "新手", "入门", "第一天", "overview", "introduction", "getting started",
                    "gameplay", "identify", "name", "what")
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is",
            "it", "of", "on", "or", "the", "to", "what", "where", "which", "with", "step", "steps",
            "procedure", "procedures", "setup", "prerequisite", "prerequisites", "requirement",
            "requirements", "usage", "instructions", "start", "begin", "using", "怎么", "如何", "什么",
            "哪个", "哪些", "怎么做", "如何使用", "使用方法", "用法", "可以", "需要", "这个", "那个",
            "设置", "前置", "前置条件", "前提", "条件", "步骤", "操作", "操作步骤", "防止", "模组",
            "连接", "连接方式", "安装", "启动", "使用", "怎么用",
            "配方", "合成", "制作", "材料", "依赖", "前置依赖", "漏气", "故障",
            "机器", "设备", "物品", "方块", "东西", "助手", "文档", "手册",
            "minecraft", "mod", "mods", "介绍", "简介", "概览", "是什么", "全称", "缩写", "核心玩法", "玩法", "功能",
            "机制", "流程", "生存流程", "新手", "入门", "第一天", "指南", "内容", "重制版",
            "overview", "introduction", "intro", "gameplay", "mechanics", "abbreviation", "guide"
    );

    private final RetrievalService retrievalService;
    private final SearchLanguage defaultLanguage;
    private final int maxResults;
    private final int maxContextChars;
    private final int maxRounds;
    private final TaskKnowledgeStore taskStore;
    private final AtomicInteger roundCounter;
    private final Consumer<SearchTrace> traceSink;
    private final Set<String> seenQueries = new HashSet<>();
    // 同一文档在不同补搜轮次可能返回不同的最佳段落；只按段落身份去重，
    // 否则第一轮命中概览页后，后续步骤/配方查询永远拿不到同一手册的细节页。
    private final Set<String> seenSegments = new LinkedHashSet<>();

    public SearchKnowledgeTool(
            RetrievalService retrievalService,
            SearchLanguage defaultLanguage,
            int maxResults,
            int maxContextChars,
            int round,
            Consumer<SearchTrace> traceSink
    ) {
        this(retrievalService, defaultLanguage, maxResults, maxContextChars, round,
                Integer.MAX_VALUE, new TaskKnowledgeStore(retrievalService.knowledgeRoot()), traceSink);
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
        this(retrievalService, defaultLanguage, maxResults, maxContextChars, round, maxRounds,
                new TaskKnowledgeStore(retrievalService.knowledgeRoot()), traceSink);
    }

    /** 允许测试或客户端注入任务存储；文本手册和任务运行表仍指向同一个 knowledge.db。 */
    public SearchKnowledgeTool(
            RetrievalService retrievalService,
            SearchLanguage defaultLanguage,
            int maxResults,
            int maxContextChars,
            int round,
            int maxRounds,
            TaskKnowledgeStore taskStore,
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
        this.taskStore = taskStore == null
                ? new TaskKnowledgeStore(retrievalService.knowledgeRoot())
                : taskStore;
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

        String queryKey = normalizedLanguage + "|" + normalizedFocus + "|" + queryKey(normalizedQuery);
        if (!seenQueries.add(queryKey)) {
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "重复查询，请改写关键词或针对缺失的资料类型继续搜索", normalizedQuery, normalizedLanguage, normalizedFocus);
        }

        List<SearchResponse> responses = searchLanguages(
                normalizedQuery,
                requestedLanguage,
                Math.max(32, Math.min(256, maxResults * 8 + excluded.size() * 4)),
                KnowledgeScope.MOD_MANUAL
        );
        List<SearchResult> candidates = mergeCandidates(responses);
        candidates = keepEntityMatches(candidates, normalizedQuery);

        List<SearchResult> ordered = candidates.stream()
                .filter(result -> !seenSegments.contains(resultKey(result)))
                .sorted(focusComparator(normalizedFocus))
                .toList();
        // 已读文档只是降级候选，不是永久黑名单：同一手册的下一轮可以返回另一个
        // 步骤/配方段落。每轮仍按文档去重，避免同一轮把一篇手册的多个段落挤满结果。
        List<SearchResult> preferred = uniqueByDocument(ordered.stream()
                .filter(result -> !excluded.contains(documentKey(result.documentId())))
                .toList());
        List<SearchResult> fallback = uniqueByDocument(ordered.stream()
                .filter(result -> excluded.contains(documentKey(result.documentId())))
                .toList());
        List<SearchResult> fresh = new ArrayList<>(preferred);
        if (fresh.size() < requestedLimit) {
            fresh.addAll(fallback);
        }
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

    @Tool(
            name = "search_wiki",
            value = "搜索整合包作者指南、任务 Wiki 和其他本地 Wiki，返回完整 Markdown 段落。"
    )
    public String searchWiki(
            @P(name = "query", value = "Wiki 查询词或自然语言问题") String query,
            @P(name = "language", value = "auto、zh_cn、en_us 或 neutral", defaultValue = "auto", required = false)
            String language,
            @P(name = "limit", value = "结果数量，范围 1 到 20", defaultValue = "8", required = false)
            Integer limit,
            @P(name = "collection_ids", value = "限定 Wiki 集合 ID 数组", defaultValue = "[]", required = false)
            List<String> collectionIds
    ) {
        String normalizedQuery = query == null ? "" : query.strip();
        SearchLanguage requestedLanguage = parseLanguage(language);
        String normalizedLanguage = requestedLanguage.code();
        int requestedLimit = limit == null ? Math.min(8, maxResults) : Math.max(1, Math.min(maxResults, limit));
        JsonObject output = new JsonObject();
        int currentRound = roundCounter.getAndIncrement();
        output.addProperty("query", normalizedQuery);
        output.addProperty("language", normalizedLanguage);
        output.addProperty("scope", "wiki");
        output.addProperty("round", currentRound);
        if (currentRound > maxRounds) {
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "已达到搜索预算，请根据已返回资料整理回答。",
                    normalizedQuery, normalizedLanguage, "related");
        }
        if (normalizedQuery.isBlank()) {
            return finish(output, currentRound, SearchStatus.EMPTY_QUERY, List.of(), false,
                    "查询为空", normalizedQuery, normalizedLanguage, "related");
        }

        String queryKey = "wiki|" + normalizedLanguage + "|" + queryKey(normalizedQuery);
        if (!seenQueries.add(queryKey)) {
            return finish(output, currentRound, SearchStatus.NO_MATCH, List.of(), false,
                    "重复 Wiki 查询，请改写集合名称、章节标题或具体关键词。",
                    normalizedQuery, normalizedLanguage, "related");
        }
        List<SearchResponse> responses = searchLanguages(
                normalizedQuery,
                requestedLanguage,
                Math.max(16, Math.min(256, maxResults * 8)),
                KnowledgeScope.WIKI
        );
        List<SearchResult> candidates = keepEntityMatches(mergeCandidates(responses), normalizedQuery);
        if (collectionIds != null && !collectionIds.isEmpty()) {
            Set<String> wanted = collectionIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .collect(java.util.stream.Collectors.toSet());
            candidates = candidates.stream()
                    .filter(result -> wanted.contains(result.collectionId()))
                    .toList();
        }
        List<SearchResult> selected = uniqueByDocument(candidates.stream()
                .filter(result -> !seenSegments.contains(resultKey(result)))
                .sorted(focusComparator("related"))
                .limit(requestedLimit)
                .toList());
        boolean hasMore = candidates.size() > selected.size();
        return finish(
                output,
                currentRound,
                selected.isEmpty() ? combinedStatus(responses) : SearchStatus.READY,
                selected,
                hasMore,
                selected.isEmpty() ? "当前没有匹配的 Wiki 文档" : "",
                normalizedQuery,
                normalizedLanguage,
                "related"
        );
    }

    @Tool(
            name = "search_tasks",
            value = "查询可选任务模组的本地运行快照。区分任务定义和玩家实时进度；" +
                    "NEXT 返回候选下一步，DETAILS 返回任务要求和奖励，BLOCKED 返回阻塞原因，" +
                    "SEARCH 按名称或 ID 搜索。随机奖励必须按候选列表处理，不能当成确定奖励。"
    )
    public String searchTasks(
            @P(name = "mode", value = "NEXT、DETAILS、BLOCKED 或 SEARCH，也支持中文描述",
                    defaultValue = "SEARCH", required = false)
            String mode,
            @P(name = "query", value = "任务名称、任务 ID 或缺失条件；NEXT 可留空",
                    defaultValue = "", required = false)
            String query,
            @P(name = "quest_id", value = "精确任务 ID，可留空", defaultValue = "", required = false)
            String questId,
            @P(name = "limit", value = "结果数量，范围 1 到 20", defaultValue = "8", required = false)
            Integer limit,
            @P(name = "collection_ids", value = "任务来源集合或世界作用域 ID 数组",
                    defaultValue = "[]", required = false)
            List<String> collectionIds
    ) {
        TaskQueryMode requestedMode = parseTaskMode(mode);
        String normalizedQuery = query == null ? "" : query.strip();
        String normalizedQuestId = questId == null ? "" : questId.strip();
        int requestedLimit = limit == null ? Math.min(8, maxResults) : Math.max(1, Math.min(maxResults, limit));
        int currentRound = roundCounter.getAndIncrement();
        JsonObject output = new JsonObject();
        output.addProperty("scope", "task_runtime");
        output.addProperty("mode", requestedMode.name());
        output.addProperty("query", normalizedQuery);
        output.addProperty("quest_id", normalizedQuestId);
        output.addProperty("round", currentRound);

        if (currentRound > maxRounds) {
            return finishTask(output, currentRound, requestedMode, normalizedQuery, normalizedQuestId,
                    new TaskResponse(TaskStatus.NO_MATCH,
                            new TaskQuery(requestedMode, normalizedQuery, normalizedQuestId, requestedLimit, collectionIds),
                            List.of(), "已达到搜索预算，请根据已返回任务资料整理回答。"), requestedLimit,
                    "已达到搜索预算，请根据已返回任务资料整理回答，并列出仍缺失的任务进度资料。");
        }

        TaskResponse response = taskStore.query(new TaskQuery(
                requestedMode,
                normalizedQuery,
                normalizedQuestId,
                requestedLimit,
                collectionIds
        ));
        return finishTask(
                output,
                currentRound,
                requestedMode,
                normalizedQuery,
                normalizedQuestId,
                response,
                requestedLimit,
                response.error()
        );
    }

    private String finishTask(
            JsonObject output,
            int round,
            TaskQueryMode mode,
            String query,
            String questId,
            TaskResponse response,
            int limit,
            String hint
    ) {
        JsonArray quests = new JsonArray();
        Map<String, SourceReference> sources = new LinkedHashMap<>();
        for (TaskResult result : response.results()) {
            JsonObject quest = new JsonObject();
            quest.addProperty("quest_id", result.questId());
            quest.addProperty("title", result.title());
            quest.addProperty("description_markdown", result.descriptionMarkdown());
            quest.addProperty("status", result.status());
            quest.addProperty("visible", result.visible());
            quest.addProperty("optional", result.optional());
            quest.addProperty("scope_key", result.scopeKey());
            quest.addProperty("snapshot_id", result.snapshotId());
            quest.addProperty("progress_available", result.progressAvailable());
            quest.add("unmet_dependencies", JSON.toJsonTree(result.unmetDependencies()));

            JsonArray requirements = new JsonArray();
            for (TaskResult.TaskRequirementResult requirement : result.requirements()) {
                JsonObject item = new JsonObject();
                item.addProperty("task_id", requirement.taskId());
                item.addProperty("type", requirement.type());
                item.addProperty("title", requirement.title());
                item.addProperty("target_id", requirement.targetId());
                item.addProperty("current", requirement.current());
                item.addProperty("required", requirement.required());
                item.addProperty("completed", requirement.completed());
                requirements.add(item);
            }
            quest.add("requirements", requirements);

            JsonArray rewards = new JsonArray();
            for (TaskResult.TaskRewardResult reward : result.rewards()) {
                JsonObject item = new JsonObject();
                boolean random = isRandomReward(reward);
                item.addProperty("reward_id", reward.rewardId());
                item.addProperty("type", reward.type());
                item.addProperty("title", reward.title());
                item.addProperty("guaranteed", reward.guaranteed() && !random);
                item.addProperty("is_random", random);
                item.addProperty("guaranteed_statement", random ? "false" : Boolean.toString(reward.guaranteed()));
                item.add("candidates", JSON.toJsonTree(reward.candidates()));
                rewards.add(item);
            }
            quest.add("rewards", rewards);
            quest.addProperty("source_path", result.sourcePath());
            quests.add(quest);

            String documentId = "task:" + result.snapshotId() + "/" + result.questId();
            sources.putIfAbsent(documentId, new SourceReference(
                    documentId,
                    result.title().isBlank() ? result.questId() : result.title(),
                    "task",
                    result.sourcePath()
            ));
        }
        output.addProperty("status", response.status().name());
        output.addProperty("returned_count", quests.size());
        output.addProperty("has_more", quests.size() >= limit && response.status() == TaskStatus.READY);
        output.addProperty("new_source_count", sources.size());
        output.addProperty("data_definition", "task_static_definition");
        output.addProperty("data_progress", quests.isEmpty() ? "unavailable" : "task_runtime_progress");
        output.add("results", quests);
        if (!hint.isBlank()) {
            output.addProperty("hint", hint);
        }
        traceSink.accept(new SearchTrace(
                query.isBlank() ? questId : query,
                "neutral",
                mode.name().toLowerCase(Locale.ROOT),
                round,
                response.status().name(),
                quests.size() >= limit && response.status() == TaskStatus.READY,
                List.copyOf(sources.values()),
                System.currentTimeMillis()
        ));
        return JSON.toJson(output);
    }

    private static boolean isRandomReward(TaskResult.TaskRewardResult reward) {
        String type = normalize(reward.type() + " " + reward.title());
        return reward.candidates().size() > 1
                || type.contains("random")
                || type.contains("loot")
                || type.contains("箱")
                || type.contains("随机");
    }

    private static TaskQueryMode parseTaskMode(String value) {
        String normalized = normalize(value).replace(" ", "");
        return switch (normalized) {
            case "next", "下一步", "当前主线", "主线", "接下来" -> TaskQueryMode.NEXT;
            case "details", "detail", "详情", "要求", "任务要求" -> TaskQueryMode.DETAILS;
            case "blocked", "block", "卡住", "阻塞", "为什么卡住" -> TaskQueryMode.BLOCKED;
            case "wiki", "说明" -> TaskQueryMode.WIKI;
            default -> TaskQueryMode.SEARCH;
        };
    }

    private List<SearchResponse> searchLanguages(
            String query,
            SearchLanguage requestedLanguage,
            int candidateLimit,
            KnowledgeScope scope
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
            responses.add(searchOne(query, primary, candidateLimit, scope));
            responses.add(searchOne(query, alternate, candidateLimit, scope));
        } else {
            responses.add(searchOne(query, requestedLanguage, candidateLimit, scope));
        }
        return responses;
    }

    private SearchResponse searchOne(
            String query,
            SearchLanguage language,
            int candidateLimit,
            KnowledgeScope scope
    ) {
        return retrievalService.searchForExpansion(new SearchQuery(
                query,
                SearchQuery.MAX_LIMIT,
                language,
                scope
        ), candidateLimit);
    }

    private static List<SearchResult> mergeCandidates(List<SearchResponse> responses) {
        Map<String, SearchResult> bestBySegment = new LinkedHashMap<>();
        for (SearchResponse response : responses) {
            for (SearchResult result : response.results()) {
                String key = resultKey(result);
                SearchResult previous = bestBySegment.get(key);
                if (previous == null || better(result, previous)) {
                    bestBySegment.put(key, result);
                }
            }
        }
        return new ArrayList<>(bestBySegment.values());
    }

    private static List<SearchResult> uniqueByDocument(List<SearchResult> results) {
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        for (SearchResult result : results) {
            unique.putIfAbsent(documentKey(result.documentId()), result);
        }
        return new ArrayList<>(unique.values());
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
            return hasUnresolvedReference(query) ? List.of() : candidates;
        }
        List<SearchResult> matched = candidates.stream()
                .filter(result -> matchesAnchor(result, anchors))
                .toList();
        if (matched.isEmpty()) {
            // 查询包含明确实体但当前语言没有命中该实体时，返回空结果而不是把通用页面
            // 当成答案。模型会看到 NO_MATCH，再用英文名称、ID 或其他实体改写查询。
            return List.of();
        }

        // 对三字以上的中文实体优先要求完整短语命中。仅命中“压力”“管道”这类
        // 双字片段会把泵、支撑梁和其它泛相关页面带进来；完整短语存在时，只保留
        // 真正包含该实体的段落。完整短语完全不存在时也返回空，让模型改用英文名
        // 或内部 ID 补搜，而不是把双字片段拼成错误答案。
        List<String> compoundAnchors = anchors.stream()
                .filter(anchor -> anchor.matches("[\\u3400-\\u9fff]{3,}"))
                .toList();
        if (!compoundAnchors.isEmpty()) {
            List<String> matchedCompounds = compoundAnchors.stream()
                    .filter(anchor -> candidates.stream().anyMatch(result -> matchesCompoundAnchor(result, anchor)))
                    .toList();
            if (matchedCompounds.isEmpty()) {
                return List.of();
            }
            List<SearchResult> compoundMatches = matched.stream()
                    .filter(result -> matchedCompounds.stream()
                            .anyMatch(anchor -> matchesCompoundAnchor(result, anchor)))
                    .toList();
            if (!compoundMatches.isEmpty()) {
                return compoundMatches;
            }
        }

        // 模组显示名经常位于查询开头，但它只代表“候选模组”，不是最终实体。只要
        // 更具体的 ID、中文实体或英文物品名在正文/标题中出现，就按具体锚点收窄，
        // 避免 overview、getting-started 等模组总览页压过目标页面。
        List<String> specific = anchors.stream()
                .filter(SearchKnowledgeTool::isSpecificAnchor)
                .filter(anchor -> candidates.stream().anyMatch(result -> matchesContentAnchor(result, anchor)))
                .toList();
        if (!specific.isEmpty()) {
            List<SearchResult> focused = matched.stream()
                    .filter(result -> specific.stream().anyMatch(anchor -> matchesContentAnchor(result, anchor)))
                    .toList();
            if (!focused.isEmpty()) {
                return focused;
            }
        }
        return matched;
    }

    private static boolean hasUnresolvedReference(String query) {
        String normalized = normalize(query).replace(" ", "");
        return normalized.contains("这个机器")
                || normalized.contains("那个机器")
                || normalized.contains("这个设备")
                || normalized.contains("那个设备")
                || normalized.contains("这个物品")
                || normalized.contains("那个物品")
                || normalized.contains("这个方块")
                || normalized.contains("那个方块")
                || normalized.contains("这个东西")
                || normalized.contains("那个东西")
                || normalized.contains("这个模组")
                || normalized.contains("本模组")
                || normalized.contains("这个助手")
                || normalized.contains("本助手");
    }

    private static boolean matchesCompoundAnchor(SearchResult result, String anchor) {
        // 三字以上中文实体必须作为连续短语命中；不能把“压力”“容器”或
        // 其它相邻双字词分别命中后拼成一个不存在的物品名称。
        return matchesContentAnchor(result, anchor);
    }

    private static List<String> queryAnchors(String query) {
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(rawNormalize(query));
        while (matcher.find()) {
            String token = matcher.group();
            Matcher cjk = CJK_RUN_PATTERN.matcher(token);
            while (cjk.find()) {
                addCjkAnchors(anchors, cjk.group());
            }
            for (String part : token.split("[\\u3400-\\u9fff]+")) {
                if (!part.isBlank()
                        && !isGenericAnchor(part)
                        && (part.length() >= 3 || part.matches("[a-z0-9_:/.-]{4,}"))) {
                    anchors.add(part);
                }
            }
        }
        return List.copyOf(anchors);
    }

    private static void addCjkAnchors(LinkedHashSet<String> anchors, String run) {
        for (String semantic : SearchTextNormalizer.semanticCjkPhrases(run)) {
            if (!isGenericAnchor(semantic) && semantic.length() >= 2) {
                anchors.add(semantic);
            }
            for (int index = 0; index + 1 < semantic.length(); index++) {
                String gram = semantic.substring(index, index + 2);
                if (!isGenericAnchor(gram)) {
                    anchors.add(gram);
                }
            }
        }
    }

    private static boolean matchesAnchor(SearchResult result, List<String> anchors) {
        return anchors.stream().anyMatch(anchor -> {
            String normalizedAnchor = normalize(anchor);
            return containsAnchor(result.documentId(), normalizedAnchor, anchor)
                    || containsAnchor(result.title(), normalizedAnchor, anchor)
                    || containsAnchor(result.sourceMod(), normalizedAnchor, anchor)
                    || containsAnchor(result.sourcePath(), normalizedAnchor, anchor)
                    || containsAnchor(result.headingPath(), normalizedAnchor, anchor)
                    || containsAnchor(result.segmentMarkdown(), normalizedAnchor, anchor)
                    || containsAnchor(String.join(" ", result.matchedTerms()), normalizedAnchor, anchor);
        });
    }

    private static boolean matchesContentAnchor(SearchResult result, String anchor) {
        String normalizedAnchor = normalize(anchor);
        return containsAnchor(result.title(), normalizedAnchor, anchor)
                || containsAnchor(result.headingPath(), normalizedAnchor, anchor)
                || containsAnchor(result.segmentMarkdown(), normalizedAnchor, anchor)
                || containsAnchor(String.join(" ", result.matchedTerms()), normalizedAnchor, anchor);
    }

    private static boolean containsAnchor(String value, String normalizedAnchor, String originalAnchor) {
        String normalizedValue = normalize(value);
        if (normalizedValue.contains(normalizedAnchor)) {
            return true;
        }
        // 只有 ID、路径和带连字符的英文名称允许忽略分隔符；中文实体仍必须
        // 在同一个字段中连续出现，避免跨字段或跨双字词产生假命中。
        return originalAnchor.matches(".*[:_./-].*")
                && normalizedValue.replace(" ", "")
                .contains(normalizedAnchor.replace(" ", ""));
    }

    private static boolean isSpecificAnchor(String anchor) {
        String normalized = normalize(anchor);
        return normalized.matches(".*[\\u3400-\\u9fff].*")
                || normalized.matches(".*[:_./-].*")
                || normalized.length() >= 5;
    }

    private static boolean isGenericAnchor(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || GENERIC_QUERY_TERMS.contains(normalized)) {
            return true;
        }
        return FOCUS_ALIASES.values().stream()
                .flatMap(List::stream)
                .map(SearchKnowledgeTool::normalize)
                .anyMatch(normalized::equals);
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
        Map<String, SourceReference> sourcesByDocument = new LinkedHashMap<>();
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
            document.addProperty("content_kind", result.contentKind());
            document.addProperty("source_id", result.sourceId());
            document.addProperty("collection_id", result.collectionId());
            document.addProperty("category", result.category());
            document.addProperty("source_version", result.sourceVersion());
            document.addProperty("source_path", result.sourcePath());
            document.addProperty("heading_path", result.headingPath());
            document.addProperty("segment_markdown", result.segmentMarkdown());
            document.addProperty("score", result.score());
            document.add("matched_terms", JSON.toJsonTree(result.matchedTerms()));
            documents.add(document);
            sourcesByDocument.putIfAbsent(documentKey(result.documentId()), new SourceReference(
                    result.documentId(),
                    result.title().isBlank() ? result.documentId() : result.title(),
                    result.sourceMod(),
                    result.sourcePath()
            ));
            // 只有真正放进模型上下文的来源才标记为已读；上下文预算裁掉的结果必须
            // 留给下一轮补搜，避免 has_more 之后出现“已排除但从未返回”的来源。
            seenSegments.add(resultKey(result));
        }
        output.addProperty("status", status.name());
        output.addProperty("returned_count", documents.size());
        output.addProperty("new_source_count", sourcesByDocument.size());
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
                List.copyOf(sourcesByDocument.values()),
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

    private static String resultKey(SearchResult result) {
        return documentKey(result.documentId())
                + "|" + normalize(result.headingPath())
                + "|" + normalize(result.segmentMarkdown());
    }
}
