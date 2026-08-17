# ModPedia 知识库设计

## 1. 设计原则

主 Mod 只内置以下内容：

- 系统提示词模板
- 回答格式模板
- Markdown schema
- 最小使用说明
- 示例文档

具体模组知识在第一次启动时从本地安装资源生成。

## 2. 实际文件布局

```text
config/modpedia/
├── runtime/                         # 玩家运行时数据，发布整合包前删除
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/
│       ├── knowledge.db*
│       ├── generated/
│       ├── cache/
│       ├── manifest.json
│       ├── keyword-index.json
│       └── state.json
└── knowledge/                       # 随整合包保留的事实源
    ├── custom/
    ├── sources/
    │   └── <source-id>/source.json + documents/**/*.md + media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json                           # 跨游戏实例共享的用户级 AI 配置
└── installation-id                   # 无系统 UUID 时的共享回退标识
```

`~/.modpedia/` 位于各游戏实例之外的当前 OS 用户目录，不属于整合包。旧版实例内的
`config/modpedia/ai.json` 或 `config/modpedia/runtime/ai.json` 会在启动时迁移到用户级路径。

### `runtime/knowledge/generated/`

由扫描器生成。重新构建时允许覆盖。

### `knowledge/custom/`

玩家手工补充或修正的内容。优先级最高，始终保留。每个文件使用 Front Matter 的稳定 `id`，并可用 `language: zh_cn`、`language: en_us` 或 `language: neutral` 区分语言。

### `runtime/knowledge/knowledge.db`

SQLite 派生搜索库，不是事实源。保存文档元数据、SHA-256 指纹、完整 Markdown、完整段落、标题路径和 FTS5 索引；原始 `custom/*.md` 始终保留。Schema v7 另外保存独立的 `item_catalog` 物品目录；FTBQ 任务表只保存静态任务定义。

`item_catalog` 与手册正文分表保存：

```sql
item_catalog(
  item_id,
  language,
  display_name,
  display_name_normalized,
  description_markdown,
  source_mod,
  fingerprint,
  updated_at
)
```

客户端注册表完成后，当前语言的 Tooltip 第一行作为名称，后续行转换为完整 Markdown 无序列表。
物品目录按 `(item_id, language)` 增量同步，当前策略只保留当前游戏语言；它供 AI 和仅搜索模式
直接读取，内容不进入 `documents`、`segments` 或 `segments_fts`。游戏 JVM 不把数万条记录直接
拼成一条 IPC JSON 消息，而是在独立 I/O 线程写入 `config/modpedia/runtime/worker/payloads/` 下的原子
JSONL 载荷，IPC 只传递载荷路径；Worker 读取完成载荷后使用一条预编译 UPSERT、一个事务和批量
绑定完成写入。这样 Tooltip JSON 序列化、文件读取、SQLite 写入都不占用游戏 Tick，也不会因为
逐条提交产生卡顿。相同指纹的再次启动只做顺序指纹比较，不复制或替换整个 `knowledge.db`；
20,000 条夹具的 Worker 文件载荷同步、首次写入和相同指纹复用均保持在毫秒级，实际整合包仍以
客户端加载日志和 `worker.log` 中的 `payload_read_ms` / `database_write_ms` 为准。

### `runtime/knowledge/cache/`

保存来源清单、关键词索引和扫描报告。

## 3. 内容类型与来源格式

`knowledge.db` 是统一文件，但不把所有内容混成一个语义集合：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `content_kind` | `mod_manual` | 模组提供的手册正文 |
| `content_kind` | `wiki` | 整合包作者、社区或任务 Wiki |
| `content_kind` | `task_runtime` | FTBQ 静态任务定义集合；玩家实时进度只在查询内存中存在 |
| `source_type` | `patchouli_json`、`guideme_markdown`、`app_json` | JAR 手册输入格式 |
| `source_type` | `custom_markdown`、`wiki_markdown` | 人工或远程 Markdown |
| `source_type` | `task_snapshot` | 任务运行快照 |
| `origin_type` | `jar`、`local_file`、`remote`、`runtime` | 来源位置 |

`knowledge_sources` 保存来源集合、版本、语言、优先级和指纹；`documents` 保存完整 Markdown
和元数据；`segments` 保存完整段落与标题路径；`segments_fts` 只保存检索派生字段。
`collection_id` 允许多个整合包 Wiki 共存，`source_id` 负责稳定来源标识。

可扩展 Wiki 的最小结构：

```text
sources/example-pack/
├── source.json
└── documents/
    └── getting-started.md
```

`source.json` 至少声明 `source_id`、`collection_id`、`content_kind`、`source_type`、
`language` 和 `documents_root`。以后增加新的导入格式只需要实现
`KnowledgeSourceImporter`，不需要改动搜索接口。

```json
{
  "source_id": "example-pack",
  "collection_id": "example-pack",
  "content_kind": "wiki",
  "source_type": "wiki_markdown",
  "origin_type": "local",
  "title": "整合包指南",
  "language": "zh_cn",
  "version": "1.0.0",
  "documents_root": "documents",
  "priority": 60
}
```

## 4. 统一 Markdown

```markdown
---
id: example:guide/basic
source_mod: example
source_type: local_guide
title: 基础说明
category: guide
keywords:
  - 基础
  - 入门
source_version: 1.0.0
---

# 基础说明

正文内容。
```

## 5. 首次启动

```text
读取模组列表
  ↓
扫描本地手册资源
  ↓
解析语言 key 和结构化数据
  ↓
转换为 Markdown
  ↓
合并 custom/
  ↓
导入 sources/ 和内置任务 Wiki
  ↓
事务导入统一 knowledge.db
  ↓
生成 manifest、keyword-index 和 state
  ↓
RetrievalService.reload()
  ↓
客户端注册表物品 Tooltip 导入 item_catalog
  ↓
预填充完成，进入主菜单
```

首次预填充在加载屏幕阶段同步完成，避免进入世界后继续占用客户端帧时间；其中游戏线程只做
Minecraft 注册表允许的 Tooltip 捕获，批量载荷序列化和数据库写入分别由 I/O 线程与 Worker
完成。F9 重建和语言切换刷新仍可使用后台任务，并向界面报告进度。

## 6. 更新方式

启动时读取 `state.json` 中的来源指纹：

- 新增模组或手册来源：转换并写入新的 Markdown。
- 模组版本或手册内容变化：只重新转换变化的来源。
- 已删除的来源：删除对应的 `generated/` 文件。
- 没有变化的来源：复用已有 Markdown，不重复执行格式转换。
- 玩家按 `F9`：强制重新转换所有来源并重建索引。

### 自定义 Markdown 增量导入

```text
扫描 custom/*.md
  ↓
读取 id/language/指纹
  ├─ 指纹相同：复用 SQLite 中已解析的文档和段落
  └─ 指纹变化：解析后按 (id, language) 替换
删除文件：删除对应 documents、segments 和 segments_fts
```

- 自定义优先级为 `100`，自动手册为 `0`；同一 `(id, language)` 只保留最高优先级记录。
- 缺少 `id`、Front Matter 不完整或解析异常时记录警告并保留上一份有效记录。
- 手册/Wiki 全量重建仍写入临时库并在事务提交后原子替换；物品目录不复制整库，直接在正式库内用一个短事务批量 UPSERT，失败时由 SQLite 回滚并继续使用旧目录。
- 数据库不存在、损坏或版本不匹配时，从当前模组手册和 `custom/` 全量重建。
- 当前早期测试版目标 Schema 为 v7，不做旧库迁移。检测到旧 Schema、旧 FTS 形态或缺少 `knowledge_sources`、`task_*`、`item_catalog` 表时，在旁路数据库中从事实源全量重建，校验成功后原子替换 `knowledge.db`；替换或导入失败时恢复上一份有效库，并清理 WAL、SHM 和临时文件。JAR、`custom/` 或 `sources/` 原始文件继续保留。
- 搜索选择当前语言，并在没有对应本地化文档时回退 `neutral`；中英文文档可使用同一稳定 ID。

每次构建都会更新 `manifest.json`、`keyword-index.json`、`state.json` 和 `cache/build-report.json`。报告包含 `updatedCount`、`reusedCount`、`removedCount` 和警告列表。

当前 FTS5 使用 `content='segments'` 的 external-content 结构，完整 Markdown 仍从
`documents`/`segments` 事实表读取，FTS 不创建 `segments_fts_content` 正文副本。构建阶段在
Schema/索引创建后和同步提交前执行 `PRAGMA optimize`；全量或大批量变更额外执行 FTS5
`optimize`/merge，小规模增量更新跳过完整合并。检索按 FTS5 `rank` 排序，再由 Java 规则评分和
中文实体短语收窄。`./gradlew knowledgeBenchmark` 会在真实 JAR 语料和 10× 合成语料上记录
中文、英文、ID、多词、无结果查询的冷/热 p50/p95/p99、SQLite/FTS/dbstat 大小和
`EXPLAIN QUERY PLAN`，报告写入 `build/reports/modpedia/`。

### 物品目录搜索上下文

玩家通过 Jade 插入的 `[[item:namespace:path|显示名称]]`、模型传入的物品 ID或正文中的裸资源 ID
先经过 `ItemQueryParser` 提取，再由 `RetrievalService.lookupItems` 查询 `item_catalog`。工具返回
`item_context` 后继续使用物品 ID和显示名称搜索模组手册；Tooltip 简介作为物品事实使用，来源卡片
仍只来自实际手册文档。

助手浮窗顶部通过 `ModPediaBridge.knowledgeStatus()` 读取 Worker 发布的线程安全
`KnowledgeStatus` 快照，显示来源数量、文档数量、最近更新时间以及更新/错误状态。
扫描、SQLite 和 FTS 连接都留在 Worker JVM。

## 7. 检索规则

第一版按照以下字段建立关键词索引：

- 模组 ID 和显示名
- 页面标题
- 物品和方块 ID
- 分类
- 标签
- 同义词
- 页面正文中的重点词

`RetrievalService` 的规则检索流程为：

```text
查询归一化
  ↓
关键词索引和元数据筛选候选
  ↓
读取候选 Markdown
  ↓
按完整段落匹配和评分
  ↓
每篇文档保留最高分段落
  ↓
按分数和文档 ID 稳定排序
```

具体规则：

- Unicode NFKC、大小写、空白和标点统一处理。
- 保留完整短语、普通词、模组 ID、物品 ID、路径片段以及下划线/连字符拆分结果。
- 中文文本生成相邻双字词，支持局部短语匹配。
- 文档 ID、物品 ID、模组 ID 的完全匹配权重最高，其次是标题、关键词、正文段落、分类和来源路径。
- 构建索引时根据手册页面自身路径，从模组 `zh_cn` 语言表补充物品/方块本地化名称；例如 `破坏面板` 可以命中 `annihilation_plane`，且不会被只引用该物品的关联页面抢占首位。
- 多个查询词同时命中时增加组合分；同义词匹配使用较低权重。
- 默认最多返回 8 条，调用方可指定 1–20 条；同一文档只返回一个段落结果。
- 空行分隔普通 Markdown 段落，连续列表和 fenced code block 保持完整。

返回结构包含文档 ID、标题、模组、来源路径、标题路径、完整 `segmentMarkdown`、分数和命中词。

可选同义词配置位于 `config/modpedia/knowledge/search-synonyms.json`：

```json
{
  "groups": [
    ["自动合成", "autocrafting"],
    ["线缆", "cable"]
  ]
}
```

单次回答只选取少量相关文档段落，并在上下文中保留文档 ID、标题和来源模组。

模组手册、Wiki 和任务查询使用不同工具：

```text
search_knowledge  → content_kind=mod_manual
search_wiki       → content_kind=wiki
search_tasks      → task_* 表，并区分静态定义与实时进度
```

`search_tasks` 不由客户端 Tick 触发。一次玩家问题开始时，先取得当前玩家运行时上下文。
单机由游戏 JVM 只发送当前存档根目录、玩家 UUID 和作用域元数据，Worker 直接读取
`saves/<世界>/ftbquests/<team-uuid>.snbt` 中有界的 `started`、`completed` 和
`task_progress`；这个文件很小，读取和 SNBT 解析不经过游戏 Tick。由于进度文件可能在
查询瞬间快速重写，Worker 读取前后会比较文件指纹，撞上重写就立即重试，不加锁、不轮询、
不写回存档或 `knowledge.db`。多人服务器或本地存档
不可用时，才由可选 FTB Quests 适配器在客户端线程读取 TeamData，再通过 IPC 返回
`TaskRuntimeSnapshot`。Worker 收到快照后才用运行时 ID 从 `knowledge.db` 抽取静态任务定义、
依赖、要求和奖励。启动阶段导入的只是静态任务定义，不能替代当前玩家进度读取。
`TaskRuntimeSnapshot` 只在当前 AI 请求内存中覆盖状态，不写入或读取实时进度表。同一 AI
请求后续的工具轮次复用这次读取结果，避免为一个问题遍历并序列化整套任务树。
快照的 `timeline` 记录任务开始、完成和检测到的进度变化；前两者沿用 FTBQ 存档中的
时间戳，进度变化使用检测时间。它只通过 Worker IPC 传给 `search_tasks`，不写入
`knowledge.db`，工具会按任务 ID 查询静态标题后返回给模型。

任务奖励中的随机箱或候选奖励在响应中保留 `is_random=true`、`guaranteed=false` 和
`candidates`，模型不得把候选列表写成确定获得的物品。

## 8. 向量索引

向量索引作为整合包制作阶段的可选输出：

```text
Markdown → 分块 → 嵌入 → 向量索引 → 可选重排
```

普通运行时保留关键词检索，确保没有额外模型时仍可工作。

## 9. 交给 AI 的内容与上下文

`search_knowledge` 只从 SQLite 返回当前查询命中的完整 Markdown 段落，不把整本手册加载进模型上下文。每轮结果同时带上：

```text
document_id / title / source_mod / source_path
heading_path / segment_markdown / score / matched_terms
returned_count / has_more
```

模型先检查实体、步骤、配方、前置条件和版本是否齐全；证据明显不足时改写查询或切换中文/英文资料继续搜索。玩家可以使用显示名称和自然语言，不必自行知道 ID。程序用搜索强度限制轮数、每轮结果数和上下文字符预算，并抑制重复查询与已实际返回的文档。

回答中的来源标注按钮只来自本轮搜索轨迹，模型使用 `[来源: document_id | 标注: ...]` 选择并说明最相关的 3–5 个来源；按钮位于回答正文的标注区，点击后跳转对应手册页面。未明确引用的候选结果不会自动变成跳转链接。回答末尾的 `<modpedia_follow_up_questions>` 协议会渲染为三个后续问题按钮。

设置中的 `mode=SEARCH_ONLY` 会跳过模型请求，直接把同一份 SQLite 检索结果格式化为完整 Markdown 消息和正文来源标注；该模式仍保存用户消息、查询语言、轮次和命中文档 ID，切换回 `AI` 时不会覆盖原 API 配置。

通用上下文管理由 LangChain4j `TokenWindowChatMemory` 完成；持久化读写使用 Apache-2.0 的 LangChain4j Community SQL `SQLChatMemoryStore`，消息序列化仍使用 LangChain4j 官方 `ChatMessageSerializer`。ModPedia 只装配 SQLite DataSource 和本地方言，不再自研完整的 ChatMemoryStore。运行时数据保存到：

```text
config/modpedia/runtime/conversations/
├── conversation-*.json  # UI 历史、正文来源标注和搜索轨迹
└── memory.sqlite        # Community SQL 持久化模型上下文
```

旧版本会话文件中的 `memoryMessagesJson` 会在首次读取对应会话时迁移到 `memory.sqlite`，成功后清空旧字段；SQLite 写入失败时保留旧 JSON 并继续使用它。历史文件保存查询和来源 ID，不复制 `segment_markdown`；知识正文的唯一事实副本仍是 JAR 资源、`runtime/knowledge/generated/*.md` 和 `knowledge/custom/*.md`，知识 SQLite 与 AI 上下文 SQLite 分开维护。这样可以独立重建知识库，也可以在不膨胀 UI 历史文件的情况下调整上下文窗口。

## 10. 当前实现

第二阶段已经接入以下运行时组件：

```text
LocalGuideScanner
  ↓
MarkdownDocumentConverter / JsonGuideDocumentConverter
  ↓
KnowledgeCompiler
  ↓
config/modpedia/runtime/knowledge/
（从 config/modpedia/knowledge/ 的 custom/ 与 sources/ 读取事实源）
```

扫描器读取已安装模组 JAR 内的资源。基础手册不联网下载；任务 Wiki 由独立的后台同步器
先使用内置 Markdown，再按配置尝试更新远程副本。支持：

- `data/<namespace>/patchouli_books/**/*.json`
- `assets/<namespace>/patchouli_books/**/*.json`
- `assets/<namespace>/guides/**/*.md`
- `assets/<namespace>/ae2guide/**/*.md`
- `assets/<namespace>/guideme_guides/**/*.json`
- `data/<namespace>/modonomicon/books/**/*.json`
- `assets/<namespace>/lang/zh_cn.json`
- `assets/<namespace>/lang/en_us.json`

三个手册框架是前置运行库，不承载实际手册正文。扫描器以内容资源的 namespace 作为模组来源；没有正文的框架 JAR 只进入规模统计中的“依赖型 JAR”，不作为扫描错误。FTB Quests、JEI、Jade 不是手册格式，也不是必需依赖：它们分别提供任务快照、配方跳转和视线目标插入。

APP 资源使用 `source_type: app_json`。书籍 JSON 中的多个 `entries` 会展开成独立文档，来源路径追加书籍、分类和条目锚点，供客户端正文来源标注按钮跳转。

1.21.1 的 APP 书籍资源位于 `data/<namespace>/modonomicon/books/**/*.json`。
这里的 `<namespace>` 是实际内容模组 ID；三个手册框架自身只提供运行时 API，
即使框架 JAR 包含同名目录，也不会被当作正文来源。

`generated/` 每次重新构建时由扫描结果生成；`custom/` 的 Markdown 作为高优先级覆盖内容合并进 manifest 和关键词索引；`sources/` 下的 Markdown 按来源描述导入为 Wiki。整合包作者使用 Patchouli 或 Modonomicon 编写的书籍，仍可通过 `knowledge.content_kind=wiki` 或 `source-overrides.json` 分类为 Wiki。

`KnowledgeDatabase` 是搜索后端的运行时首选：`RetrievalService` 优先查询 SQLite FTS5，不在查询过程中重新读取或拆分 Markdown；缺失数据库时才回退到旧版 JSON/Markdown 路径，损坏数据库则由下一次知识库构建全量重建。

Patchouli 书籍页面按每本书独立选择语言：存在 `zh_cn` 时只读取中文页面，否则回退到 `en_us`；其他语言页面不会重复进入知识库。GuideME Markdown 同时识别标准 `guides/`、`guideme_guides/` 和 AE2 使用的 `ae2guide/` 目录。

### 本地样本验证

使用本地提供的两组手册库与代表模组验证：

- Patchouli + PneumaticCraft：237 个 Patchouli JSON 来源。
- GuideME + Applied Energistics 2：125 个 `ae2guide` Markdown 来源。
- ModPedia 自带示例与 `custom/`：2 个生成文档、1 个自定义文档。
- 总计：364 个来源、365 个文档、0 个扫描警告；文档 ID 无重复。

### 规模与双语基准

`knowledgeBenchmark` 测试任务不会覆盖运行中的 `config/modpedia/runtime/knowledge/`，而是在临时目录执行：

```bash
./gradlew knowledgeBenchmark
```

测试分别使用 `zh_cn` 和 `en_us` 语言 profile，并记录：

- JAR 数、声明模组数、唯一模组数和包含手册的容器数；
- 来源、文档、关键词、posting、完整 Markdown 段落和文件大小；
- 首次构建、无变化增量构建、单来源变更构建和索引 `reload()`；
- 精确 ID、本地化名称、英文短语、部分词、多词查询和无匹配查询的 p50/p95/p99。

基准包含当前基线、额外 JAR 实际扩展集和 10× 唯一文档集。默认搜索 p95 预算是 `50 ms`，因为检索结果之后还要交给大语言模型处理。没有手册正文的前置模组仍参加 JAR 和依赖统计，并标记为依赖型 JAR；它们不是扫描错误。

每次构建都会写入：

```text
manifest.json
keyword-index.json
state.json
cache/build-report.json
```

数据库同步相关纯 Java 回归测试：

```bash
./gradlew knowledgeDatabaseSelfTest
```

覆盖新增、修改、未变化复用、删除、双语/`neutral` 选择、自定义覆盖、损坏重建、非法 Front Matter 回退和事务失败回滚。规模基准默认把搜索 p95 预算设为 `50 ms`，以便给后续大语言模型回答保留预算。

任务定义表与文本表共用物理数据库但互不污染：文本手册重建只替换
`knowledge_sources` 中的 `mod_manual`/`wiki` 和对应 `documents`；任务同步只替换对应
`task_snapshots` 及其静态子表。`knowledge.db` 保存事实正文、搜索段落和静态任务定义，
玩家实时进度只存在于当前 `TaskRuntimeSnapshot`；AI 上下文仍单独保存到
`runtime/conversations/memory.sqlite`。
