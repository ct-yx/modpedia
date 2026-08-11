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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务运行数据的统一 SQLite 适配层；物理文件仍是 knowledge.db。 */
public final class TaskKnowledgeStore {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path knowledgeRoot;

    public TaskKnowledgeStore(Path knowledgeRoot) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
    }

    public void syncSnapshot(TaskSnapshot snapshot) throws java.io.IOException {
        if (snapshot == null) {
            return;
        }
        KnowledgeDatabase.writeTransaction(knowledgeRoot, connection -> {
            deleteSnapshot(connection, snapshot.sourceKey());
            insertSnapshot(connection, snapshot);
            insertSource(connection, snapshot);
            return null;
        });
    }

    public TaskResponse query(TaskQuery query) {
        TaskQuery actual = query == null ? TaskQuery.search("") : query;
        if (actual.mode() == TaskQueryMode.WIKI) {
            return new TaskResponse(TaskStatus.NO_MATCH, actual, List.of(), "Wiki 由 search_wiki 查询");
        }
        if (actual.mode() != TaskQueryMode.NEXT && actual.text().isBlank() && actual.questId().isBlank()) {
            return new TaskResponse(TaskStatus.EMPTY_QUERY, actual, List.of(), "");
        }
        try {
            KnowledgeDatabase.ensureDatabase(knowledgeRoot);
            try (Connection connection = openRead()) {
                if (!hasSnapshots(connection)) {
                    return new TaskResponse(TaskStatus.NOT_SYNCED, actual, List.of(), "任务快照尚未同步");
                }
                List<QuestRow> quests = readQuests(connection, actual);
                List<TaskResult> results = new ArrayList<>();
                for (QuestRow row : quests) {
                    TaskResult result = toResult(connection, row);
                    if (matchesMode(result, actual.mode())) {
                        results.add(result);
                    }
                }
                results.sort(Comparator.comparing(TaskResult::questId));
                if (results.size() > actual.limit()) {
                    results = new ArrayList<>(results.subList(0, actual.limit()));
                }
                return new TaskResponse(
                        results.isEmpty() ? TaskStatus.NO_MATCH : TaskStatus.READY,
                        actual,
                        results,
                        ""
                );
            }
        } catch (Exception exception) {
            return new TaskResponse(TaskStatus.ERROR, actual, List.of(), messageOf(exception));
        }
    }

    public void updateProgress(
            String scopeKey,
            String questId,
            String taskId,
            String status,
            double current,
            double required,
            boolean completed
    ) throws java.io.IOException {
        updateProgress("", scopeKey, questId, taskId, status, current, required, completed);
    }

    /** 带任务快照 ID 的进度更新；可避免多个来源使用相同 quest/task ID 时串数据。 */
    public void updateProgress(
            String snapshotId,
            String scopeKey,
            String questId,
            String taskId,
            String status,
            double current,
            double required,
            boolean completed
    ) throws java.io.IOException {
        KnowledgeDatabase.writeTransaction(knowledgeRoot, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO task_progress(
                        scope_key, snapshot_id, quest_id, task_id, status, current_value,
                        required_value, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(scope_key, snapshot_id, quest_id, task_id) DO UPDATE SET
                        status = excluded.status,
                        current_value = excluded.current_value,
                        required_value = excluded.required_value,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, text(scopeKey, "local"));
                statement.setString(2, text(snapshotId, ""));
                statement.setString(3, text(questId, "unknown"));
                statement.setString(4, text(taskId, "unknown"));
                statement.setString(5, completed ? "completed" : text(status, "in_progress"));
                statement.setDouble(6, current);
                statement.setDouble(7, required);
                statement.setLong(8, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
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
        String snapshotId = null;
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT snapshot_id FROM task_snapshots WHERE source_key = ?")) {
            find.setString(1, sourceKey);
            try (ResultSet rows = find.executeQuery()) {
                if (rows.next()) {
                    snapshotId = rows.getString(1);
                }
            }
        }
        if (snapshotId != null) {
            try (PreparedStatement progress = connection.prepareStatement(
                    "DELETE FROM task_progress WHERE snapshot_id = ?")) {
                progress.setString(1, snapshotId);
                progress.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM task_snapshots WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            statement.executeUpdate();
        }
    }

    private void insertSnapshot(Connection connection, TaskSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_snapshots(
                    snapshot_id, source_key, fingerprint, scope_key, version, updated_at, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.snapshotId());
            statement.setString(2, snapshot.sourceKey());
            statement.setString(3, snapshot.fingerprint());
            statement.setString(4, snapshot.scopeKey());
            statement.setString(5, snapshot.version());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, snapshot.rawJson());
            statement.executeUpdate();
        }

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
            for (TaskSnapshot.TaskQuest quest : snapshot.quests()) {
                questStatement.setString(1, quest.questId());
                questStatement.setString(2, snapshot.snapshotId());
                questStatement.setString(3, quest.parentId());
                questStatement.setString(4, quest.title());
                questStatement.setString(5, quest.subtitleMarkdown());
                questStatement.setString(6, quest.descriptionMarkdown());
                questStatement.setInt(7, quest.optional() ? 1 : 0);
                questStatement.setInt(8, quest.visible() ? 1 : 0);
                questStatement.setInt(9, quest.started() ? 1 : 0);
                questStatement.setInt(10, quest.completed() ? 1 : 0);
                questStatement.setInt(11, quest.sortIndex());
                questStatement.setString(12, quest.rawJson());
                questStatement.executeUpdate();

                for (String dependency : quest.dependencies()) {
                    dependencyStatement.setString(1, snapshot.snapshotId());
                    dependencyStatement.setString(2, quest.questId());
                    dependencyStatement.setString(3, dependency);
                    dependencyStatement.setInt(4, 0);
                    dependencyStatement.addBatch();
                }
                for (TaskSnapshot.TaskRequirement task : quest.tasks()) {
                    taskStatement.setString(1, snapshot.snapshotId());
                    taskStatement.setString(2, task.taskId());
                    taskStatement.setString(3, quest.questId());
                    taskStatement.setString(4, task.type());
                    taskStatement.setString(5, task.title());
                    taskStatement.setString(6, task.targetId());
                    taskStatement.setDouble(7, task.current());
                    taskStatement.setDouble(8, task.required());
                    taskStatement.setInt(9, task.completed() ? 1 : 0);
                    taskStatement.setString(10, task.rawJson());
                    taskStatement.addBatch();
                }
                for (TaskSnapshot.TaskReward reward : quest.rewards()) {
                    rewardStatement.setString(1, snapshot.snapshotId());
                    rewardStatement.setString(2, reward.rewardId());
                    rewardStatement.setString(3, quest.questId());
                    rewardStatement.setString(4, reward.type());
                    rewardStatement.setString(5, reward.title());
                    rewardStatement.setInt(6, reward.guaranteed() ? 1 : 0);
                    rewardStatement.setString(7, JSON.toJson(reward.candidates()));
                    rewardStatement.setString(8, reward.rawJson());
                    rewardStatement.addBatch();
                }
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

    private List<QuestRow> readQuests(Connection connection, TaskQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT q.quest_id, q.snapshot_id, s.scope_key, q.parent_id, q.title,
                       q.description_markdown, q.optional, q.visible, q.started,
                       q.completed, q.sort_index
                FROM task_quests q
                JOIN task_snapshots s ON s.snapshot_id = q.snapshot_id
                WHERE 1 = 1
                """);
        List<String> parameters = new ArrayList<>();
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
        sql.append(" ORDER BY sort_index, quest_id");
        List<QuestRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new QuestRow(
                            rows.getString("quest_id"),
                            rows.getString("snapshot_id"),
                            rows.getString("scope_key"),
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

    private TaskResult toResult(Connection connection, QuestRow row) throws SQLException {
        List<String> dependencies = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT dependency_id FROM task_dependencies WHERE snapshot_id = ? AND quest_id = ?")) {
            statement.setString(1, row.snapshotId());
            statement.setString(2, row.questId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String dependency = rows.getString(1);
                    if (!isCompleted(connection, row.snapshotId(), row.scopeKey(), dependency)) {
                        dependencies.add(dependency);
                    }
                }
            }
        }
        List<TaskResult.TaskRequirementResult> requirements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, task_type, title, target_id, current_value,
                       required_value, completed
                FROM task_tasks WHERE snapshot_id = ? AND quest_id = ? ORDER BY task_id
                """)) {
            statement.setString(1, row.snapshotId());
            statement.setString(2, row.questId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ProgressRow progress = readProgress(connection, row, rows.getString("task_id"));
                    double current = progress == null ? rows.getDouble("current_value") : progress.current();
                    double required = progress == null ? rows.getDouble("required_value") : progress.required();
                    boolean completed = progress == null
                            ? rows.getBoolean("completed")
                            : "completed".equalsIgnoreCase(progress.status())
                            || progress.current() >= progress.required() && progress.required() > 0;
                    requirements.add(new TaskResult.TaskRequirementResult(
                            rows.getString("task_id"),
                            rows.getString("task_type"),
                            rows.getString("target_id"),
                            current,
                            required,
                            completed,
                            rows.getString("title")
                    ));
                }
            }
        }
        List<TaskResult.TaskRewardResult> rewards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reward_id, reward_type, title, guaranteed, candidates_json
                FROM task_rewards WHERE snapshot_id = ? AND quest_id = ? ORDER BY reward_id
                """)) {
            statement.setString(1, row.snapshotId());
            statement.setString(2, row.questId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rewards.add(new TaskResult.TaskRewardResult(
                            rows.getString("reward_id"),
                            rows.getString("reward_type"),
                            rows.getString("title"),
                            rows.getBoolean("guaranteed"),
                            parseStrings(rows.getString("candidates_json"))
                    ));
                }
            }
        }
        String status = row.completed()
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
                hasProgress(connection, row)
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
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT 1 FROM task_snapshots LIMIT 1")) {
            return rows.next();
        }
    }

    private boolean isCompleted(
            Connection connection,
            String snapshotId,
            String scopeKey,
            String questId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT completed FROM task_quests WHERE snapshot_id = ? AND quest_id = ?")) {
            statement.setString(1, snapshotId);
            statement.setString(2, questId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getBoolean(1)) {
                    return true;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN task_tasks.completed = 1
                                  OR EXISTS (
                                      SELECT 1 FROM task_progress
                                      WHERE task_progress.scope_key = ?
                                        AND (task_progress.snapshot_id = ? OR task_progress.snapshot_id = '')
                                        AND task_progress.quest_id = task_tasks.quest_id
                                        AND task_progress.task_id = task_tasks.task_id
                                        AND (task_progress.status = 'completed'
                                             OR task_progress.current_value >= task_progress.required_value)
                                  ) THEN 1 ELSE 0 END) AS done
                FROM task_tasks
                WHERE snapshot_id = ? AND quest_id = ?
                """)) {
            statement.setString(1, scopeKey);
            statement.setString(2, snapshotId);
            statement.setString(3, snapshotId);
            statement.setString(4, questId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    int total = rows.getInt("total");
                    return total > 0 && rows.getInt("done") == total;
                }
            }
        }
        return false;
    }

    private ProgressRow readProgress(Connection connection, QuestRow row, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, current_value, required_value
                FROM task_progress
                WHERE scope_key = ? AND (snapshot_id = ? OR snapshot_id = '')
                  AND quest_id = ? AND task_id = ?
                ORDER BY CASE WHEN snapshot_id = ? THEN 0 ELSE 1 END
                LIMIT 1
                """)) {
            statement.setString(1, row.scopeKey());
            statement.setString(2, row.snapshotId());
            statement.setString(3, row.questId());
            statement.setString(4, taskId);
            statement.setString(5, row.snapshotId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new ProgressRow(rows.getString("status"), rows.getDouble("current_value"), rows.getDouble("required_value"))
                        : null;
            }
        }
    }

    private boolean hasProgress(Connection connection, QuestRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM task_progress WHERE scope_key = ? AND (snapshot_id = ? OR snapshot_id = '') "
                        + "AND quest_id = ? LIMIT 1")) {
            statement.setString(1, row.scopeKey());
            statement.setString(2, row.snapshotId());
            statement.setString(3, row.questId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
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

    private record QuestRow(
            String questId,
            String snapshotId,
            String scopeKey,
            String parentId,
            String title,
            String descriptionMarkdown,
            boolean optional,
            boolean visible,
            boolean started,
            boolean completed
    ) {
    }

    private record ProgressRow(String status, double current, double required) {
    }
}
