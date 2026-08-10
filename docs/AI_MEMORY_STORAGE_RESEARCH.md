# AI 持久化上下文方案调研

> 调研日期：2026-08-10
>
> 目标：用社区成熟实现替换 ModPedia 自己维护的 `ChatMemoryStore` 持久化层，保留现有会话、搜索轨迹和 Minecraft 客户端适配。

## 结论

首选 **LangChain4j Community SQL**：

```text
dev.langchain4j:langchain4j-community-sql:1.18.0-beta28
```

它直接实现 LangChain4j 的 `ChatMemoryStore`，使用 LangChain4j 官方的
`ChatMessageSerializer`/`ChatMessageDeserializer` 保存完整消息 JSON，适合保留
`AiMessage`、工具调用请求、工具结果和原始 tool-call ID。

项目继续使用已有的 Xerial SQLite 驱动和本地文件数据库。社区模块目前没有
SQLite 方言，只有 H2、MySQL、PostgreSQL 三种方言；SQLite 兼容 PostgreSQL
方言实际使用的建表和 `ON CONFLICT ... DO UPDATE` 语句，但正式迁移时应增加一个
仅包含四条 SQL 语句的 `SQLiteDialect` 胶水类，而不是继续维护完整的消息序列化、
读写和 ChatMemoryStore 生命周期。

## 一手资料

- [LangChain4j Chat Memory 官方文档](https://docs.langchain4j.dev/tutorials/chat-memory)
  - 官方明确将持久化边界定义为 `ChatMemoryStore`，并提供消息 JSON 序列化辅助类。
  - 官方核心目前只内置 `InMemoryChatMemoryStore`，数据库实现通过集成模块或自定义存储接入。
- [`ChatMemoryStore` 官方接口](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java)
- [官方支持的 Chat Memory Stores](https://docs.langchain4j.dev/integrations/chat-memory-stores)
  - 当前列出的持久化实现主要是 Cassandra、Oracle、Redis、Hazelcast 等，不包含本地 SQLite。
- [Community SQL 模块源码](https://github.com/langchain4j/langchain4j-community/tree/1.18.0-beta28/chat-memory-stores/langchain4j-community-sql)
- [Community SQL Maven artifact](https://central.sonatype.com/artifact/dev.langchain4j/langchain4j-community-sql/1.18.0-beta28)
  - 版本已发布到 Maven Central，许可证为 Apache-2.0。
- [SQLChatMemoryStore 实现](https://github.com/langchain4j/langchain4j-community/blob/1.18.0-beta28/chat-memory-stores/langchain4j-community-sql/src/main/java/dev/langchain4j/community/store/memory/chat/sql/SQLChatMemoryStore.java)
- [SQL 方言接口与现有方言](https://github.com/langchain4j/langchain4j-community/tree/1.18.0-beta28/chat-memory-stores/langchain4j-community-sql/src/main/java/dev/langchain4j/community/store/memory/chat/sql)

## 方案对比

| 方案 | 社区代码覆盖 | SQLite | 本地部署 | 结论 |
|---|---:|---:|---:|---|
| Community SQL + SQLiteDialect | ChatMemoryStore、JSON 读写、SQL 生命周期 | 直接 | 保持现有 DB | **推荐** |
| Community SQL + PostgreSQLDialect | 同上 | 已验证兼容 | 保持现有 DB | 可作为临时探针，不作为长期命名 |
| Community SQL + H2Dialect | 同上 | 不使用 SQLite | 需要新增 H2 文件库 | 可行，但增加数据库和依赖 |
| Spring/JPA 方案 | 部分持久化代码 | 间接 | 需要 Spring/JPA 运行时 | 不适合 NeoForge 客户端 |
| 低星个人项目 | 不稳定 | 不确定 | 不确定 | 不采用 |

本地探针已使用当前 SQLite 驱动验证 Community SQL 的完整消息往返、更新和删除；
工具调用消息的最终回归仍应在仓库迁移后通过 LangChain4j 与真实模型测试完成。

## 迁移边界

### 替换

- `PersistentChatMemoryStore` 的持久化读写改为 Community SQL 的 `SQLChatMemoryStore`。
- 新增 SQLite `DataSource`/方言装配和数据库路径配置。
- 首次读取对应会话时从旧会话 JSON 的 `memoryMessagesJson` 一次性迁移到 SQLite。

### 保留

- `TokenWindowChatMemory`：它已经是 LangChain4j 官方上下文窗口实现，不需要另造窗口管理器。
- `ConversationStore`：它保存 UI 历史、来源卡片和搜索轨迹，属于 ModPedia 产品数据模型，不是通用 ChatMemoryStore 的职责。
- `repair`、重试时清理未完成工具轮次：这是针对网关失败恢复的业务策略，不应伪装成数据库能力；迁移后只对社区 store 读写结果执行。

### 验收重点

1. 重启后完整恢复 `assistant.tool_calls` 与 `tool_call_id` 配对。
2. 工具结果仍紧跟对应的 `AiMessage`，不再产生 orphan tool result。
3. 新旧会话迁移失败时保留旧 JSON，不影响历史 UI。
4. SQLite 写入失败时不覆盖旧上下文，重试不会污染上一轮。
5. Dedicated Server 不加载 AI 客户端或 UI 类。
