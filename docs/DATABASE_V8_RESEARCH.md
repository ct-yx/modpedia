# ModPedia 数据库 v8 研究与结构冻结方案

状态：研究稿，待数据库实现完成后随代码一起评审

## 1. 研究结论

当前实例的配置目录与模组集合彼此隔离，每个整合包使用独立的
`config/modpedia/knowledge/knowledge.db`。因此数据库无需为多个整合包在同一个文件中
同时共存设计隔离主键，也不把 `collection_id` 作为文档唯一身份的一部分。

v8 的目标是：

```text
一个实例一份数据库
+ 当前安装模组版本的知识快照
+ 可重复的来源指纹和解析器指纹
+ 稳定的文档/物品引用
+ 原子更新和失败保留旧库
+ 为合成配方和其他来源预留内容类型
```

v8 完成后冻结数据库业务结构。日常模组更新只更新数据、指纹和当前快照，不再
因为每次模组版本发布而改变表结构。

## 2. 当前 v7 基线

当前数据库位于：

```text
run/config/modpedia/knowledge/knowledge.db
```

最近一次本地检查结果：

```text
Schema：v7
来源：9
逻辑文档：543
Markdown 段落：8033
物品目录：8709
FTS：SQLite FTS5 external-content
```

当前主要表：

```text
metadata
knowledge_sources
documents
segments
segments_fts
item_catalog
task_snapshots
task_quests
task_dependencies
task_tasks
task_rewards
```

v7 已经具备以下基础：

- 模组手册、Wiki、任务静态定义和物品目录共用一个 SQLite 文件；
- `content_kind` 与 `source_type` 分离；
- `documents` 保存完整 Markdown；
- `segments` 保存段落和标题路径；
- FTS5 使用 external-content，避免再保存一份 FTS 正文；
- JAR、Wiki 文件和 `custom/*.md` 仍然是事实源；
- 玩家实时任务进度保持在查询级内存快照中。

## 3. v8 的边界决定

### 3.1 数据库隔离范围

每个 Minecraft 实例独立保存：

```text
模组版本
模组手册
Wiki 来源
自定义文档
物品目录
FTB Quests 静态任务定义
```

`collection_id` 继续保留，用于区分同一实例中的手册集合、Wiki 集合或任务 Wiki，
但它只用于分类和筛选，不参与文档主键设计。

### 3.2 版本范围

本轮只追踪模组版本更新：

```text
mod_id
mod_version
source_fingerprint
parser_id
parser_version
```

Minecraft 和 NeoForge 版本继续由工程配置与实例环境管理，不作为同一实例内文档
唯一键的一部分。

### 3.3 当前内容与历史内容分离

默认数据库只保留当前可检索内容：

```text
当前文档 → documents / segments / segments_fts
当前物品 → item_catalog
版本审计 → source_revisions
```

旧版本的完整 Markdown 不默认长期堆积。历史表只保存版本、指纹、时间、解析器和
构建结果；需要离线对比时再通过原始 JAR、Wiki 缓存或可选归档恢复。

## 4. v8 目标结构

### 4.1 来源注册表

保留 `knowledge_sources`，补充模组版本和解析器信息：

```sql
knowledge_sources(
    source_id TEXT PRIMARY KEY,
    collection_id TEXT NOT NULL,
    content_kind TEXT NOT NULL,
    source_type TEXT NOT NULL,
    origin_type TEXT NOT NULL,
    title TEXT NOT NULL,
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    mod_id TEXT NOT NULL,
    mod_version TEXT NOT NULL,
    origin_uri TEXT NOT NULL,
    local_root TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    parser_id TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    priority INTEGER NOT NULL,
    metadata_json TEXT NOT NULL,
    last_seen_build_id TEXT NOT NULL,
    updated_at INTEGER NOT NULL
)
```

字段含义：

| 字段 | 作用 |
| --- | --- |
| `source_id` | 稳定来源 ID，模组版本更新时保持不变 |
| `mod_id` | 提供手册或内容的模组 ID |
| `mod_version` | 本次导入对应的模组版本 |
| `fingerprint` | 原始资源内容指纹 |
| `parser_id` | 适配器身份，例如 `patchouli_json` |
| `parser_version` | 适配器和转换规则版本 |
| `last_seen_build_id` | 最近一次完整扫描批次 |

当前代码中 `KnowledgeCompiler` 主要按原始资源指纹复用文档。v8 必须把解析器身份
和版本纳入有效指纹，避免 JAR 未变但转换器升级后继续使用旧 Markdown。

推荐的有效指纹为：

```text
SHA256(
    原始资源指纹
    + source_type
    + parser_id
    + parser_version
    + 语言回退策略版本
    + Markdown 分段器版本
)
```

### 4.2 来源版本记录

新增 `source_revisions`，只保存版本审计信息：

```sql
source_revisions(
    revision_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    mod_id TEXT NOT NULL,
    mod_version TEXT NOT NULL,
    language TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    parser_id TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    document_count INTEGER NOT NULL,
    segment_count INTEGER NOT NULL,
    status TEXT NOT NULL,
    discovered_at INTEGER NOT NULL,
    completed_at INTEGER,
    error TEXT NOT NULL
)
```

推荐的 `status`：

```text
discovered
building
ready
failed
removed
```

同一 `source_id` 的模组版本更新产生新的 `revision_id`，当前内容仍然只写入
当前表。这样可以判断数据库中的文档对应哪个模组版本，也可以保留失败更新的记录。

### 4.3 文档层

`documents` 继续以以下身份保存当前有效文档：

```text
(document_id, language)
```

前提是所有自动生成文档使用稳定且带 namespace 的 ID，自定义文档使用稳定 Front
Matter ID。建议增加：

```text
mod_version
parser_id
parser_version
revision_id
last_seen_build_id
```

已有字段继续保留：

```text
source_id
collection_id
content_kind
source_type
origin_type
source_path
metadata_json
markdown
```

低优先级覆盖文档仍按现有优先级规则处理；被覆盖的候选文档不进入当前检索集，
来源版本表保留其导入结果和指纹。

### 4.4 构建批次

新增 `knowledge_builds`，把“扫描是否完整”作为数据库事实：

```sql
knowledge_builds(
    build_id TEXT PRIMARY KEY,
    trigger TEXT NOT NULL,
    status TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    completed_at INTEGER,
    source_count INTEGER NOT NULL,
    document_count INTEGER NOT NULL,
    segment_count INTEGER NOT NULL,
    error TEXT NOT NULL
)
```

更新流程：

```text
创建 build_id
  ↓
扫描并转换来源
  ↓
写入临时数据库/事务
  ↓
所有来源扫描成功
  ↓
标记 build=ready
  ↓
原子替换 knowledge.db
```

只有完整扫描成功后，才清理 `last_seen_build_id` 不属于当前批次的旧来源。扫描
中途失败时保留上一份可用数据库，避免暂时缺失的前置模组或损坏 JAR 触发大面积
删除。

### 4.5 版本更新与文档 ID 迁移

模组更新可能改变手册条目 ID 或资源路径。v8 预留：

```sql
document_aliases(
    old_document_id TEXT NOT NULL,
    new_document_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(old_document_id, source_id)
)
```

作用：

- 历史会话中的旧来源可以解析到新文档；
- 模组改名或重排条目后仍可显示迁移提示；
- 原手册跳转失败时可以尝试新路径；
- 搜索结果可以标记“来源已更新”。

物品 ID 迁移另行预留：

```sql
item_aliases(
    old_item_id TEXT NOT NULL,
    new_item_id TEXT NOT NULL,
    source_mod TEXT NOT NULL,
    mod_version TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(old_item_id, source_mod)
)
```

## 5. 预留内容类型

v8 的 `content_kind` 固定预留以下两个值：

```text
recipe
external
```

完整内容类型为：

```text
mod_manual
wiki
task_runtime
recipe
external
```

### 5.1 合成配方条目

```text
content_kind = recipe
```

预留的 `source_type`：

```text
recipe_runtime
recipe_json
recipe_markdown
```

设计原则：

- 当前阶段继续不导入 JEI 全量配方；
- 将来配方进入数据库时，同时保存结构化 JSON 和可供模型读取的 Markdown；
- 配方输出、输入、机器类型、数量和来源模组放入 `metadata_json` 或配方载荷；
- 搜索结果可以复用 `documents`、`segments` 和 `segments_fts`；
- 精确配方查询使用 `item_id` 和结构化字段，模型上下文使用 Markdown；
- JEI 仍然是可选跳转目标，不成为数据库写入的硬依赖。

推荐的 Markdown 形态：

```markdown
## 合成配方：示例物品

- 输出：`[[item:example:result|示例物品]] × 1`
- 输入：
  - `[[item:example:a|材料 A]] × 2`
  - `[[item:example:b|材料 B]] × 1`
- 类型：工作台
- 来源：example
```

### 5.2 其他来源条目

```text
content_kind = external
```

预留的 `source_type`：

```text
external_markdown
external_json
external_runtime
```

适用范围：

- 整合包作者额外提供的说明；
- 社区 Wiki；
- 本地导入的教程和故障排查文档；
- 第三方运行时说明；
- 后续未归入手册、Wiki、任务或配方的知识来源。

这些来源统一沿用：

```text
documents.markdown
segments.markdown
segments_fts
source_path
metadata_json
```

未知字段进入 `metadata_json`，原始文件保留在来源目录，避免为每种外部来源重复
增加数据库表。

## 6. 模组更新流程

### 6.1 首次安装或首次扫描

```text
发现模组 JAR
  ↓
读取 mod_id、mod_version 和手册资源
  ↓
确定 source_id、source_type、parser_id
  ↓
计算有效指纹
  ↓
转换为 Markdown 和段落
  ↓
写入 documents / segments / FTS
  ↓
记录 source_revisions
```

### 6.2 模组版本发生变化

```text
mod_version 或有效指纹变化
  ↓
只重建该 source_id
  ↓
删除该来源旧文档和段落
  ↓
插入新文档、段落和 FTS
  ↓
写入新的 source_revisions
  ↓
提交事务并 reload
```

其他模组和其他来源保持原样。

### 6.3 模组版本未变化

```text
mod_version 相同
且有效指纹相同
且 parser_version 相同
  ↓
复用当前文档和段落
```

### 6.4 模组被移除

完整扫描确认来源消失后：

```text
删除当前 documents / segments / FTS 记录
保留 source_revisions 的 removed 记录
保留历史 document_aliases
```

原始 JAR、Wiki 缓存和自定义 Markdown 不由数据库清理流程删除。

## 7. 事务、恢复和并发规则

- 文本知识、物品目录和静态任务导入继续使用同一个 Worker 写入队列；
- 同一时间只允许一个数据库写事务；
- 读连接使用 `query_only`；
- FTS 写入与 `segments` 写入必须在同一事务中完成；
- 构建失败回滚整个批次；
- 数据库替换使用临时文件和原子 rename；
- 数据库损坏时从 JAR、Wiki 源文件和 `custom/` 全量重建；
- 重建过程不删除事实源；
- v8 后日常模组更新只改数据，不改表结构。

## 8. v8 验收标准

### 结构

- [ ] `PRAGMA user_version = 8`；
- [ ] 模组版本和解析器版本可以追踪；
- [ ] 来源版本表可以记录成功、失败和移除；
- [ ] 构建批次可以区分完整扫描和中断扫描；
- [ ] `recipe` 和 `external` 内容类型已预留；
- [ ] `metadata_json` 可以保存未知扩展字段；
- [ ] 现有手册、Wiki、任务和物品查询接口保持稳定。

### 模组更新

- [ ] 模组版本变化只重建对应来源；
- [ ] 模组版本未变化且解析器未变化时复用缓存；
- [ ] 转换器升级会触发必要重建；
- [ ] 删除模组后不会留下当前搜索结果；
- [ ] 更新失败时上一份有效知识库仍可搜索；
- [ ] 文档 ID 改名可以通过 alias 解析；
- [ ] 来源跳转路径更新后可以回退到新路径或来源预览。

### 预留类型

- [ ] `recipe` 可以同时承载结构化载荷和 Markdown；
- [ ] `external` 可以导入 Markdown、JSON 和运行时来源；
- [ ] 两类来源不会混入默认模组手册搜索范围；
- [ ] 配方未接入前，JEI 缺失不会影响启动；
- [ ] 外部来源失败时不会影响手册和任务数据库。

## 9. 实施顺序

```text
1. 锁定 v8 字段和 content_kind/source_type 枚举
2. 增加 mod_version、parser_id、parser_version
3. 增加 source_revisions 和 knowledge_builds
4. 将有效指纹改为“原始资源 + 解析器版本”
5. 增加 document_aliases / item_aliases
6. 调整来源更新、删除和失败回滚流程
7. 增加 recipe / external 的 schema 预留和测试夹具
8. 运行大型整合包更新前后回归
9. 更新 README、架构文档、知识库文档和开发清单
10. 完成 v8 数据库后再提交和推送
```

## 10. 明确暂缓项

以下内容不进入 v8 的当前实现：

- 同一个数据库同时承载多个整合包实例；
- 多实例共享文档身份；
- JEI 全量配方导入；
- 默认保留所有历史 Markdown 正文；
- 向量数据库；
- 为每一种外部来源增加专用表；
- 玩家实时任务进度持久化。

## 11. 结论

v8 的核心不是增加大量业务表，而是把以下信息一次性固定下来：

```text
来源是谁
来源属于哪个模组版本
使用哪个解析器生成
哪一次构建产生
当前文档是否仍然有效
旧 ID 如何迁移
未来内容属于手册、Wiki、任务、配方还是其他来源
```

完成这些字段后，模组版本更新可以保持局部、可回滚、可追踪；数据库业务结构也能
长期稳定。合成配方和其他来源先作为 v8 的正式预留类型，后续接入时复用统一 Markdown
和 FTS 链路。
