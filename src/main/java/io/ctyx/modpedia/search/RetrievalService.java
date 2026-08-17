package io.ctyx.modpedia.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于本地 SQLite（兼容旧版 manifest/关键词索引）的规则检索服务。
 *
 * <p>服务本身不依赖客户端 Screen 或 AI API，可以被后续会话层直接调用。</p>
 */
public final class RetrievalService implements AutoCloseable {
    private final Path knowledgeRoot;
    private final Path manifestPath;
    private final Path keywordIndexPath;
    private final Path databasePath;
    private final Path synonymsPath;
    private final Object reloadLock = new Object();

    private volatile Snapshot snapshot;
    private volatile FileStamp loadedStamp;
    private volatile SearchStatus loadStatus = SearchStatus.INDEX_NOT_READY;
    private volatile String loadError = "";
    private volatile FileState loadedDatabaseStamp;
    private volatile FileState loadedDatabaseSynonymsStamp;
    private volatile KnowledgeDatabase.Reader databaseReader;
    private volatile Map<String, Set<String>> databaseSynonyms = Map.of();
    private volatile SearchStatus databaseStatus = SearchStatus.INDEX_NOT_READY;
    private volatile String databaseError = "";
    private volatile SearchLanguage defaultLanguage = SearchLanguage.ZH_CN;

    public RetrievalService(Path knowledgeRoot) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.manifestPath = this.knowledgeRoot.resolve("manifest.json");
        this.keywordIndexPath = this.knowledgeRoot.resolve("keyword-index.json");
        this.databasePath = KnowledgeDatabase.path(this.knowledgeRoot);
        this.synonymsPath = this.knowledgeRoot.resolve("..").normalize().resolve("search-synonyms.json");
    }

    /** 返回当前知识库根目录；任务运行数据与文本检索共用同一个 knowledge.db。 */
    public Path knowledgeRoot() {
        return knowledgeRoot;
    }

    /** 更新游戏当前语言；AUTO 查询会使用这个值。 */
    public void setLanguage(SearchLanguage language) {
        defaultLanguage = language == null || language == SearchLanguage.AUTO
                ? SearchLanguage.ZH_CN
                : language;
    }

    public SearchLanguage language() {
        return defaultLanguage;
    }

    /**
     * 关闭长期持有的 SQLite 只读连接。
     *
     * <p>客户端会话通常跟随进程一直存活，但自测、重建工具和世界切换都可能
     * 提前结束一个检索服务。显式关闭可以避免旧连接继续持有已替换数据库的
     * 文件句柄。</p>
     */
    @Override
    public void close() {
        synchronized (reloadLock) {
            closeDatabaseReaderLocked();
            snapshot = null;
            loadedStamp = null;
            loadedDatabaseStamp = null;
            loadedDatabaseSynonymsStamp = null;
            databaseStatus = SearchStatus.INDEX_NOT_READY;
            databaseError = "";
            loadStatus = SearchStatus.INDEX_NOT_READY;
            loadError = "";
        }
    }

    /** 查询玩家已确认的物品目录资料；目录与手册检索使用同一个 SQLite 文件。 */
    public List<ItemCatalogEntry> lookupItems(
            Collection<String> itemIds,
            SearchLanguage language
    ) {
        if (itemIds == null || itemIds.isEmpty() || !Files.isRegularFile(databasePath)) {
            return List.of();
        }
        synchronized (reloadLock) {
            ensureFreshDatabaseLocked();
            if (databaseStatus != SearchStatus.READY || databaseReader == null) {
                return List.of();
            }
            SearchLanguage selected = language == null || language == SearchLanguage.AUTO
                    ? defaultLanguage
                    : language;
            return databaseReader.lookupItems(itemIds, selected);
        }
    }

    /** 按当前语言精确匹配物品显示名称；同名物品不在检索层擅自选择。 */
    public List<ItemCatalogEntry> lookupItemsByDisplayName(
            Collection<String> displayNames,
            SearchLanguage language
    ) {
        if (displayNames == null || displayNames.isEmpty() || !Files.isRegularFile(databasePath)) {
            return List.of();
        }
        synchronized (reloadLock) {
            ensureFreshDatabaseLocked();
            if (databaseStatus != SearchStatus.READY || databaseReader == null) {
                return List.of();
            }
            SearchLanguage selected = language == null || language == SearchLanguage.AUTO
                    ? defaultLanguage
                    : language;
            return databaseReader.lookupItemsByDisplayName(displayNames, selected);
        }
    }

    /** 从问题中提取物品 ID 并读取对应目录资料。 */
    public List<ItemCatalogEntry> lookupItemContext(String query, SearchLanguage language) {
        ItemQueryParser.Parsed parsed = ItemQueryParser.parse(query);
        List<ItemCatalogEntry> result = new ArrayList<>(lookupItems(parsed.itemIds(), language));
        Set<String> existingIds = result.stream()
                .map(ItemCatalogEntry::itemId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // 已确认的协议令牌/裸 ID 是最高置信度来源。此时不再把令牌中的显示名
        // 扩展成其它同名物品，避免 [[item:a:x|铁锭]] 被误补成 b:iron_ingot。
        // 没有 ID 时才按自然语言显示名称返回全部候选，并由模型处理歧义。
        if (parsed.itemIds().isEmpty()) {
            for (ItemCatalogEntry entry : lookupItemsByDisplayName(
                    ItemQueryParser.displayNameCandidates(parsed.searchableText()),
                    language
            )) {
                if (existingIds.add(entry.itemId())) {
                    result.add(entry);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 读取完整 Markdown，供后续模型构造上下文。 */
    public java.util.Optional<String> readMarkdown(String documentId) {
        if (!Files.isRegularFile(databasePath)) {
            return java.util.Optional.empty();
        }
        synchronized (reloadLock) {
            ensureFreshDatabaseLocked();
            KnowledgeDatabase.Reader reader = databaseReader;
            return reader == null
                    ? java.util.Optional.empty()
                    : reader.readMarkdown(documentId, defaultLanguage.code());
        }
    }

    /** 使用默认的八条结果执行搜索。 */
    public SearchResponse search(String text) {
        return search(SearchQuery.of(text));
    }

    /** 执行一次检索；结果只包含每篇文档中分数最高的完整段落。 */
    public SearchResponse search(SearchQuery query) {
        SearchQuery actualQuery = query == null ? SearchQuery.of("") : query;
        if (SearchTextNormalizer.normalizeField(actualQuery.text()).isBlank()) {
            return new SearchResponse(SearchStatus.EMPTY_QUERY, actualQuery.text(), List.of(), "");
        }

        if (Files.isRegularFile(databasePath)) {
            synchronized (reloadLock) {
                ensureFreshDatabaseLocked();
                if (databaseStatus != SearchStatus.READY || databaseReader == null) {
                    return new SearchResponse(databaseStatus, actualQuery.text(), List.of(), databaseError);
                }
                return databaseReader.search(
                        actualQuery,
                        defaultLanguage,
                        databaseSynonyms
                );
            }
        }

        ensureFreshSnapshot();
        SearchStatus currentStatus = loadStatus;
        if (currentStatus != SearchStatus.READY || snapshot == null) {
            return new SearchResponse(currentStatus, actualQuery.text(), List.of(), loadError);
        }

        Snapshot current = snapshot;
        SearchTextNormalizer.QueryTerms queryTerms = SearchTextNormalizer.query(
                actualQuery.text(),
                current.synonyms()
        );
        if (!queryTerms.usable()) {
            return new SearchResponse(SearchStatus.NO_MATCH, actualQuery.text(), List.of(), "");
        }

        Set<String> candidateIds = collectCandidates(current, queryTerms);
        if (candidateIds.isEmpty()) {
            return new SearchResponse(SearchStatus.NO_MATCH, actualQuery.text(), List.of(), "");
        }

        List<SearchResult> results = new ArrayList<>();
        for (String documentId : candidateIds) {
            DocumentMetadata document = current.documents().get(documentId);
            if (document == null) {
                continue;
            }
            SearchResult result = bestSegment(document, queryTerms);
            if (result != null) {
                results.add(result);
            }
        }

        results.sort(Comparator
                .comparingInt(SearchResult::score)
                .reversed()
                .thenComparing(SearchResult::documentId));
        if (results.size() > actualQuery.limit()) {
            results = new ArrayList<>(results.subList(0, actualQuery.limit()));
        }

        return new SearchResponse(
                results.isEmpty() ? SearchStatus.NO_MATCH : SearchStatus.READY,
                actualQuery.text(),
                results,
                ""
        );
    }

    /**
     * 为 AI 补搜读取同一文档的多个段落候选。
     *
     * <p>普通搜索契约仍然是“每篇文档一个最佳段落”。AI 需要在已经看过某篇文档的
     * 概览后继续寻找步骤或配方，因此这里使用 SQLite 的扩展候选接口；旧版 JSON
     * 索引没有段落数据库时退回普通搜索，不改变兼容行为。</p>
     */
    public SearchResponse searchForExpansion(SearchQuery query, int candidateLimit) {
        SearchQuery actualQuery = query == null ? SearchQuery.of("") : query;
        if (SearchTextNormalizer.normalizeField(actualQuery.text()).isBlank()) {
            return new SearchResponse(SearchStatus.EMPTY_QUERY, actualQuery.text(), List.of(), "");
        }

        if (Files.isRegularFile(databasePath)) {
            synchronized (reloadLock) {
                ensureFreshDatabaseLocked();
                if (databaseStatus != SearchStatus.READY || databaseReader == null) {
                    return new SearchResponse(databaseStatus, actualQuery.text(), List.of(), databaseError);
                }
                return databaseReader.searchExpanded(
                        actualQuery,
                        defaultLanguage,
                        databaseSynonyms,
                        Math.max(1, Math.min(256, candidateLimit)),
                        4
                );
            }
        }
        return search(actualQuery);
    }

    /** 强制重新读取 SQLite 或旧版 manifest、关键词索引和同义词配置。 */
    public void reload() {
        synchronized (reloadLock) {
            if (Files.isRegularFile(databasePath)) {
                reloadDatabaseLocked();
                return;
            }
            closeDatabaseReaderLocked();
            FileStamp currentStamp = FileStamp.capture(manifestPath, keywordIndexPath, synonymsPath);
            try {
                Snapshot next = loadSnapshot();
                snapshot = next;
                loadedStamp = currentStamp;
                loadStatus = SearchStatus.READY;
                loadError = "";
            } catch (IndexProblem problem) {
                loadedStamp = currentStamp;
                if (snapshot == null) {
                    loadStatus = problem.status();
                    loadError = problem.getMessage();
                }
                // 已有快照时继续使用旧索引，避免知识库更新的中间写入影响正在进行的查询。
            }
        }
    }

    private void ensureFreshDatabaseLocked() {
        FileState current = FileState.capture(databasePath);
        FileState currentSynonyms = FileState.capture(synonymsPath);
        if (databaseReader == null || !current.equals(loadedDatabaseStamp)) {
            reloadDatabaseLocked();
        } else if (!currentSynonyms.equals(loadedDatabaseSynonymsStamp)) {
            databaseSynonyms = loadSynonymsSafely();
            loadedDatabaseSynonymsStamp = currentSynonyms;
        }
    }

    private void reloadDatabaseLocked() {
        FileState current = FileState.capture(databasePath);
        FileState currentSynonyms = FileState.capture(synonymsPath);
        if (KnowledgeDatabase.isUsable(databasePath)) {
            try {
                KnowledgeDatabase.Reader next = KnowledgeDatabase.openReader(databasePath);
                KnowledgeDatabase.Reader previous = databaseReader;
                databaseReader = next;
                databaseSynonyms = loadSynonymsSafely();
                loadedDatabaseStamp = current;
                loadedDatabaseSynonymsStamp = currentSynonyms;
                databaseStatus = SearchStatus.READY;
                databaseError = "";
                if (previous != null) {
                    previous.close();
                }
            } catch (java.sql.SQLException | RuntimeException exception) {
                closeDatabaseReaderLocked();
                loadedDatabaseStamp = current;
                loadedDatabaseSynonymsStamp = currentSynonyms;
                databaseStatus = SearchStatus.INDEX_ERROR;
                databaseError = "SQLite 知识库打开失败：" + messageOf(exception);
            }
        } else {
            closeDatabaseReaderLocked();
            loadedDatabaseStamp = current;
            loadedDatabaseSynonymsStamp = currentSynonyms;
            databaseStatus = SearchStatus.INDEX_ERROR;
            databaseError = "SQLite 知识库不存在、损坏或 Schema 版本不匹配";
        }
    }

    private void closeDatabaseReaderLocked() {
        KnowledgeDatabase.Reader previous = databaseReader;
        databaseReader = null;
        if (previous != null) {
            previous.close();
        }
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private Map<String, Set<String>> loadSynonymsSafely() {
        try {
            return loadSynonyms();
        } catch (IOException exception) {
            return Map.of();
        }
    }

    private void ensureFreshSnapshot() {
        FileStamp currentStamp = FileStamp.capture(manifestPath, keywordIndexPath, synonymsPath);
        if (snapshot == null || !currentStamp.equals(loadedStamp)) {
            reload();
        }
    }

    private Snapshot loadSnapshot() {
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(keywordIndexPath)) {
            throw new IndexProblem(SearchStatus.INDEX_NOT_READY, "知识库索引尚未生成");
        }

        try {
            JsonObject manifest = parseObject(manifestPath, "manifest.json");
            JsonElement documentElement = manifest.get("documents");
            if (documentElement == null || !documentElement.isJsonArray()) {
                throw new IndexProblem(SearchStatus.INDEX_ERROR, "manifest.json 缺少 documents 数组");
            }

            Map<String, DocumentMetadata> documents = new LinkedHashMap<>();
            for (JsonElement element : documentElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                DocumentMetadata document = parseDocument(element.getAsJsonObject());
                if (!document.id().isBlank() && !document.path().isBlank()) {
                    documents.put(document.id(), document);
                }
            }

            JsonObject keywordIndex = parseObject(keywordIndexPath, "keyword-index.json");
            Map<String, Set<String>> index = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : keywordIndex.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    throw new IndexProblem(SearchStatus.INDEX_ERROR, "keyword-index.json 包含非法文档列表");
                }
                Set<String> ids = new LinkedHashSet<>();
                for (JsonElement id : entry.getValue().getAsJsonArray()) {
                    if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                        ids.add(id.getAsString());
                    }
                }
                String normalizedTerm = SearchTextNormalizer.normalizeField(entry.getKey());
                if (!normalizedTerm.isBlank() && !ids.isEmpty()) {
                    index.put(normalizedTerm, Set.copyOf(ids));
                }
            }

            return new Snapshot(
                    Map.copyOf(documents),
                    immutableIndex(index),
                    loadSynonyms()
            );
        } catch (IndexProblem problem) {
            throw problem;
        } catch (IOException | RuntimeException exception) {
            throw new IndexProblem(SearchStatus.INDEX_ERROR, "读取知识库索引失败：" + exception.getMessage(), exception);
        }
    }

    private JsonObject parseObject(Path path, String name) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IndexProblem(SearchStatus.INDEX_ERROR, name + " 必须是 JSON 对象");
        }
        return parsed.getAsJsonObject();
    }

    private DocumentMetadata parseDocument(JsonObject value) {
        String id = stringValue(value, "id");
        String title = stringValue(value, "title");
        String sourceMod = stringValue(value, "source_mod");
        String sourceType = stringValue(value, "source_type");
        String category = stringValue(value, "category");
        String sourceVersion = stringValue(value, "source_version");
        String sourcePath = stringValue(value, "source_path");
        String path = stringValue(value, "path");
        List<String> keywords = stringList(value.get("keywords"));
        return new DocumentMetadata(
                id,
                title,
                sourceMod,
                sourceType,
                category,
                sourceVersion,
                sourcePath,
                path,
                TextProfile.of(id),
                TextProfile.of(title),
                TextProfile.of(sourceMod),
                TextProfile.of(String.join(" ", keywords)),
                TextProfile.of(category + " " + sourcePath + " " + path),
                keywords
        );
    }

    private Map<String, Set<String>> loadSynonyms() throws IOException {
        if (!Files.isRegularFile(synonymsPath)) {
            return Map.of();
        }

        try {
            JsonObject root = parseObject(synonymsPath, "search-synonyms.json");
            JsonElement groupsElement = root.get("groups");
            if (groupsElement == null || !groupsElement.isJsonArray()) {
                return Map.of();
            }

            Map<String, Set<String>> synonyms = new LinkedHashMap<>();
            for (JsonElement groupElement : groupsElement.getAsJsonArray()) {
                if (!groupElement.isJsonArray()) {
                    continue;
                }
                LinkedHashSet<String> group = new LinkedHashSet<>();
                for (JsonElement item : groupElement.getAsJsonArray()) {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                        String value = SearchTextNormalizer.normalizeRaw(item.getAsString()).strip();
                        if (!value.isBlank()) {
                            group.add(value);
                        }
                    }
                }
                for (String value : group) {
                    Set<String> alternatives = new LinkedHashSet<>(group);
                    alternatives.remove(value);
                    synonyms.put(value, Set.copyOf(alternatives));
                }
            }
            return Map.copyOf(synonyms);
        } catch (IndexProblem problem) {
            // 同义词是可选增强；配置格式错误时保留基础关键词检索。
            return Map.of();
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private Set<String> collectCandidates(Snapshot current, SearchTextNormalizer.QueryTerms query) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (SearchTextNormalizer.QueryTerm term : query.terms()) {
            addIndexCandidates(current.keywordIndex(), term.value(), candidates);
        }
        addIndexCandidates(current.keywordIndex(), query.phrase(), candidates);

        // 标题、模组名和路径也属于可检索元数据；这些字段可能来自旧版索引，因此补一次元数据筛选。
        if (candidates.isEmpty()) {
            for (DocumentMetadata document : current.documents().values()) {
                if (scoreMetadata(document, query).score() > 0) {
                    candidates.add(document.id());
                }
            }
        }
        return candidates;
    }

    private void addIndexCandidates(
            Map<String, Set<String>> index,
            String rawTerm,
            Set<String> candidates
    ) {
        String term = SearchTextNormalizer.normalizeField(rawTerm);
        if (!SearchTextNormalizer.isSignificant(term)) {
            return;
        }
        Set<String> exact = index.get(term);
        if (exact != null) {
            candidates.addAll(exact);
        }
        String compactTerm = SearchTextNormalizer.compact(term);
        for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
            String indexTerm = entry.getKey();
            if (indexTerm.equals(term)
                    || compactTerm.length() >= 2
                    && SearchTextNormalizer.compact(indexTerm).contains(compactTerm)) {
                candidates.addAll(entry.getValue());
            }
        }
    }

    private SearchResult bestSegment(DocumentMetadata document, SearchTextNormalizer.QueryTerms query) {
        Score metadataScore = scoreMetadata(document, query);
        Path documentPath = knowledgeRoot.resolve(document.path()).normalize();
        if (!documentPath.startsWith(knowledgeRoot) || !Files.isRegularFile(documentPath)) {
            return null;
        }

        String markdown;
        try {
            markdown = Files.readString(documentPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }

        List<MarkdownSegmenter.MarkdownSegment> segments = MarkdownSegmenter.split(markdown);
        SearchResult best = null;
        for (MarkdownSegmenter.MarkdownSegment segment : segments) {
            MatchAccumulator accumulator = new MatchAccumulator(metadataScore);
            scorePhrase(segment.markdown(), query, 560, accumulator);
            for (SearchTextNormalizer.QueryTerm term : query.terms()) {
                scoreField(TextProfile.of(segment.markdown()), term, 340, 170, accumulator);
            }
            accumulator.addCoverageBonus(query.primaryTermCount());
            SearchResult candidate = new SearchResult(
                    document.id(),
                    document.title(),
                    document.sourceMod(),
                    document.sourceType(),
                    document.category(),
                    document.sourceVersion(),
                    document.sourcePath(),
                    segment.headingPath(),
                    segment.markdown(),
                    accumulator.score,
                    new ArrayList<>(accumulator.matchedTerms)
            );
            if (accumulator.score <= 0 || best == null
                    || candidate.score() > best.score()
                    || candidate.score() == best.score()
                    && segment.index() < segmentIndex(best, segments)) {
                if (accumulator.score > 0) {
                    best = candidate;
                }
            }
        }

        if (best == null && metadataScore.score() > 0 && !segments.isEmpty()) {
            MarkdownSegmenter.MarkdownSegment first = segments.get(0);
            best = new SearchResult(
                    document.id(),
                    document.title(),
                    document.sourceMod(),
                    document.sourceType(),
                    document.category(),
                    document.sourceVersion(),
                    document.sourcePath(),
                    first.headingPath(),
                    first.markdown(),
                    metadataScore.score(),
                    metadataScore.matchedTerms()
            );
        }
        return best;
    }

    private int segmentIndex(SearchResult result, List<MarkdownSegmenter.MarkdownSegment> segments) {
        for (MarkdownSegmenter.MarkdownSegment segment : segments) {
            if (segment.markdown().equals(result.segmentMarkdown())) {
                return segment.index();
            }
        }
        return Integer.MAX_VALUE;
    }

    private Score scoreMetadata(DocumentMetadata document, SearchTextNormalizer.QueryTerms query) {
        MatchAccumulator accumulator = new MatchAccumulator();
        scorePhrase(document.idProfile(), query, 1000, accumulator);
        scorePhrase(document.titleProfile(), query, 900, accumulator);
        scorePhrase(document.sourceModProfile(), query, 680, accumulator);
        scorePhrase(document.keywordProfile(), query, 620, accumulator);
        scorePhrase(document.otherProfile(), query, 260, accumulator);

        for (SearchTextNormalizer.QueryTerm term : query.terms()) {
            scoreField(document.idProfile(), term, 820, 480, accumulator);
            scoreField(document.titleProfile(), term, 720, 420, accumulator);
            scoreField(document.sourceModProfile(), term, 540, 300, accumulator);
            scoreField(document.keywordProfile(), term, 580, 300, accumulator);
            scoreField(document.otherProfile(), term, 240, 120, accumulator);
        }
        return accumulator.toScore();
    }

    private void scorePhrase(
            TextProfile profile,
            SearchTextNormalizer.QueryTerms query,
            int weight,
            MatchAccumulator accumulator
    ) {
        if (query.phrase().isBlank()) {
            return;
        }
        String phrase = SearchTextNormalizer.normalizeField(query.phrase());
        String compactPhrase = SearchTextNormalizer.compact(query.phrase());
        if (profile.normalized().equals(phrase)) {
            accumulator.addPhrase(weight, phrase);
        } else if (profile.normalized().contains(phrase)
                || compactPhrase.length() >= 2 && profile.compact().contains(compactPhrase)) {
            accumulator.addPhrase(Math.max(1, weight * 3 / 4), phrase);
        }
    }

    private void scorePhrase(
            String markdown,
            SearchTextNormalizer.QueryTerms query,
            int weight,
            MatchAccumulator accumulator
    ) {
        scorePhrase(TextProfile.of(markdown), query, weight, accumulator);
    }

    private void scoreField(
            TextProfile profile,
            SearchTextNormalizer.QueryTerm term,
            int exactWeight,
            int partialWeight,
            MatchAccumulator accumulator
    ) {
        String normalizedTerm = SearchTextNormalizer.normalizeField(term.value());
        if (!SearchTextNormalizer.isSignificant(normalizedTerm)) {
            return;
        }
        if (profile.tokens().contains(normalizedTerm) || profile.normalized().equals(normalizedTerm)) {
            accumulator.add(term, adjustedWeight(exactWeight, term));
        } else if (profile.normalized().contains(normalizedTerm)
                || normalizedTerm.length() >= 2
                && profile.compact().contains(SearchTextNormalizer.compact(normalizedTerm))) {
            accumulator.add(term, adjustedWeight(partialWeight, term));
        }
    }

    private static int adjustedWeight(int weight, SearchTextNormalizer.QueryTerm term) {
        return term.synonym() ? Math.max(1, weight * 3 / 4) : weight;
    }

    private static String stringValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static List<String> stringList(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String text = element.getAsString().trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Set<String>> immutableIndex(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, Set.copyOf(values)));
        return Map.copyOf(result);
    }

    private record Snapshot(
            Map<String, DocumentMetadata> documents,
            Map<String, Set<String>> keywordIndex,
            Map<String, Set<String>> synonyms
    ) {
    }

    private record DocumentMetadata(
            String id,
            String title,
            String sourceMod,
            String sourceType,
            String category,
            String sourceVersion,
            String sourcePath,
            String path,
            TextProfile idProfile,
            TextProfile titleProfile,
            TextProfile sourceModProfile,
            TextProfile keywordProfile,
            TextProfile otherProfile,
            List<String> keywords
    ) {
        private DocumentMetadata {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    private record TextProfile(String normalized, String compact, Set<String> tokens) {
        private static TextProfile of(String value) {
            String normalized = SearchTextNormalizer.normalizeField(value);
            Set<String> tokens = new LinkedHashSet<>();
            for (String token : SearchTextNormalizer.tokens(value)) {
                String normalizedToken = SearchTextNormalizer.normalizeField(token);
                if (!normalizedToken.isBlank()) {
                    tokens.add(normalizedToken);
                }
            }
            return new TextProfile(normalized, normalized.replace(" ", ""), Set.copyOf(tokens));
        }
    }

    private record Score(int score, List<String> matchedTerms, Set<String> matchedOrigins) {
        private Score {
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
            matchedOrigins = matchedOrigins == null ? Set.of() : Set.copyOf(matchedOrigins);
        }
    }

    private static final class MatchAccumulator {
        private int score;
        private final LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> matchedOrigins = new LinkedHashSet<>();

        private MatchAccumulator() {
        }

        private MatchAccumulator(Score base) {
            score = base.score();
            matchedTerms.addAll(base.matchedTerms());
            matchedOrigins.addAll(base.matchedOrigins());
        }

        private void add(SearchTextNormalizer.QueryTerm term, int points) {
            score += points;
            matchedTerms.add(term.value());
            if (!term.origin().isBlank()) {
                matchedOrigins.add(term.origin());
            }
        }

        private void addPhrase(int points, String phrase) {
            score += points;
            if (!phrase.isBlank()) {
                matchedTerms.add(phrase);
            }
        }

        private void addCoverageBonus(int expectedTerms) {
            if (expectedTerms > 0 && matchedOrigins.size() >= expectedTerms) {
                score += 120;
            }
        }

        private Score toScore() {
            return new Score(score, new ArrayList<>(matchedTerms), new LinkedHashSet<>(matchedOrigins));
        }
    }

    private record FileStamp(FileState manifest, FileState keywordIndex, FileState synonyms) {
        private static FileStamp capture(Path manifest, Path keywordIndex, Path synonyms) {
            return new FileStamp(
                    FileState.capture(manifest),
                    FileState.capture(keywordIndex),
                    FileState.capture(synonyms)
            );
        }
    }

    private record FileState(boolean exists, long modified, long size) {
        private static FileState capture(Path path) {
            if (!Files.isRegularFile(path)) {
                return new FileState(false, -1L, -1L);
            }
            try {
                return new FileState(
                        true,
                        Files.getLastModifiedTime(path).toMillis(),
                        Files.size(path)
                );
            } catch (IOException exception) {
                return new FileState(true, -1L, -1L);
            }
        }
    }

    private static final class IndexProblem extends RuntimeException {
        private final SearchStatus status;

        private IndexProblem(SearchStatus status, String message) {
            super(message);
            this.status = status;
        }

        private IndexProblem(SearchStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        private SearchStatus status() {
            return status;
        }
    }
}
