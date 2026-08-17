package io.ctyx.modpedia.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ctyx.modpedia.search.KnowledgeDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 任务运行数据的统一 SQLite 适配层；物理文件仍是 knowledge.db。 */
public final class TaskKnowledgeStore {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int FILTERED_CANDIDATE_PAGE_SIZE = 64;
    /** 可检索的静态/测试快照；旧版 ftbquests:<world> 运行时快照不在此集合内。 */
    private static final String STATIC_SNAPSHOT_PREDICATE =
            "s.source_key LIKE 'ftbquests:static:%'";
    private static final String LEGACY_RUNTIME_PREDICATE =
            "(source_key LIKE 'ftbquests:%' AND source_key NOT LIKE 'ftbquests:static:%')"
                    + " OR source_key LIKE 'task:%'";
    private final Path knowledgeRoot;
    private final Consumer<TaskQuery> queryObserver;

    public TaskKnowledgeStore(Path knowledgeRoot) {
        this(knowledgeRoot, ignored -> { });
    }

    /**
     * 供纯 Java 回归测试观察“运行时进度读取 → 静态任务查询”的边界。
     * 生产调用方使用默认构造器，不增加任何额外行为。
     */
    TaskKnowledgeStore(Path knowledgeRoot, Consumer<TaskQuery> queryObserver) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.queryObserver = queryObserver == null ? ignored -> { } : queryObserver;
    }

    public void syncSnapshot(TaskSnapshot snapshot) throws java.io.IOException {
        if (snapshot == null || !snapshot.sourceKey().startsWith("ftbquests:static:")) {
            // 运行时快照只允许通过 query(runtimeSnapshot) 临时传入；拒绝把
            // 旧版 ftbquests:<world> 或早期 task:<id> 数据重新写回 knowledge.db。
            return;
        }
        KnowledgeDatabase.writeTransaction(knowledgeRoot, connection -> {
            deleteLegacyRuntimeSnapshots(connection);
            deleteSnapshot(connection, snapshot.sourceKey());
            insertSnapshot(connection, snapshot);
            insertSource(connection, snapshot);
            return null;
        });
    }

    /**
     * 清理早期客户端适配器留下的 ftbquests:<world> 运行时快照。
     * 运行时进度现在只从存档文件/TeamData 临时读取，不应留在 knowledge.db。
     */
    public void cleanupLegacyRuntimeSnapshots() throws java.io.IOException {
        KnowledgeDatabase.writeTransaction(knowledgeRoot, connection -> {
            deleteLegacyRuntimeSnapshots(connection);
            return null;
        });
    }

    /**
     * 原子替换 Worker 导入的静态任务章节。
     *
     * <p>章节定义按 sourceKey 增量替换；本次扫描中已经消失的章节同步删除。
     * 这里没有运行时进度参数，避免把玩家状态写入统一数据库。</p>
     */
    public void syncStaticSnapshots(Collection<TaskSnapshot> snapshots) throws java.io.IOException {
        List<TaskSnapshot> current = snapshots == null
                ? List.of()
                : snapshots.stream()
                .filter(snapshot -> snapshot != null)
                .filter(snapshot -> snapshot.sourceKey().startsWith("ftbquests:static:"))
                .toList();
        KnowledgeDatabase.writeTransaction(knowledgeRoot, connection -> {
            deleteLegacyRuntimeSnapshots(connection);
            Map<String, String> previous = staticFingerprints(connection);
            Map<String, TaskSnapshot> next = new LinkedHashMap<>();
            current.forEach(snapshot -> next.put(snapshot.sourceKey(), snapshot));

            for (String sourceKey : previous.keySet()) {
                if (!next.containsKey(sourceKey)) {
                    deleteSnapshot(connection, sourceKey);
                }
            }
            for (TaskSnapshot snapshot : next.values()) {
                if (snapshot.fingerprint().equals(previous.get(snapshot.sourceKey()))) {
                    // 指纹相同只表示任务定义无需重建；来源元数据可能因旧版异常
                    // 或手工清理而缺失，必须在增量路径补回，不能让来源列表与
                    // task_snapshots 脱节。
                    insertSource(connection, snapshot);
                    continue;
                }
                deleteSnapshot(connection, snapshot.sourceKey());
                insertSnapshot(connection, snapshot);
                insertSource(connection, snapshot);
            }
            deleteOrphanStaticSources(connection);
            return null;
        });
    }

    public TaskResponse query(TaskQuery query) {
        return query(query, null);
    }

    /**
     * 为运行时时间线补充静态任务标题。时间线本身仍只来自运行时快照，
     * 这里仅按 ID 读取静态定义，不把标题写回运行时数据。
     */
    public Map<String, List<String>> questTitleCandidates(
            Collection<String> questIds,
            Collection<String> collectionIds
    ) {
        List<String> ids = questIds == null
                ? List.of()
                : questIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> collections = collectionIds == null
                ? List.of()
                : collectionIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        try {
            KnowledgeDatabase.ensureDatabase(knowledgeRoot);
            StringBuilder sql = new StringBuilder(
                    "SELECT q.quest_id, q.title FROM task_quests q "
                            + "JOIN task_snapshots s ON s.snapshot_id = q.snapshot_id "
                            + "WHERE s.source_key LIKE 'ftbquests:static:%' "
                            + "AND q.quest_id IN (" + placeholders(ids.size()) + ")"
            );
            List<String> parameters = new ArrayList<>(ids);
            if (!collections.isEmpty()) {
                sql.append(" AND s.scope_key IN (")
                        .append("?, ".repeat(Math.max(0, collections.size() - 1)))
                        .append("?)");
                parameters.addAll(collections);
            }
            sql.append(" ORDER BY q.quest_id, q.title");
            try (Connection connection = openRead();
                 PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < parameters.size(); index++) {
                    statement.setString(index + 1, parameters.get(index));
                }
                Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString("quest_id");
                        String title = rows.getString("title");
                        if (id != null && !id.isBlank() && title != null && !title.isBlank()) {
                            values.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(title);
                        }
                    }
                }
                Map<String, List<String>> result = new LinkedHashMap<>();
                values.forEach((id, titles) -> result.put(id, List.copyOf(titles)));
                return Map.copyOf(result);
            }
        } catch (Exception exception) {
            return Map.of();
        }
    }

    /**
     * 查询静态任务定义，并可用一次性的玩家运行时快照覆盖状态。
     *
     * <p>运行时快照由 FTBQ 在本次查询开始时读取，整个方法只读 SQLite；查询结束后
     * 快照由调用方释放。数据库不会保存或读取玩家的局部进度。</p>
     */
    public TaskResponse query(TaskQuery query, TaskRuntimeSnapshot runtimeSnapshot) {
        TaskQuery actual = query == null ? TaskQuery.search("") : query;
        if (actual.mode() == TaskQueryMode.WIKI) {
            return new TaskResponse(TaskStatus.NO_MATCH, actual, List.of(), "Wiki 由 search_wiki 查询");
        }
        if (actual.mode() != TaskQueryMode.NEXT
                && actual.mode() != TaskQueryMode.BLOCKED
                && actual.text().isBlank()
                && actual.questId().isBlank()) {
            return new TaskResponse(TaskStatus.EMPTY_QUERY, actual, List.of(), "");
        }
        try {
            // 运行时快照必须由 search_tasks 在进入这里之前取得；本方法只负责
            // 在快照已经交付后读取静态定义并在内存中覆盖结果。
            queryObserver.accept(actual);
            KnowledgeDatabase.ensureDatabase(knowledgeRoot);
            try (Connection connection = openRead()) {
                if (!hasSnapshots(connection)) {
                    return new TaskResponse(TaskStatus.NOT_SYNCED, actual, List.of(), "任务快照尚未同步");
                }
                RuntimeContext runtime = RuntimeContext.resolve(connection, actual, runtimeSnapshot);
                QueryResults queried = readResults(connection, actual, runtime);
                int taskDefinitionCount = countTaskDefinitions(connection, actual.collectionIds());
                return new TaskResponse(
                        queried.results().isEmpty() ? TaskStatus.NO_MATCH : TaskStatus.READY,
                        actual,
                        queried.results(),
                        "",
                        queried.hasMore(),
                        taskDefinitionCount
                );
            }
        } catch (Exception exception) {
            return new TaskResponse(TaskStatus.ERROR, actual, List.of(), messageOf(exception));
        }
    }

    private QueryResults readResults(
            Connection connection,
            TaskQuery query,
            RuntimeContext runtime
    ) throws SQLException {
        int limit = query.limit();
        int pageSize = query.mode() == TaskQueryMode.SEARCH
                || query.mode() == TaskQueryMode.DETAILS
                ? limit + 1
                : Math.min(FILTERED_CANDIDATE_PAGE_SIZE, Math.max(16, limit * 4));
        int offset = 0;
        List<TaskResult> matches = new ArrayList<>(limit + 1);
        while (true) {
            List<QuestRow> candidates = readQuests(
                    connection,
                    query,
                    offset,
                    pageSize
            );
            if (candidates.isEmpty()) {
                break;
            }
            PageContext page = PageContext.load(connection, candidates);
            for (QuestRow row : candidates) {
                TaskResult result = toResult(row, runtime, page);
                if (matchesMode(result, query.mode())) {
                    matches.add(result);
                    if (matches.size() > limit) {
                        break;
                    }
                }
            }
            if (matches.size() > limit || candidates.size() < pageSize) {
                break;
            }
            offset += candidates.size();
        }
        boolean hasMore = matches.size() > limit;
        if (hasMore) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }
        return new QueryResults(List.copyOf(matches), hasMore);
    }

    /**
     * 只从数据库取得静态候选任务 ID，不读取实时进度和完整任务正文。
     *
     * <p>这是供诊断或其他静态调用方使用的辅助接口；正式的
     * {@code search_tasks} 链路先读取 FTBQ 运行时状态，再调用带快照的查询。</p>
     */
    public List<String> candidateQuestIds(TaskQuery query, int limit) {
        TaskQuery actual = query == null ? TaskQuery.search("") : query;
        if (actual.mode() == TaskQueryMode.WIKI
                || actual.mode() != TaskQueryMode.NEXT
                && actual.text().isBlank()
                && actual.questId().isBlank()) {
            return List.of();
        }
        int actualLimit = Math.max(1, Math.min(64, limit));
        try {
            KnowledgeDatabase.ensureDatabase(knowledgeRoot);
            try (Connection connection = openRead()) {
                if (!hasSnapshots(connection)) {
                    return List.of();
                }
                StringBuilder sql = new StringBuilder("""
                        SELECT q.quest_id
                        FROM task_quests q
                        JOIN task_snapshots s ON s.snapshot_id = q.snapshot_id
                        WHERE s.source_key LIKE 'ftbquests:static:%'
                        """);
                List<String> parameters = new ArrayList<>();
                if (!actual.questId().isBlank()) {
                    sql.append(" AND q.quest_id = ?");
                    parameters.add(actual.questId());
                }
                if (!actual.text().isBlank()) {
                    String value = "%" + actual.text() + "%";
                    sql.append(" AND (q.quest_id LIKE ? OR q.title LIKE ? OR q.description_markdown LIKE ?)");
                    parameters.add(value);
                    parameters.add(value);
                    parameters.add(value);
                }
                if (!actual.collectionIds().isEmpty()) {
                    sql.append(" AND s.scope_key IN (")
                            .append("?, ".repeat(Math.max(0, actual.collectionIds().size() - 1)))
                            .append("?)");
                    parameters.addAll(actual.collectionIds());
                }
                sql.append(" GROUP BY q.quest_id ORDER BY MIN(q.sort_index), q.quest_id LIMIT ?");
                parameters.add(Integer.toString(actualLimit));
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    for (int index = 0; index < parameters.size(); index++) {
                        if (index == parameters.size() - 1) {
                            statement.setInt(index + 1, actualLimit);
                        } else {
                            statement.setString(index + 1, parameters.get(index));
                        }
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        List<String> result = new ArrayList<>();
                        while (rows.next()) {
                            result.add(rows.getString(1));
                        }
                        return List.copyOf(result);
                    }
                }
            }
        } catch (Exception ignored) {
            // 运行时适配器仍可回退到 FTBQ 的公开对象遍历；数据库异常不影响游戏。
            return List.of();
        }
    }

    private Connection openRead() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC 驱动未加载", exception);
        }
        Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + KnowledgeDatabase.path(knowledgeRoot).toAbsolutePath()
        );
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA query_only = ON");
        }
        return connection;
    }

    private void deleteSnapshot(Connection connection, String sourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM task_snapshots WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM knowledge_sources WHERE source_id = ?")) {
            statement.setString(1, "task:" + sourceKey);
            statement.executeUpdate();
        }
    }

    private void deleteLegacyRuntimeSnapshots(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM task_snapshots WHERE " + LEGACY_RUNTIME_PREDICATE);
            statement.executeUpdate(
                    "DELETE FROM knowledge_sources WHERE source_id LIKE 'task:task:%' "
                            + "OR (source_id LIKE 'task:ftbquests:%' "
                            + "AND source_id NOT LIKE 'task:ftbquests:static:%')"
            );
        }
    }

    private void deleteOrphanStaticSources(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM knowledge_sources
                WHERE source_id LIKE 'task:ftbquests:static:%'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM task_snapshots snapshot
                      WHERE snapshot.source_key LIKE 'ftbquests:static:%'
                        AND 'task:' || snapshot.source_key = knowledge_sources.source_id
                  )
                """)) {
            statement.executeUpdate();
        }
    }

    private Map<String, String> staticFingerprints(Connection connection) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_key, fingerprint FROM task_snapshots WHERE source_key LIKE ?")) {
            statement.setString(1, "ftbquests:static:%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return result;
    }

    private void insertSnapshot(Connection connection, TaskSnapshot snapshot) throws SQLException {
        insertSnapshotHeader(connection, snapshot, snapshot.snapshotId());

        for (TaskSnapshot.TaskQuest quest : snapshot.quests()) {
            insertQuest(connection, snapshot.snapshotId(), quest);
        }
    }

    private void insertSnapshotHeader(Connection connection, TaskSnapshot snapshot, String snapshotId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_snapshots(
                    snapshot_id, source_key, fingerprint, scope_key, version, updated_at, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshotId);
            statement.setString(2, snapshot.sourceKey());
            statement.setString(3, snapshot.fingerprint());
            statement.setString(4, snapshot.scopeKey());
            statement.setString(5, snapshot.version());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, snapshot.rawJson());
            statement.executeUpdate();
        }
    }

    private void insertQuest(Connection connection, String snapshotId, TaskSnapshot.TaskQuest quest)
            throws SQLException {
        try (PreparedStatement questStatement = connection.prepareStatement("""
                INSERT INTO task_quests(
                    quest_id, snapshot_id, parent_id, title, subtitle_markdown,
                    description_markdown, optional, visible, started, completed,
                    sort_index, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement dependencyStatement = connection.prepareStatement("""
                INSERT INTO task_dependencies(snapshot_id, quest_id, dependency_id, optional)
                VALUES (?, ?, ?, ?)
                """);
             PreparedStatement taskStatement = connection.prepareStatement("""
                INSERT INTO task_tasks(
                    snapshot_id, task_id, quest_id, task_type, title, target_id,
                    current_value, required_value, completed, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement rewardStatement = connection.prepareStatement("""
                INSERT INTO task_rewards(
                    snapshot_id, reward_id, quest_id, reward_type, title, guaranteed,
                    candidates_json, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            questStatement.setString(1, quest.questId());
            questStatement.setString(2, snapshotId);
            questStatement.setString(3, quest.parentId());
            questStatement.setString(4, quest.title());
            questStatement.setString(5, quest.subtitleMarkdown());
            questStatement.setString(6, quest.descriptionMarkdown());
            questStatement.setInt(7, quest.optional() ? 1 : 0);
            questStatement.setInt(8, quest.visible() ? 1 : 0);
            // 这里写入的是静态任务定义；玩家开始/完成状态只在运行时快照中存在。
            questStatement.setInt(9, 0);
            questStatement.setInt(10, 0);
            questStatement.setInt(11, quest.sortIndex());
            questStatement.setString(12, quest.rawJson());
            questStatement.executeUpdate();

            for (String dependency : quest.dependencies()) {
                dependencyStatement.setString(1, snapshotId);
                dependencyStatement.setString(2, quest.questId());
                dependencyStatement.setString(3, dependency);
                dependencyStatement.setInt(4, 0);
                dependencyStatement.addBatch();
            }
            for (TaskSnapshot.TaskRequirement task : quest.tasks()) {
                taskStatement.setString(1, snapshotId);
                taskStatement.setString(2, task.taskId());
                taskStatement.setString(3, quest.questId());
                taskStatement.setString(4, task.type());
                taskStatement.setString(5, task.title());
                taskStatement.setString(6, task.targetId());
                // current_value 是静态默认值，实时数量由 TaskRuntimeSnapshot 覆盖。
                taskStatement.setDouble(7, 0);
                taskStatement.setDouble(8, task.required());
                taskStatement.setInt(9, 0);
                taskStatement.setString(10, task.rawJson());
                taskStatement.addBatch();
            }
            for (TaskSnapshot.TaskReward reward : quest.rewards()) {
                rewardStatement.setString(1, snapshotId);
                rewardStatement.setString(2, reward.rewardId());
                rewardStatement.setString(3, quest.questId());
                rewardStatement.setString(4, reward.type());
                rewardStatement.setString(5, reward.title());
                rewardStatement.setInt(6, reward.guaranteed() ? 1 : 0);
                rewardStatement.setString(7, JSON.toJson(reward.candidates()));
                rewardStatement.setString(8, reward.rawJson());
                rewardStatement.addBatch();
            }
            dependencyStatement.executeBatch();
            taskStatement.executeBatch();
            rewardStatement.executeBatch();
        }
    }

    private void insertSource(Connection connection, TaskSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_sources(
                    source_id, collection_id, content_kind, source_type, origin_type,
                    title, language, version, origin_uri, local_root, fingerprint,
                    priority, metadata_json, updated_at
                ) VALUES (?, ?, 'task_runtime', 'task_snapshot', 'runtime', ?, 'neutral', ?, '', '', ?, 0, '{}', ?)
                ON CONFLICT(source_id) DO UPDATE SET
                    fingerprint = excluded.fingerprint,
                    version = excluded.version,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, "task:" + snapshot.sourceKey());
            statement.setString(2, "task");
            statement.setString(3, "任务运行快照");
            statement.setString(4, snapshot.version());
            statement.setString(5, snapshot.fingerprint());
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private List<QuestRow> readQuests(
            Connection connection,
            TaskQuery query,
            int offset,
            int pageSize
    ) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT q.quest_id, q.snapshot_id, s.scope_key, q.parent_id, q.title,
                       q.description_markdown, q.optional, q.visible, q.started,
                       q.completed, q.sort_index, s.source_key
                FROM task_quests q
                JOIN task_snapshots s ON s.snapshot_id = q.snapshot_id
                WHERE s.source_key LIKE 'ftbquests:static:%'
                """);
        List<Object> parameters = new ArrayList<>();
        if (!query.questId().isBlank()) {
            sql.append(" AND quest_id = ?");
            parameters.add(query.questId());
        }
        if (!query.text().isBlank()) {
            sql.append(" AND (q.quest_id LIKE ? OR q.title LIKE ? OR q.description_markdown LIKE ?)");
            String value = "%" + query.text() + "%";
            parameters.add(value);
            parameters.add(value);
            parameters.add(value);
        }
        if (!query.collectionIds().isEmpty()) {
            sql.append(" AND s.scope_key IN (")
                    .append("?, ".repeat(Math.max(0, query.collectionIds().size() - 1)))
                    .append("?)");
            parameters.addAll(query.collectionIds());
        }
        if (query.mode() == TaskQueryMode.NEXT) {
            // NEXT 的运行时状态过滤在内存中完成；这里先保持静态候选页，
            // 避免把未确认来源的 quest ID 传播到另一份任务书。
            sql.append(" AND q.visible = 1 AND q.optional = 0");
        }
        sql.append(" ORDER BY q.sort_index, q.quest_id, s.scope_key LIMIT ? OFFSET ?");
        parameters.add(pageSize);
        parameters.add(offset);
        List<QuestRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object parameter = parameters.get(index);
                if (parameter instanceof Integer integer) {
                    statement.setInt(index + 1, integer);
                } else {
                    statement.setString(index + 1, String.valueOf(parameter));
                }
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new QuestRow(
                            rows.getString("quest_id"),
                            rows.getString("snapshot_id"),
                            rows.getString("scope_key"),
                            rows.getString("source_key"),
                            rows.getString("parent_id"),
                            rows.getString("title"),
                            rows.getString("description_markdown"),
                            rows.getBoolean("optional"),
                            rows.getBoolean("visible"),
                            rows.getBoolean("started"),
                            rows.getBoolean("completed")
                    ));
                }
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        return "?, ".repeat(Math.max(0, count - 1)) + "?";
    }

    private TaskResult toResult(
            QuestRow row,
            RuntimeContext runtime,
            PageContext page
    ) {
        QuestKey key = new QuestKey(row.snapshotId(), row.questId());
        List<String> dependencies = page.dependencies().getOrDefault(key, List.of()).stream()
                .filter(dependency -> !page.isCompleted(
                        new QuestKey(row.snapshotId(), dependency), runtime
                ))
                .toList();
        List<TaskResult.TaskRequirementResult> requirements = new ArrayList<>();
        boolean runtimeProgressAvailable = false;
        boolean runtimeQuestCompleted = runtime.appliesToQuest(row.snapshotId(), row.questId())
                && runtime.snapshot().completedQuestIds().contains(row.questId());
        for (TaskRow task : page.tasks().getOrDefault(key, List.of())) {
            Double currentProgress = runtime.appliesToTask(row.snapshotId(), row.questId(), task.taskId())
                    ? runtime.snapshot().taskProgress().get(task.taskId())
                    : null;
            if (currentProgress != null) {
                runtimeProgressAvailable = true;
            }
            double current = currentProgress == null ? task.currentValue() : currentProgress;
            double required = task.requiredValue();
            boolean completed = runtimeQuestCompleted
                    || currentProgress != null && current >= required && required > 0
                    || currentProgress == null && task.completed();
            requirements.add(new TaskResult.TaskRequirementResult(
                    task.taskId(), task.type(), task.targetId(), current, required,
                    completed, task.title()
            ));
        }
        List<TaskResult.TaskRewardResult> rewards = new ArrayList<>();
        for (RewardRow reward : page.rewards().getOrDefault(key, List.of())) {
            rewards.add(new TaskResult.TaskRewardResult(
                    reward.rewardId(), reward.type(), reward.title(), reward.guaranteed(),
                    parseStrings(reward.candidatesJson())
            ));
        }
        String status = row.completed() || runtimeQuestCompleted
                ? "completed"
                : !dependencies.isEmpty()
                ? "blocked_dependency"
                : requirements.stream().allMatch(TaskResult.TaskRequirementResult::completed)
                ? "ready"
                : "blocked_requirement";
        return new TaskResult(
                row.questId(),
                row.title(),
                row.descriptionMarkdown(),
                status,
                row.visible(),
                row.optional(),
                dependencies,
                requirements,
                rewards,
                "task://" + row.scopeKey() + "/" + row.questId(),
                row.snapshotId(),
                row.scopeKey(),
                runtime.appliesToQuest(row.snapshotId(), row.questId())
                        && runtime.snapshot().hasQuestState(row.questId())
        );
    }

    private boolean matchesMode(TaskResult result, TaskQueryMode mode) {
        return switch (mode) {
            case NEXT -> !result.optional() && result.visible()
                    && !"completed".equals(result.status())
                    && !result.status().startsWith("blocked_dependency");
            case BLOCKED -> result.status().startsWith("blocked_");
            case DETAILS, SEARCH -> true;
            case WIKI -> false;
        };
    }

    private boolean hasSnapshots(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM task_snapshots WHERE source_key LIKE 'ftbquests:static:%' LIMIT 1")) {
            return rows.next();
        }
    }

    /** 统计当前查询范围内导入的静态任务定义，不把玩家运行时状态混入数量。 */
    private int countTaskDefinitions(Connection connection, Collection<String> collectionIds)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM task_quests q "
                        + "JOIN task_snapshots s ON s.snapshot_id = q.snapshot_id "
                        + "WHERE s.source_key LIKE 'ftbquests:static:%'"
        );
        List<String> parameters = new ArrayList<>();
        if (collectionIds != null && !collectionIds.isEmpty()) {
            sql.append(" AND s.scope_key IN (")
                    .append("?, ".repeat(Math.max(0, collectionIds.size() - 1)))
                    .append("?)");
            parameters.addAll(collectionIds);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Math.max(0, rows.getInt(1)) : 0;
            }
        }
    }

    private List<String> parseStrings(String value) {
        try {
            List<String> parsed = JSON.fromJson(value == null ? "[]" : value, List.class);
            return parsed == null ? List.of() : parsed.stream().map(String::valueOf).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * 一页候选任务的关联数据快照。
     *
     * <p>旧实现的 {@code toResult()} 对每个任务分别查询依赖、要求、奖励以及
     * 依赖完成状态，在宽泛的 NEXT/BLOCKED 查询中形成 N+1 往返。这里先按当前页
     * 批量读取关联表，再在内存中组装结果；运行时进度仍只存在调用栈中。</p>
     */
    private static final class PageContext {
        private final Map<QuestKey, List<String>> dependencies;
        private final Map<QuestKey, List<TaskRow>> tasks;
        private final Map<QuestKey, List<RewardRow>> rewards;
        private final Map<QuestKey, Boolean> questCompleted;

        private PageContext(
                Map<QuestKey, List<String>> dependencies,
                Map<QuestKey, List<TaskRow>> tasks,
                Map<QuestKey, List<RewardRow>> rewards,
                Map<QuestKey, Boolean> questCompleted
        ) {
            this.dependencies = dependencies;
            this.tasks = tasks;
            this.rewards = rewards;
            this.questCompleted = questCompleted;
        }

        private Map<QuestKey, List<String>> dependencies() {
            return dependencies;
        }

        private Map<QuestKey, List<TaskRow>> tasks() {
            return tasks;
        }

        private Map<QuestKey, List<RewardRow>> rewards() {
            return rewards;
        }

        private static PageContext load(Connection connection, List<QuestRow> candidates)
                throws SQLException {
            LinkedHashSet<QuestKey> candidateKeys = new LinkedHashSet<>();
            for (QuestRow row : candidates) {
                candidateKeys.add(new QuestKey(row.snapshotId(), row.questId()));
            }

            Map<QuestKey, List<String>> dependencies = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT snapshot_id, quest_id, dependency_id FROM task_dependencies WHERE "
                            + pairPredicate("snapshot_id", "quest_id", candidateKeys)
                            + " ORDER BY snapshot_id, quest_id, dependency_id")) {
                bindKeys(statement, candidateKeys);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        QuestKey key = new QuestKey(rows.getString(1), rows.getString(2));
                        dependencies.computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(rows.getString(3));
                    }
                }
            }

            LinkedHashSet<QuestKey> relatedKeys = new LinkedHashSet<>(candidateKeys);
            dependencies.forEach((key, values) -> values.forEach(value ->
                    relatedKeys.add(new QuestKey(key.snapshotId(), value))
            ));

            Map<QuestKey, List<TaskRow>> tasks = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT snapshot_id, quest_id, task_id, task_type, title, target_id,
                           current_value, required_value, completed
                    FROM task_tasks
                    WHERE """ + pairPredicate("snapshot_id", "quest_id", relatedKeys)
                            + " ORDER BY snapshot_id, quest_id, task_id")) {
                bindKeys(statement, relatedKeys);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        QuestKey key = new QuestKey(rows.getString(1), rows.getString(2));
                        tasks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TaskRow(
                                rows.getString(3), rows.getString(4), rows.getString(5),
                                rows.getString(6), rows.getDouble(7), rows.getDouble(8),
                                rows.getBoolean(9)
                        ));
                    }
                }
            }

            Map<QuestKey, List<RewardRow>> rewards = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT snapshot_id, quest_id, reward_id, reward_type, title,
                           guaranteed, candidates_json
                    FROM task_rewards
                    WHERE """ + pairPredicate("snapshot_id", "quest_id", candidateKeys)
                            + " ORDER BY snapshot_id, quest_id, reward_id")) {
                bindKeys(statement, candidateKeys);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        QuestKey key = new QuestKey(rows.getString(1), rows.getString(2));
                        rewards.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new RewardRow(
                                rows.getString(3), rows.getString(4), rows.getString(5),
                                rows.getBoolean(6), rows.getString(7)
                        ));
                    }
                }
            }

            Map<QuestKey, Boolean> questCompleted = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT snapshot_id, quest_id, completed FROM task_quests WHERE "
                            + pairPredicate("snapshot_id", "quest_id", relatedKeys))) {
                bindKeys(statement, relatedKeys);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        questCompleted.put(
                                new QuestKey(rows.getString(1), rows.getString(2)),
                                rows.getBoolean(3)
                        );
                    }
                }
            }

            return new PageContext(
                    immutableLists(dependencies),
                    immutableLists(tasks),
                    immutableLists(rewards),
                    Map.copyOf(questCompleted)
            );
        }

        private boolean isCompleted(QuestKey key, RuntimeContext runtime) {
            if (runtime.appliesToQuest(key.snapshotId(), key.questId())
                    && runtime.snapshot().completedQuestIds().contains(key.questId())) {
                return true;
            }
            if (questCompleted.getOrDefault(key, false)) {
                return true;
            }
            List<TaskRow> values = tasks.getOrDefault(key, List.of());
            if (values.isEmpty()) {
                return false;
            }
            for (TaskRow task : values) {
                Double progress = runtime.appliesToTask(key.snapshotId(), key.questId(), task.taskId())
                        ? runtime.snapshot().taskProgress().get(task.taskId())
                        : null;
                double current = progress == null ? task.currentValue() : progress;
                boolean completed = progress == null
                        ? task.completed()
                        : current >= task.requiredValue() && task.requiredValue() > 0;
                if (!completed) {
                    return false;
                }
            }
            return true;
        }

        private static String pairPredicate(
                String snapshotColumn,
                String questColumn,
                Collection<QuestKey> keys
        ) {
            if (keys.isEmpty()) {
                return "1 = 0";
            }
            return keys.stream()
                    .map(ignored -> "(" + snapshotColumn + " = ? AND " + questColumn + " = ?)")
                    .collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
        }

        private static void bindKeys(PreparedStatement statement, Collection<QuestKey> keys)
                throws SQLException {
            int parameter = 1;
            for (QuestKey key : keys) {
                statement.setString(parameter++, key.snapshotId());
                statement.setString(parameter++, key.questId());
            }
        }

        private static <T> Map<QuestKey, List<T>> immutableLists(Map<QuestKey, List<T>> values) {
            Map<QuestKey, List<T>> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Map.copyOf(result);
        }
    }

    private record QuestKey(String snapshotId, String questId) {
    }

    private record TaskRow(
            String taskId,
            String type,
            String title,
            String targetId,
            double currentValue,
            double requiredValue,
            boolean completed
    ) {
    }

    private record RewardRow(
            String rewardId,
            String type,
            String title,
            boolean guaranteed,
            String candidatesJson
    ) {
    }

    private record QuestRow(
            String questId,
            String snapshotId,
            String scopeKey,
            String sourceKey,
            String parentId,
            String title,
            String descriptionMarkdown,
            boolean optional,
            boolean visible,
            boolean started,
            boolean completed
    ) {
    }

    private record QueryResults(List<TaskResult> results, boolean hasMore) {
    }

    /** 当前查询中允许运行时状态覆盖的静态来源集合。 */
    private record RuntimeContext(
            TaskRuntimeSnapshot snapshot,
            Set<QuestKey> applicableQuestKeys,
            Set<TaskKey> applicableTaskKeys
    ) {
        private static RuntimeContext resolve(
                Connection connection,
                TaskQuery query,
                TaskRuntimeSnapshot snapshot
        ) throws SQLException {
            if (snapshot == null) {
                return empty();
            }

            List<String> exactParameters = new ArrayList<>();
            StringBuilder exactSql = new StringBuilder(
                    "SELECT s.snapshot_id FROM task_snapshots s WHERE "
                            + STATIC_SNAPSHOT_PREDICATE
            );
            if (!snapshot.sourceKey().isBlank()) {
                exactSql.append(" AND s.source_key = ?");
                exactParameters.add(snapshot.sourceKey());
            }
            if (!snapshot.scopeKey().isBlank()
                    && !isDefaultRuntimeScope(snapshot.scopeKey())) {
                exactSql.append(" AND s.scope_key = ?");
                exactParameters.add(snapshot.scopeKey());
            }
            // 工具调用可以同时限定任务来源。运行时 source_key 精确命中时也必须
            // 服从这个过滤条件，不能因为 source_key 单独命中就把 pack-a 的进度
            // 覆盖到 pack-b 的查询结果中。
            if (!query.collectionIds().isEmpty()) {
                exactSql.append(" AND s.scope_key IN (")
                        .append("?, ".repeat(Math.max(0, query.collectionIds().size() - 1)))
                        .append("?)");
                exactParameters.addAll(query.collectionIds());
            }
            Set<String> exact = snapshotIds(connection, exactSql.toString(), exactParameters);
            if (exact.size() == 1) {
                Set<QuestKey> applicableQuests = uniqueQuestKeys(connection, exact, snapshot);
                return new RuntimeContext(
                        snapshot,
                        applicableQuests,
                        uniqueTaskKeys(connection, exact, snapshot, applicableQuests, true)
                );
            }

            if (!snapshot.sourceKey().isBlank()
                    && sourceExists(connection, snapshot.sourceKey())) {
                // source_key 已经能唯一识别一份静态定义，但 scope_key 与它不符。
                // 禁止再用“恰好只有一个候选章节”的回退规则跨来源绑定。
                return empty();
            }
            if (snapshot.sourceKey().startsWith("ftbquests:static:")) {
                // 显式声明为静态来源但 source/scope 不成对时，不能把它当成
                // 普通 Worker 运行时来源降级到“唯一候选”匹配。
                return empty();
            }

            // Worker 读取的存档 source_key 通常不会等于静态章节 source_key。
            // 此时以本次查询范围和运行时携带的 quest/task ID 建立复合绑定；同一
            // ID 在多个来源出现时不绑定，避免 source/scope 或 ID 交叉污染。
            // Worker 读取的 scope_key 通常是 player/world 作用域，并不等于静态
            // 章节 scope；但如果它确实命中了静态 scope，就必须把该约束带入
            // 候选集合。否则“运行时来自 pack-b，却只在 pack-a 存在的 quest ID”
            // 会被错误地套到 pack-a 上。
            String staticScope = staticScopeExists(connection, snapshot.scopeKey())
                    ? snapshot.scopeKey()
                    : "";
            Set<String> candidates = candidateSnapshotIds(connection, query, staticScope);
            Set<QuestKey> applicableQuests = uniqueQuestKeys(connection, candidates, snapshot);
            return new RuntimeContext(
                    snapshot,
                    applicableQuests,
                    // 未能精确绑定 source 时，只有唯一的静态候选章节才允许直接
                    // 使用 task_progress；如果多个章节共用同一个 quest ID，任务
                    // ID 恰好唯一也不能绕过 quest 级来源隔离。
                    uniqueTaskKeys(
                            connection,
                            candidates,
                            snapshot,
                            applicableQuests,
                            candidates.size() == 1 && !staticScope.isBlank()
                    )
            );
        }

        private static Set<String> candidateSnapshotIds(
                Connection connection,
                TaskQuery query,
                String staticScope
        )
                throws SQLException {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.snapshot_id FROM task_snapshots s WHERE "
                            + STATIC_SNAPSHOT_PREDICATE
            );
            List<String> parameters = new ArrayList<>();
            if (!query.collectionIds().isEmpty()) {
                sql.append(" AND s.scope_key IN (")
                        .append("?, ".repeat(Math.max(0, query.collectionIds().size() - 1)))
                        .append("?)");
                parameters.addAll(query.collectionIds());
            }
            if (staticScope != null && !staticScope.isBlank()) {
                sql.append(" AND s.scope_key = ?");
                parameters.add(staticScope);
            }
            if (!query.questId().isBlank()) {
                sql.append(" AND EXISTS (SELECT 1 FROM task_quests q "
                        + "WHERE q.snapshot_id = s.snapshot_id AND q.quest_id = ?)");
                parameters.add(query.questId());
            }
            return snapshotIds(connection, sql.toString(), parameters);
        }

        private static boolean staticScopeExists(Connection connection, String scopeKey)
                throws SQLException {
            if (scopeKey == null || scopeKey.isBlank() || isDefaultRuntimeScope(scopeKey)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM task_snapshots s WHERE " + STATIC_SNAPSHOT_PREDICATE
                            + " AND s.scope_key = ? LIMIT 1")) {
                statement.setString(1, scopeKey);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        }

        private static boolean isDefaultRuntimeScope(String scopeKey) {
            return "local".equalsIgnoreCase(scopeKey);
        }

        private static Set<QuestKey> uniqueQuestKeys(
                Connection connection,
                Set<String> snapshotIds,
                TaskRuntimeSnapshot runtime
        ) throws SQLException {
            if (snapshotIds.isEmpty()
                    || runtime.startedQuestIds().isEmpty() && runtime.completedQuestIds().isEmpty()) {
                return Set.of();
            }
            Set<String> runtimeIds = new LinkedHashSet<>();
            runtimeIds.addAll(runtime.startedQuestIds());
            runtimeIds.addAll(runtime.completedQuestIds());
            String snapshotPlaceholders = placeholders(snapshotIds.size());
            String questPlaceholders = placeholders(runtimeIds.size());
            String sql = "SELECT q.snapshot_id, q.quest_id FROM task_quests q "
                    + "WHERE q.snapshot_id IN (" + snapshotPlaceholders + ") "
                    + "AND q.quest_id IN (" + questPlaceholders + ") "
                    + "GROUP BY q.quest_id HAVING COUNT(DISTINCT q.snapshot_id) = 1";
            Map<String, Set<String>> matches = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                for (String snapshotId : snapshotIds) {
                    statement.setString(parameter++, snapshotId);
                }
                for (String questId : runtimeIds) {
                    statement.setString(parameter++, questId);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        matches.computeIfAbsent(rows.getString("quest_id"), ignored -> new LinkedHashSet<>())
                                .add(rows.getString("snapshot_id"));
                    }
                }
            }
            Set<QuestKey> result = new LinkedHashSet<>();
            matches.forEach((questId, ids) -> {
                if (ids.size() == 1) {
                    result.add(new QuestKey(ids.iterator().next(), questId));
                }
            });
            return Set.copyOf(result);
        }

        private static Set<TaskKey> uniqueTaskKeys(
                Connection connection,
                Set<String> snapshotIds,
                TaskRuntimeSnapshot runtime,
                Set<QuestKey> applicableQuests,
                boolean allowAllTasks
        ) throws SQLException {
            if (snapshotIds.isEmpty() || runtime.taskProgress().isEmpty()) {
                return Set.of();
            }
            String snapshotPlaceholders = placeholders(snapshotIds.size());
            String taskPlaceholders = placeholders(runtime.taskProgress().size());
            String sql = "SELECT snapshot_id, quest_id, task_id FROM task_tasks "
                    + "WHERE snapshot_id IN (" + snapshotPlaceholders + ") "
                    + "AND task_id IN (" + taskPlaceholders + ")";
            Map<String, Set<TaskKey>> matches = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                for (String snapshotId : snapshotIds) {
                    statement.setString(parameter++, snapshotId);
                }
                for (String taskId : runtime.taskProgress().keySet()) {
                    statement.setString(parameter++, taskId);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        TaskKey key = new TaskKey(
                                rows.getString("snapshot_id"),
                                rows.getString("quest_id"),
                                rows.getString("task_id")
                        );
                        if (!allowAllTasks
                                && !applicableQuests.contains(new QuestKey(key.snapshotId(), key.questId()))) {
                            // task_id 不是全局身份。必须先确认其父 quest 已经
                            // 唯一绑定到来源，否则不同任务书中的同名 task 会把
                            // 当前玩家进度错误套到另一份静态定义上。
                            continue;
                        }
                        matches.computeIfAbsent(key.taskId(), ignored -> new LinkedHashSet<>()).add(key);
                    }
                }
            }
            Set<TaskKey> result = new LinkedHashSet<>();
            matches.values().forEach(keys -> {
                if (keys.size() == 1) {
                    result.add(keys.iterator().next());
                }
            });
            return Set.copyOf(result);
        }

        private static boolean sourceExists(Connection connection, String sourceKey)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM task_snapshots s WHERE " + STATIC_SNAPSHOT_PREDICATE
                            + " AND s.source_key = ? LIMIT 1")) {
                statement.setString(1, sourceKey);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        }

        private static Set<String> snapshotIds(
                Connection connection,
                String sql,
                List<String> parameters
        ) throws SQLException {
            Set<String> result = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setString(i + 1, parameters.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(rows.getString(1));
                    }
                }
            }
            return Set.copyOf(result);
        }

        private static RuntimeContext empty() {
            return new RuntimeContext(null, Set.of(), Set.of());
        }

        private boolean appliesToQuest(String snapshotId, String questId) {
            return snapshot != null && applicableQuestKeys.contains(new QuestKey(snapshotId, questId));
        }

        private boolean appliesToTask(String snapshotId, String questId, String taskId) {
            return snapshot != null && applicableTaskKeys.contains(new TaskKey(snapshotId, questId, taskId));
        }
    }

    private record TaskKey(String snapshotId, String questId, String taskId) {
    }

}
