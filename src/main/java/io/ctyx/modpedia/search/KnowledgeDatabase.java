package io.ctyx.modpedia.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite 派生知识库。
 *
 * <p>JAR 和 custom Markdown 是事实源，SQLite 只保存可检索的完整 Markdown、
 * 段落和索引。构建始终写入旁路文件，成功提交后再替换正式数据库，避免启动时
 * 读取到半成品。</p>
 */
public final class KnowledgeDatabase {
    public static final int SCHEMA_VERSION = 3;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int CUSTOM_PRIORITY = 100;

    private KnowledgeDatabase() {
    }

    public static Path path(Path knowledgeRoot) {
        return knowledgeRoot.resolve("knowledge.db");
    }

    /** 一个当前构建应保留的有效文档来源。 */
    public record DocumentInput(
            String sourceKey,
            String fingerprint,
            String relativePath,
            String language,
            int priority,
            KnowledgeDocument document
    ) {
        public DocumentInput {
            sourceKey = requireText(sourceKey, "sourceKey");
            fingerprint = requireText(fingerprint, "fingerprint");
            relativePath = requireText(relativePath, "relativePath");
            language = normalizeLanguage(language);
            if (document == null) {
                throw new IllegalArgumentException("document 不能为空");
            }
        }

        public boolean custom() {
            return priority >= CUSTOM_PRIORITY || document.sourceType().startsWith("custom");
        }
    }

    /** 从 SQLite 缓存复用的自定义文档。 */
    public record CachedDocument(DocumentInput input) {
    }

    public record SyncResult(
            int updatedCount,
            int reusedCount,
            int removedCount,
            int documentCount
    ) {
    }

    /**
     * 读取上一次已成功导入的自定义文档。
     *
     * <p>只要路径和 SHA-256 指纹都相同，就可以直接复用已经解析好的
     * KnowledgeDocument，启动时不再重复解析 Markdown。</p>
     */
    public static Map<String, CachedDocument> readCachedCustomDocuments(Path databasePath) {
        if (!Files.isRegularFile(databasePath)) {
            return Map.of();
        }

        Map<String, CachedDocument> result = new LinkedHashMap<>();
        try (Connection connection = open(databasePath, true);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT source_key, fingerprint, relative_path, language, priority, "
                             + "document_id, source_mod, source_type, title, category, keywords_json, "
                             + "source_version, source_path, markdown "
                             + "FROM documents WHERE priority >= ?")) {
            statement.setInt(1, CUSTOM_PRIORITY);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    KnowledgeDocument document = new KnowledgeDocument(
                            rows.getString("document_id"),
                            rows.getString("source_mod"),
                            rows.getString("source_type"),
                            rows.getString("title"),
                            rows.getString("category"),
                            parseKeywords(rows.getString("keywords_json")),
                            rows.getString("source_version"),
                            rows.getString("source_path"),
                            rows.getString("markdown")
                    );
                    DocumentInput input = new DocumentInput(
                            rows.getString("source_key"),
                            rows.getString("fingerprint"),
                            rows.getString("relative_path"),
                            rows.getString("language"),
                            rows.getInt("priority"),
                            document
                    );
                    result.put(input.relativePath(), new CachedDocument(input));
                }
            }
            return Map.copyOf(result);
        } catch (SQLException | RuntimeException exception) {
            // 数据库损坏时由下一次编译从 JAR/custom 全量重建。
            return Map.of();
        }
    }

    /**
     * 增量同步正式数据库。
     *
     * @param forceRebuild F9 等强制重建时，即使指纹相同也重建文档段落
     */
    public static SyncResult sync(
            Path knowledgeRoot,
            Collection<DocumentInput> inputs,
            boolean forceRebuild
    ) throws IOException {
        Files.createDirectories(knowledgeRoot);
        Path database = path(knowledgeRoot);
        Path staged = Files.createTempFile(knowledgeRoot, "knowledge-", ".db.tmp");
        Files.deleteIfExists(staged);

        if (isUsable(database)) {
            Files.copy(database, staged, StandardCopyOption.REPLACE_EXISTING);
        }

        SyncResult result;
        try {
            result = syncStaged(staged, inputs, forceRebuild);
            replaceDatabase(staged, database);
            return result;
        } catch (SQLException | RuntimeException exception) {
            Files.deleteIfExists(staged);
            throw new IOException("SQLite 知识库同步失败", exception);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** 检查数据库是否可读且 Schema 版本正确。 */
    public static boolean isUsable(Path databasePath) {
        if (!Files.isRegularFile(databasePath)) {
            return false;
        }
        try (Connection connection = open(databasePath, true);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() && rows.getInt(1) == SCHEMA_VERSION && hasTable(connection, "documents");
        } catch (SQLException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * 可复用的只读连接。
     *
     * <p>打开 SQLite 连接和初始化 FTS5 查询环境是一次性成本。客户端会话的搜索
     * 频率高于知识库重建频率，因此由检索服务持有一个连接，避免每次按键都重新
     * 打开数据库。实例方法使用 synchronized，保证同一个服务的并发查询不会共用
     * JDBC 游标。</p>
     */
    public static Reader openReader(Path databasePath) throws SQLException {
        return new Reader(databasePath);
    }

    public static final class Reader implements AutoCloseable {
        private final Connection connection;
        private boolean closed;

        private Reader(Path databasePath) throws SQLException {
            if (!Files.isRegularFile(databasePath)) {
                throw new SQLException("SQLite 知识库不存在");
            }
            this.connection = open(databasePath, true);
        }

        public synchronized SearchResponse search(
                SearchQuery query,
                SearchLanguage defaultLanguage,
                Map<String, Set<String>> synonyms
        ) {
            ensureOpen();
            SearchQuery actualQuery = query == null ? SearchQuery.of("") : query;
            if (SearchTextNormalizer.normalizeField(actualQuery.text()).isBlank()) {
                return new SearchResponse(SearchStatus.EMPTY_QUERY, actualQuery.text(), List.of(), "");
            }
            return searchConnection(connection, actualQuery, defaultLanguage, synonyms, 1, actualQuery.limit());
        }

        /**
         * 为 AI 补搜读取更大的段落候选窗口。
         *
         * <p>普通规则搜索仍然每篇文档只返回一个最佳段落；补搜需要在同一文档已经
         * 命中概览页时，继续拿到步骤/配方等其它段落。这里把“候选窗口”和“最终
         * 返回限制”分开，调用方仍负责去重和上下文预算。</p>
         */
        public synchronized SearchResponse searchExpanded(
                SearchQuery query,
                SearchLanguage defaultLanguage,
                Map<String, Set<String>> synonyms,
                int candidateLimit,
                int segmentsPerDocument
        ) {
            ensureOpen();
            SearchQuery actualQuery = query == null ? SearchQuery.of("") : query;
            if (SearchTextNormalizer.normalizeField(actualQuery.text()).isBlank()) {
                return new SearchResponse(SearchStatus.EMPTY_QUERY, actualQuery.text(), List.of(), "");
            }
            return searchConnection(
                    connection,
                    actualQuery,
                    defaultLanguage,
                    synonyms,
                    Math.max(1, Math.min(8, segmentsPerDocument)),
                    Math.max(1, Math.min(256, candidateLimit))
            );
        }

        public synchronized Optional<String> readMarkdown(String documentId, String language) {
            ensureOpen();
            if (documentId == null || documentId.isBlank()) {
                return Optional.empty();
            }
            try {
                return KnowledgeDatabase.readMarkdown(connection, documentId, language);
            } catch (SQLException | RuntimeException exception) {
                return Optional.empty();
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败不影响后续重新打开新的只读连接。
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("SQLite 只读连接已关闭");
            }
        }
    }

    /** 读取完整文档 Markdown，供后续模型上下文组装使用。 */
    public static Optional<String> readMarkdown(
            Path databasePath,
            String documentId,
            String language
    ) {
        if (!Files.isRegularFile(databasePath) || documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        try (Reader reader = openReader(databasePath)) {
            return reader.readMarkdown(documentId, language);
        } catch (SQLException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** 使用 SQLite FTS5 执行规则搜索，不在查询过程中读取或拆分 Markdown 文件。 */
    public static SearchResponse search(
            Path databasePath,
            SearchQuery query,
            SearchLanguage defaultLanguage,
            Map<String, Set<String>> synonyms
    ) {
        SearchQuery actualQuery = query == null ? SearchQuery.of("") : query;
        if (SearchTextNormalizer.normalizeField(actualQuery.text()).isBlank()) {
            return new SearchResponse(SearchStatus.EMPTY_QUERY, actualQuery.text(), List.of(), "");
        }
        if (!Files.isRegularFile(databasePath)) {
            return new SearchResponse(SearchStatus.INDEX_NOT_READY, actualQuery.text(), List.of(), "SQLite 知识库尚未生成");
        }

        try (Reader reader = openReader(databasePath)) {
            return reader.search(actualQuery, defaultLanguage, synonyms);
        } catch (SQLException | RuntimeException exception) {
            return new SearchResponse(
                    SearchStatus.INDEX_ERROR,
                    actualQuery.text(),
                    List.of(),
                    "读取 SQLite 知识库失败：" + (exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage())
            );
        }
    }

    private static Optional<String> readMarkdown(
            Connection connection,
            String documentId,
            String language
    ) throws SQLException {
        String selectedLanguage = normalizeLanguage(language);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT markdown FROM documents WHERE document_id = ? "
                        + "AND language IN (?, 'neutral') "
                        + "ORDER BY priority DESC, CASE WHEN language = ? THEN 0 ELSE 1 END LIMIT 1")) {
            statement.setString(1, documentId);
            statement.setString(2, selectedLanguage);
            statement.setString(3, selectedLanguage);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        }
    }

    private static SearchResponse searchConnection(
            Connection connection,
            SearchQuery actualQuery,
            SearchLanguage defaultLanguage,
            Map<String, Set<String>> synonyms,
            int segmentsPerDocument,
            int resultLimit
    ) {
        SearchLanguage language = actualQuery.language() == SearchLanguage.AUTO
                ? defaultLanguage == null || defaultLanguage == SearchLanguage.AUTO
                ? SearchLanguage.ZH_CN
                : defaultLanguage
                : actualQuery.language();
        SearchTextNormalizer.QueryTerms queryTerms = SearchTextNormalizer.query(
                actualQuery.text(),
                synonyms == null ? Map.of() : synonyms
        );
        if (!queryTerms.usable()) {
            return new SearchResponse(SearchStatus.NO_MATCH, actualQuery.text(), List.of(), "");
        }

        try {
            boolean identifierQuery = looksLikeIdentifier(actualQuery.text());
            Map<DocumentKey, DocumentRow> metadataDocuments = identifierQuery
                    ? findExactDocuments(connection, actualQuery.text(), queryTerms, language)
                    : new LinkedHashMap<>();
            Map<Long, SegmentRef> segmentRefs = identifierQuery && !metadataDocuments.isEmpty()
                    ? Map.of()
                    : findFtsSegments(connection, queryTerms, language, resultLimit, segmentsPerDocument);
            // 标题和关键词已经进入 FTS；常规查询不再先对所有文档执行多组
            // 前置 LIKE。只有 FTS 没有候选时才扫描 ID、分类和来源路径元数据，
            // 保留这些字段的回退匹配，同时避免中文双字词把查询放大到 N×M。
            if (metadataDocuments.isEmpty() && segmentRefs.isEmpty()) {
                metadataDocuments.putAll(findMetadataDocuments(connection, queryTerms, language));
            }
            List<SegmentRow> segments = metadataDocuments.isEmpty()
                    ? loadSegments(connection, segmentRefs.keySet())
                    : loadSegmentsForDocuments(connection, metadataDocuments.keySet(), segmentsPerDocument);
            Map<DocumentKey, List<SegmentRow>> segmentsByDocument = new LinkedHashMap<>();
            for (SegmentRow segment : segments) {
                segmentsByDocument.computeIfAbsent(segment.key(), ignored -> new ArrayList<>()).add(segment);
            }

            // FTS 返回的是段落引用；一次批量读取其文档元数据，避免每个候选段落
            // 再开一条 SELECT。规模扩大后这条 N+1 查询会成为主要延迟来源。
            metadataDocuments.putAll(loadDocuments(connection, segmentsByDocument.keySet()));

            // 元数据命中但正文没有 FTS 命中时，取首段作为可返回的完整 Markdown 上下文。
            Set<DocumentKey> firstSegmentKeys = new LinkedHashSet<>();
            for (DocumentKey key : metadataDocuments.keySet()) {
                if (segmentsByDocument.containsKey(key)) {
                    continue;
                }
                firstSegmentKeys.add(key);
            }
            for (Map.Entry<DocumentKey, SegmentRow> entry : loadFirstSegments(connection, firstSegmentKeys).entrySet()) {
                segmentsByDocument.put(entry.getKey(), List.of(entry.getValue()));
            }

            Map<String, SearchResult> bestByDocument = new LinkedHashMap<>();
            Map<String, Integer> bestPriorities = new HashMap<>();
            Map<String, String> bestLanguages = new HashMap<>();
            Map<String, List<ScoredSegment>> expandedByDocument = new LinkedHashMap<>();
            for (Map.Entry<DocumentKey, List<SegmentRow>> entry : segmentsByDocument.entrySet()) {
                DocumentRow document = metadataDocuments.get(entry.getKey());
                if (document == null) {
                    document = loadDocument(connection, entry.getKey());
                }
                if (document == null) {
                    continue;
                }
                DbScore metadataScore = scoreMetadata(document, queryTerms);
                for (SegmentRow segment : entry.getValue()) {
                    SearchResult candidate = score(document, segment, queryTerms, metadataScore);
                    if (candidate.score() <= 0) {
                        continue;
                    }
                    if (segmentsPerDocument == 1) {
                        SearchResult previous = bestByDocument.get(candidate.documentId());
                        if (previous == null || better(
                                candidate,
                                document,
                                previous,
                                bestPriorities.getOrDefault(candidate.documentId(), Integer.MIN_VALUE),
                                bestLanguages.get(candidate.documentId()),
                                language
                        )) {
                            bestByDocument.put(candidate.documentId(), candidate);
                            bestPriorities.put(candidate.documentId(), document.priority());
                            bestLanguages.put(candidate.documentId(), document.language());
                        }
                    } else {
                        expandedByDocument.computeIfAbsent(candidate.documentId(), ignored -> new ArrayList<>())
                                .add(new ScoredSegment(document, segment, candidate));
                    }
                }
            }

            List<SearchResult> results = segmentsPerDocument == 1
                    ? new ArrayList<>(bestByDocument.values())
                    : selectExpandedResults(expandedByDocument, language, segmentsPerDocument);
            results = refineCjkResults(results, actualQuery.text());
            results.sort(Comparator
                    .comparingInt(SearchResult::score)
                    .reversed()
                    .thenComparing(SearchResult::documentId));
            if (results.size() > resultLimit) {
                results = new ArrayList<>(results.subList(0, resultLimit));
            }
            return new SearchResponse(
                    results.isEmpty() ? SearchStatus.NO_MATCH : SearchStatus.READY,
                    actualQuery.text(),
                    results,
                    ""
            );
        } catch (SQLException | RuntimeException exception) {
            return new SearchResponse(
                    SearchStatus.INDEX_ERROR,
                    actualQuery.text(),
                    List.of(),
                    "读取 SQLite 知识库失败：" + (exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage())
            );
        }
    }

    private static List<SearchResult> selectExpandedResults(
            Map<String, List<ScoredSegment>> candidatesByDocument,
            SearchLanguage language,
            int segmentsPerDocument
    ) {
        List<SearchResult> results = new ArrayList<>();
        for (List<ScoredSegment> candidates : candidatesByDocument.values()) {
            candidates.sort(Comparator
                    .comparingInt((ScoredSegment value) -> value.result().score())
                    .reversed()
                    .thenComparing(Comparator.comparingInt((ScoredSegment value) -> value.document().priority())
                            .reversed())
                    .thenComparingInt(value -> value.document().language().equals(language.code()) ? 0 : 1)
                    .thenComparingInt(value -> value.segment().index())
                    .thenComparing(value -> value.document().language())
                    .thenComparing(value -> value.segment().headingPath()));
            if (candidates.isEmpty()) {
                continue;
            }

            // 同一 document_id 的中英文页面只保留一个语言版本；语言回退仍由
            // 当前语言优先级和原有 score 规则决定，避免补搜同时塞入两份正文。
            String selectedLanguage = candidates.get(0).document().language();
            int selected = 0;
            for (ScoredSegment candidate : candidates) {
                if (!selectedLanguage.equals(candidate.document().language())) {
                    continue;
                }
                results.add(candidate.result());
                if (++selected >= segmentsPerDocument) {
                    break;
                }
            }
        }
        return results;
    }

    /** 中文连续句先按完整实体短语收窄，避免仅命中双字片段的泛相关页面进入结果。 */
    private static List<SearchResult> refineCjkResults(List<SearchResult> results, String query) {
        List<String> phrases = SearchTextNormalizer.semanticCjkPhrases(query);
        List<String> compoundPhrases = phrases.stream()
                .filter(phrase -> phrase.length() >= 3)
                .toList();
        if (compoundPhrases.isEmpty() || results.isEmpty()) {
            return results;
        }
        List<SearchResult> matched = results.stream()
                .filter(result -> compoundPhrases.stream()
                        .anyMatch(phrase -> matchesCjkPhrase(result, phrase)))
                .toList();
        // stream().toList() 返回不可变列表；调用方还会按稳定规则排序和截断，
        // 因此这里必须返回可变副本，避免中文查询在最终排序阶段触发异常。
        return new ArrayList<>(matched);
    }

    private static boolean matchesCjkPhrase(SearchResult result, String phrase) {
        return containsCjkPhrase(result.documentId(), phrase)
                || containsCjkPhrase(result.title(), phrase)
                || containsCjkPhrase(result.sourcePath(), phrase)
                || containsCjkPhrase(result.headingPath(), phrase)
                || containsCjkPhrase(result.segmentMarkdown(), phrase)
                || containsCjkPhrase(String.join(" ", result.matchedTerms()), phrase);
    }

    private static boolean containsCjkPhrase(String value, String phrase) {
        String normalizedValue = SearchTextNormalizer.normalizeField(value);
        String normalizedPhrase = SearchTextNormalizer.normalizeField(phrase);
        if (normalizedValue.contains(normalizedPhrase)) {
            return true;
        }
        // 允许“压力 容器”这类只由空格/标点分隔的紧凑写法，但只在同一个字段
        // 内判断，不能把标题末尾和正文开头拼成一个不存在的实体。
        return SearchTextNormalizer.compact(normalizedValue)
                .contains(SearchTextNormalizer.compact(normalizedPhrase));
    }

    private static boolean looksLikeIdentifier(String text) {
        if (text == null) {
            return false;
        }
        return text.indexOf(':') >= 0
                || text.indexOf('/') >= 0
                || text.indexOf('\\') >= 0
                || text.startsWith("item.")
                || text.startsWith("block.");
    }

    private static Map<DocumentKey, DocumentRow> findExactDocuments(
            Connection connection,
            String rawText,
            SearchTextNormalizer.QueryTerms query,
            SearchLanguage language
    ) throws SQLException {
        String sql = """
                SELECT document_id, language, source_key, fingerprint, priority,
                       source_mod, source_type, title, category, keywords_json,
                       source_version, source_path, markdown, id_normalized,
                       title_normalized, keywords_normalized, other_normalized
                FROM documents
                WHERE
                """;
        StringBuilder statementSql = new StringBuilder(sql);
        appendLanguageFilter(statementSql, language, "language");
        statementSql.append(" AND (document_id = ? OR document_id = ? OR id_normalized IN (?, ?, ?))");
        Map<DocumentKey, DocumentRow> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(statementSql.toString())) {
            String normalizedRaw = SearchTextNormalizer.normalizeRaw(rawText).strip();
            statement.setString(1, rawText == null ? "" : rawText.strip());
            statement.setString(2, normalizedRaw);
            statement.setString(3, normalizedRaw);
            statement.setString(4, SearchTextNormalizer.normalizeField(query.phrase()));
            statement.setString(5, SearchTextNormalizer.compact(query.phrase()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DocumentRow document = readDocumentRow(rows);
                    result.put(document.key(), document);
                }
            }
        }
        return result;
    }

    private static Map<DocumentKey, DocumentRow> findMetadataDocuments(
            Connection connection,
            SearchTextNormalizer.QueryTerms query,
            SearchLanguage language
    ) throws SQLException {
        List<SearchTextNormalizer.QueryTerm> terms = new ArrayList<>(query.terms());
        if (!query.phrase().isBlank()) {
            terms.add(new SearchTextNormalizer.QueryTerm(query.phrase(), query.phrase(), false));
        }
        StringBuilder sql = new StringBuilder("""
                SELECT document_id, language, source_key, fingerprint, priority,
                       source_mod, source_type, title, category, keywords_json,
                       source_version, source_path, markdown, id_normalized,
                       title_normalized, keywords_normalized, other_normalized
                FROM documents
                WHERE
                """);
        appendLanguageFilter(sql, language, "language");
        sql.append(" AND (");
        for (int index = 0; index < terms.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append("id_normalized LIKE ? OR title_normalized LIKE ? OR keywords_normalized LIKE ? OR other_normalized LIKE ?");
        }
        sql.append(')');

        Map<DocumentKey, DocumentRow> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            for (SearchTextNormalizer.QueryTerm term : terms) {
                String value = "%" + SearchTextNormalizer.normalizeField(term.value()) + "%";
                statement.setString(parameter++, value);
                statement.setString(parameter++, value);
                statement.setString(parameter++, value);
                statement.setString(parameter++, value);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DocumentRow document = readDocumentRow(rows);
                    result.put(document.key(), document);
                }
            }
        }
        return result;
    }

    private static Map<Long, SegmentRef> findFtsSegments(
            Connection connection,
            SearchTextNormalizer.QueryTerms query,
            SearchLanguage language,
            int limit,
            int segmentsPerDocument
    ) throws SQLException {
        String match = ftsMatch(query);
        if (match.isBlank()) {
            return Map.of();
        }
        String languageSql = language == SearchLanguage.NEUTRAL
                ? "language = ?"
                : "language IN (?, 'neutral')";
        String sql = "SELECT segment_id, document_id, language FROM segments_fts "
                + "WHERE segments_fts MATCH ? AND " + languageSql
                + " ORDER BY bm25(segments_fts) LIMIT ?";
        Map<Long, SegmentRef> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, match);
            statement.setString(2, language.code());
            // 结果在 Java 侧还会按文档去重，并且中文多实体查询可能需要从多个
            // 页面中筛出完整短语。扩大候选窗口可以避免通用双字词把真正实体挤出
            // 候选集；上限仍然固定，保证大型整合包下不会把整张 FTS 表搬进内存。
            statement.setInt(3, Math.max(32, Math.min(1024,
                    Math.max(1, limit) * 12 * Math.max(1, segmentsPerDocument))));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long segmentId = rows.getLong("segment_id");
                    result.put(segmentId, new SegmentRef(
                            segmentId,
                            rows.getString("document_id"),
                            rows.getString("language")
                    ));
                }
            }
        }
        return result;
    }

    private static List<SegmentRow> loadSegments(Connection connection, Set<Long> segmentIds) throws SQLException {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(segmentIds.size(), "?"));
        String sql = "SELECT segment_id, document_id, language, segment_index, "
                + "heading_path, markdown, normalized_text FROM segments "
                + "WHERE segment_id IN (" + placeholders + ")"
                + " ORDER BY document_id, language, segment_index";
        List<SegmentRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (Long segmentId : segmentIds) {
                statement.setLong(parameter++, segmentId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readSegmentRow(rows));
                }
            }
        }
        return result;
    }

    private static List<SegmentRow> loadSegmentsForDocuments(
            Connection connection,
            Set<DocumentKey> keys,
            int maxPerDocument
    ) throws SQLException {
        if (keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> documentIds = new LinkedHashSet<>();
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        for (DocumentKey key : keys) {
            documentIds.add(key.documentId());
            languages.add(key.language());
        }
        String idPlaceholders = String.join(", ", java.util.Collections.nCopies(documentIds.size(), "?"));
        String languagePlaceholders = String.join(", ", java.util.Collections.nCopies(languages.size(), "?"));
        String sql = "SELECT segment_id, document_id, language, segment_index, "
                + "heading_path, markdown, normalized_text FROM segments "
                + "WHERE document_id IN (" + idPlaceholders + ") "
                + "AND language IN (" + languagePlaceholders + ") "
                + "ORDER BY document_id, language, segment_index";
        Map<DocumentKey, Integer> counts = new HashMap<>();
        List<SegmentRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String documentId : documentIds) {
                statement.setString(parameter++, documentId);
            }
            for (String language : languages) {
                statement.setString(parameter++, language);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SegmentRow segment = readSegmentRow(rows);
                    if (!keys.contains(segment.key())) {
                        continue;
                    }
                    int count = counts.getOrDefault(segment.key(), 0);
                    if (count >= maxPerDocument) {
                        continue;
                    }
                    result.add(segment);
                    counts.put(segment.key(), count + 1);
                }
            }
        }
        return result;
    }

    private static Map<DocumentKey, DocumentRow> loadDocuments(
            Connection connection,
            Set<DocumentKey> keys
    ) throws SQLException {
        if (keys.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> documentIds = new LinkedHashSet<>();
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        for (DocumentKey key : keys) {
            documentIds.add(key.documentId());
            languages.add(key.language());
        }
        String idPlaceholders = String.join(", ", java.util.Collections.nCopies(documentIds.size(), "?"));
        String languagePlaceholders = String.join(", ", java.util.Collections.nCopies(languages.size(), "?"));
        String sql = """
                SELECT document_id, language, source_key, fingerprint, priority,
                       source_mod, source_type, title, category, keywords_json,
                       source_version, source_path, markdown, id_normalized,
                       title_normalized, keywords_normalized, other_normalized
                FROM documents
                WHERE document_id IN (""" + idPlaceholders + ") AND language IN (" + languagePlaceholders + ")";
        Map<DocumentKey, DocumentRow> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String documentId : documentIds) {
                statement.setString(parameter++, documentId);
            }
            for (String language : languages) {
                statement.setString(parameter++, language);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DocumentRow document = readDocumentRow(rows);
                    result.put(document.key(), document);
                }
            }
        }
        return result;
    }

    private static Map<DocumentKey, SegmentRow> loadFirstSegments(
            Connection connection,
            Set<DocumentKey> keys
    ) throws SQLException {
        if (keys.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> documentIds = new LinkedHashSet<>();
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        for (DocumentKey key : keys) {
            documentIds.add(key.documentId());
            languages.add(key.language());
        }
        String idPlaceholders = String.join(", ", java.util.Collections.nCopies(documentIds.size(), "?"));
        String languagePlaceholders = String.join(", ", java.util.Collections.nCopies(languages.size(), "?"));
        String sql = "SELECT segment_id, document_id, language, segment_index, "
                + "heading_path, markdown, normalized_text FROM segments "
                + "WHERE segment_index = 0 AND document_id IN (" + idPlaceholders + ") "
                + "AND language IN (" + languagePlaceholders + ")";
        Map<DocumentKey, SegmentRow> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String documentId : documentIds) {
                statement.setString(parameter++, documentId);
            }
            for (String language : languages) {
                statement.setString(parameter++, language);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SegmentRow segment = readSegmentRow(rows);
                    if (keys.contains(segment.key())) {
                        result.put(segment.key(), segment);
                    }
                }
            }
        }
        return result;
    }

    private static DocumentRow loadDocument(Connection connection, DocumentKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT document_id, language, source_key, fingerprint, priority,
                       source_mod, source_type, title, category, keywords_json,
                       source_version, source_path, markdown, id_normalized,
                       title_normalized, keywords_normalized, other_normalized
                FROM documents WHERE document_id = ? AND language = ?
                """)) {
            statement.setString(1, key.documentId());
            statement.setString(2, key.language());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readDocumentRow(rows) : null;
            }
        }
    }

    private static SearchResult score(
            DocumentRow document,
            SegmentRow segment,
            SearchTextNormalizer.QueryTerms query,
            DbScore metadataScore
    ) {
        MatchAccumulator accumulator = new MatchAccumulator(metadataScore);
        scorePhrase(segment.profile(), query, 560, accumulator);
        for (SearchTextNormalizer.QueryTerm term : query.terms()) {
            scoreField(segment.profile(), term, 340, 170, accumulator);
        }
        accumulator.addCoverageBonus(query.primaryTermCount());
        accumulator.score += document.priority() * 10;
        return new SearchResult(
                document.documentId(),
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
    }

    private static DbScore scoreMetadata(
            DocumentRow document,
            SearchTextNormalizer.QueryTerms query
    ) {
        MatchAccumulator metadata = new MatchAccumulator();
        scorePhrase(document.idProfile(), query, 1000, metadata);
        scorePhrase(document.titleProfile(), query, 900, metadata);
        scorePhrase(document.sourceModProfile(), query, 680, metadata);
        scorePhrase(document.keywordProfile(), query, 620, metadata);
        scorePhrase(document.otherProfile(), query, 260, metadata);
        for (SearchTextNormalizer.QueryTerm term : query.terms()) {
            scoreField(document.idProfile(), term, 820, 480, metadata);
            scoreField(document.titleProfile(), term, 720, 420, metadata);
            scoreField(document.sourceModProfile(), term, 540, 300, metadata);
            scoreField(document.keywordProfile(), term, 580, 300, metadata);
            scoreField(document.otherProfile(), term, 240, 120, metadata);
        }
        return metadata.toScore();
    }

    private static boolean better(
            SearchResult candidate,
            DocumentRow candidateDocument,
            SearchResult previous,
            int previousPriority,
            String previousLanguage,
            SearchLanguage language
    ) {
        if (candidate.score() != previous.score()) {
            return candidate.score() > previous.score();
        }
        if (candidateDocument.priority() != previousPriority) {
            return candidateDocument.priority() > previousPriority;
        }
        boolean candidateLanguage = candidateDocument.language().equals(language.code());
        return candidateLanguage && !language.code().equals(previousLanguage);
    }

    private static void scorePhrase(TextProfile profile, SearchTextNormalizer.QueryTerms query, int weight, MatchAccumulator accumulator) {
        String phrase = SearchTextNormalizer.normalizeField(query.phrase());
        String compact = SearchTextNormalizer.compact(query.phrase());
        if (phrase.isBlank()) {
            return;
        }
        if (profile.normalized().equals(phrase)) {
            accumulator.addPhrase(weight, phrase);
        } else if (profile.normalized().contains(phrase)
                || compact.length() >= 2 && profile.compact().contains(compact)) {
            accumulator.addPhrase(Math.max(1, weight * 3 / 4), phrase);
        }
    }

    private static void scoreField(
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

    private static String ftsMatch(SearchTextNormalizer.QueryTerms query) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (SearchTextNormalizer.QueryTerm term : query.terms()) {
            String value = SearchTextNormalizer.normalizeField(term.value());
            if (!value.isBlank()) {
                values.add('"' + value.replace("\"", "\"\"") + '"');
            }
        }
        return String.join(" OR ", values);
    }

    private static void appendLanguageFilter(StringBuilder sql, SearchLanguage language, String column) {
        if (language == SearchLanguage.NEUTRAL) {
            sql.append(column).append(" = 'neutral'");
        } else {
            sql.append(column).append(" IN ('").append(language.code()).append("', 'neutral')");
        }
    }

    private static DocumentRow readDocumentRow(ResultSet rows) throws SQLException {
        return new DocumentRow(
                new DocumentKey(rows.getString("document_id"), rows.getString("language")),
                rows.getString("source_key"),
                rows.getString("fingerprint"),
                rows.getInt("priority"),
                rows.getString("source_mod"),
                rows.getString("source_type"),
                rows.getString("title"),
                rows.getString("category"),
                rows.getString("source_version"),
                rows.getString("source_path"),
                TextProfile.fromNormalized(rows.getString("id_normalized")),
                TextProfile.fromNormalized(rows.getString("title_normalized")),
                TextProfile.fromNormalized(normalized(rows.getString("source_mod"))),
                TextProfile.fromNormalized(rows.getString("keywords_normalized")),
                TextProfile.fromNormalized(rows.getString("other_normalized"))
        );
    }

    private static SegmentRow readSegmentRow(ResultSet rows) throws SQLException {
        return new SegmentRow(
                rows.getLong("segment_id"),
                new DocumentKey(rows.getString("document_id"), rows.getString("language")),
                rows.getInt("segment_index"),
                rows.getString("heading_path"),
                rows.getString("markdown"),
                TextProfile.fromNormalized(rows.getString("normalized_text"))
        );
    }

    private static void appendLanguageFilter(StringBuilder sql, SearchLanguage language) {
        appendLanguageFilter(sql, language, "language");
    }

    private record DocumentKey(String documentId, String language) {
    }

    private record SegmentRef(long segmentId, String documentId, String language) {
    }

    private record DocumentRow(
            DocumentKey key,
            String sourceKey,
            String fingerprint,
            int priority,
            String sourceMod,
            String sourceType,
            String title,
            String category,
            String sourceVersion,
            String sourcePath,
            TextProfile idProfile,
            TextProfile titleProfile,
            TextProfile sourceModProfile,
            TextProfile keywordProfile,
            TextProfile otherProfile
    ) {
        String documentId() {
            return key.documentId();
        }

        String language() {
            return key.language();
        }
    }

    private record SegmentRow(
            long segmentId,
            DocumentKey key,
            int index,
            String headingPath,
            String markdown,
            TextProfile profile
    ) {
    }

    private record ScoredSegment(
            DocumentRow document,
            SegmentRow segment,
            SearchResult result
    ) {
    }

    private record TextProfile(String normalized, String compact, Set<String> tokens) {
        static TextProfile fromNormalized(String value) {
            String normalized = value == null ? "" : value.trim();
            Set<String> tokens = new LinkedHashSet<>();
            if (!normalized.isBlank()) {
                for (String token : normalized.split("\\s+")) {
                    if (!token.isBlank()) {
                        tokens.add(token);
                    }
                }
            }
            return new TextProfile(normalized, normalized.replace(" ", ""), Set.copyOf(tokens));
        }
    }

    private record DbScore(int score, List<String> matchedTerms, Set<String> matchedOrigins) {
    }

    private static final class MatchAccumulator {
        private int score;
        private final LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> matchedOrigins = new LinkedHashSet<>();

        private MatchAccumulator() {
        }

        private MatchAccumulator(DbScore base) {
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

        private DbScore toScore() {
            return new DbScore(score, new ArrayList<>(matchedTerms), new LinkedHashSet<>(matchedOrigins));
        }
    }

    private static SyncResult syncStaged(
            Path staged,
            Collection<DocumentInput> rawInputs,
            boolean forceRebuild
    ) throws SQLException {
        Map<DocumentKey, DocumentInput> inputs = effectiveInputs(rawInputs);
        try (Connection connection = open(staged, false)) {
            createSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<DocumentKey, ExistingDocument> existing = readExisting(connection);
                int updated = 0;
                int reused = 0;

                for (Map.Entry<DocumentKey, DocumentInput> entry : inputs.entrySet()) {
                    ExistingDocument previous = existing.remove(entry.getKey());
                    DocumentInput input = entry.getValue();
                    if (!forceRebuild && previous != null
                            && previous.sourceKey().equals(input.sourceKey())
                            && previous.fingerprint().equals(input.fingerprint())) {
                        reused++;
                        continue;
                    }
                    deleteDocument(connection, entry.getKey());
                    insertDocument(connection, input);
                    updated++;
                }

                for (DocumentKey removed : existing.keySet()) {
                    deleteDocument(connection, removed);
                }

                setMetadata(connection, "updated_at", Long.toString(System.currentTimeMillis()));
                setMetadata(connection, "document_count", Integer.toString(inputs.size()));
                connection.commit();
                return new SyncResult(updated, reused, existing.size(), inputs.size());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static Map<DocumentKey, DocumentInput> effectiveInputs(Collection<DocumentInput> rawInputs) {
        Map<DocumentKey, DocumentInput> result = new LinkedHashMap<>();
        if (rawInputs == null) {
            return result;
        }
        for (DocumentInput input : rawInputs) {
            DocumentKey key = new DocumentKey(input.document().id(), input.language());
            DocumentInput previous = result.get(key);
            if (previous == null
                    || input.priority() > previous.priority()
                    || input.priority() == previous.priority()
                    && input.sourceKey().compareTo(previous.sourceKey()) < 0) {
                result.put(key, input);
            }
        }
        return result;
    }

    private static Map<DocumentKey, ExistingDocument> readExisting(Connection connection) throws SQLException {
        Map<DocumentKey, ExistingDocument> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT document_id, language, source_key, fingerprint FROM documents")) {
            while (rows.next()) {
                result.put(
                        new DocumentKey(rows.getString("document_id"), rows.getString("language")),
                        new ExistingDocument(rows.getString("source_key"), rows.getString("fingerprint"))
                );
            }
        }
        return result;
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = DELETE");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS documents (
                        document_id TEXT NOT NULL,
                        language TEXT NOT NULL,
                        source_key TEXT NOT NULL UNIQUE,
                        fingerprint TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        source_mod TEXT NOT NULL,
                        source_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        category TEXT NOT NULL,
                        keywords_json TEXT NOT NULL,
                        source_version TEXT NOT NULL,
                        source_path TEXT NOT NULL,
                        markdown TEXT NOT NULL,
                        id_normalized TEXT NOT NULL,
                        title_normalized TEXT NOT NULL,
                        keywords_normalized TEXT NOT NULL,
                        other_normalized TEXT NOT NULL,
                        PRIMARY KEY (document_id, language)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS segments (
                        segment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        document_id TEXT NOT NULL,
                        language TEXT NOT NULL,
                        segment_index INTEGER NOT NULL,
                        heading_path TEXT NOT NULL,
                        markdown TEXT NOT NULL,
                        normalized_text TEXT NOT NULL,
                        UNIQUE (document_id, language, segment_index),
                        FOREIGN KEY (document_id, language)
                            REFERENCES documents(document_id, language)
                            ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS segments_fts USING fts5(
                        segment_id UNINDEXED,
                        document_id UNINDEXED,
                        language UNINDEXED,
                        title,
                        keywords,
                        heading_path,
                        content,
                        tokenize = 'unicode61 remove_diacritics 2'
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_source ON documents(source_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_id ON documents(document_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_id_normalized ON documents(id_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_segments_document ON segments(document_id, language)");
        }
        setMetadata(connection, "schema_version", Integer.toString(SCHEMA_VERSION));
    }

    private static void insertDocument(Connection connection, DocumentInput input) throws SQLException {
        KnowledgeDocument document = input.document();
        String keywordsJson = JSON.toJson(document.keywords());
        String idNormalized = normalized(document.id());
        String titleNormalized = normalized(document.title());
        String keywordsNormalized = normalized(String.join(" ", document.keywords()));
        String otherNormalized = normalized(
                document.category() + " " + document.sourcePath() + " " + document.sourceMod()
        );

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO documents(
                    document_id, language, source_key, fingerprint, relative_path, priority,
                    source_mod, source_type, title, category, keywords_json, source_version,
                    source_path, markdown, id_normalized, title_normalized,
                    keywords_normalized, other_normalized
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, document.id());
            statement.setString(2, input.language());
            statement.setString(3, input.sourceKey());
            statement.setString(4, input.fingerprint());
            statement.setString(5, input.relativePath());
            statement.setInt(6, input.priority());
            statement.setString(7, document.sourceMod());
            statement.setString(8, document.sourceType());
            statement.setString(9, document.title());
            statement.setString(10, document.category());
            statement.setString(11, keywordsJson);
            statement.setString(12, document.sourceVersion());
            statement.setString(13, document.sourcePath());
            statement.setString(14, document.body());
            statement.setString(15, idNormalized);
            statement.setString(16, titleNormalized);
            statement.setString(17, keywordsNormalized);
            statement.setString(18, otherNormalized);
            statement.executeUpdate();
        }

        List<MarkdownSegmenter.MarkdownSegment> segments = MarkdownSegmenter.split(document.body());
        try (PreparedStatement segmentStatement = connection.prepareStatement("""
                INSERT INTO segments(
                    document_id, language, segment_index, heading_path, markdown, normalized_text
                ) VALUES (?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement ftsStatement = connection.prepareStatement("""
                INSERT INTO segments_fts(
                    segment_id, document_id, language, title, keywords, heading_path, content
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (MarkdownSegmenter.MarkdownSegment segment : segments) {
                segmentStatement.setString(1, document.id());
                segmentStatement.setString(2, input.language());
                segmentStatement.setInt(3, segment.index());
                segmentStatement.setString(4, segment.headingPath());
                segmentStatement.setString(5, segment.markdown());
                segmentStatement.setString(6, normalized(segment.markdown()));
                segmentStatement.executeUpdate();

                long segmentId;
                try (Statement idStatement = connection.createStatement();
                     ResultSet keys = idStatement.executeQuery("SELECT last_insert_rowid()")) {
                    keys.next();
                    segmentId = keys.getLong(1);
                }
                ftsStatement.setLong(1, segmentId);
                ftsStatement.setString(2, document.id());
                ftsStatement.setString(3, input.language());
                ftsStatement.setString(4, ftsText(document.title()));
                ftsStatement.setString(5, ftsText(String.join(" ", document.keywords())));
                ftsStatement.setString(6, ftsText(segment.headingPath()));
                ftsStatement.setString(7, ftsText(segment.markdown()));
                ftsStatement.executeUpdate();
            }
        }
    }

    private static void deleteDocument(Connection connection, DocumentKey key) throws SQLException {
        List<Long> segmentIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT segment_id FROM segments WHERE document_id = ? AND language = ?")) {
            statement.setString(1, key.documentId());
            statement.setString(2, key.language());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    segmentIds.add(rows.getLong(1));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM segments_fts WHERE segment_id = ?")) {
            for (Long segmentId : segmentIds) {
                statement.setLong(1, segmentId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM documents WHERE document_id = ? AND language = ?")) {
            statement.setString(1, key.documentId());
            statement.setString(2, key.language());
            statement.executeUpdate();
        }
    }

    private static void setMetadata(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO metadata(key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Connection open(Path databasePath, boolean readOnly) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC 驱动未加载", exception);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            if (readOnly) {
                statement.execute("PRAGMA query_only = ON");
            }
        }
        return connection;
    }

    private static void replaceDatabase(Path staged, Path database) throws IOException {
        try {
            Files.move(
                    staged,
                    database,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(staged, database, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> parseKeywords(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    result.add(element.getAsString());
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return String.join(" ", SearchTextNormalizer.tokens(value));
    }

    /**
     * unicode61 会将连续中文视为一个 token。查询端会生成相邻双字词，因此索引端
     * 也要把中文字符分隔开，否则实体只出现在正文时会因标题/关键词没有命中而漏搜。
     * 仅改变 FTS 派生列，documents.markdown 和 segments.normalized_text 仍保留原格式。
     */
    private static String ftsText(String value) {
        String raw = SearchTextNormalizer.normalizeRaw(value);
        if (raw.isBlank()) {
            return "";
        }
        StringBuilder separated = new StringBuilder(raw.length() + 16);
        StringBuilder cjkRun = new StringBuilder();
        raw.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                cjkRun.appendCodePoint(codePoint);
                return;
            }
            appendCjkRun(separated, cjkRun);
            separated.appendCodePoint(codePoint);
        });
        appendCjkRun(separated, cjkRun);
        return SearchTextNormalizer.normalizeField(separated.toString());
    }

    private static void appendCjkRun(StringBuilder output, StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        // 同时保留完整中文串和相邻双字词：前者支持完整实体，后者支持实体出现在
        // 正文且查询包含自然语言后缀的情况。这样不依赖 unicode61 对中文分词的猜测。
        output.append(run).append(' ');
        for (int index = 0; index + 1 < run.length(); index++) {
            output.append(run, index, index + 2).append(' ');
        }
        run.setLength(0);
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x9fff;
    }

    private static String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "neutral";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("zh")) {
            return "zh_cn";
        }
        if (normalized.startsWith("en")) {
            return "en_us";
        }
        return "neutral";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private record ExistingDocument(String sourceKey, String fingerprint) {
    }
}
