package io.ctyx.modpedia.ai;

import dev.langchain4j.community.store.memory.chat.sql.SQLDialect;

/**
 * SQLite 方言只负责把本地数据库语法交给 Community SQL 的 ChatMemoryStore。
 * 消息序列化、读写和删除逻辑均由社区实现负责。
 */
final class SQLiteDialect implements SQLDialect {
    @Override
    public String createTableSql(String table, String memoryIdColumnName, String contentColumnName) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + memoryIdColumnName + " TEXT PRIMARY KEY, "
                + contentColumnName + " TEXT NOT NULL DEFAULT '')";
    }

    @Override
    public String upsertSql(String table, String memoryIdColumnName, String contentColumnName) {
        return "INSERT INTO " + table + " (" + memoryIdColumnName + ", " + contentColumnName + ") VALUES (?, ?) "
                + "ON CONFLICT (" + memoryIdColumnName + ") DO UPDATE SET "
                + contentColumnName + " = excluded." + contentColumnName;
    }

    @Override
    public String deleteSql(String table, String memoryIdColumnName) {
        return "DELETE FROM " + table + " WHERE " + memoryIdColumnName + " = ?";
    }

    @Override
    public String selectSql(String table, String memoryIdColumnName, String contentColumnName) {
        return "SELECT " + contentColumnName + " FROM " + table + " WHERE " + memoryIdColumnName + " = ?";
    }
}
