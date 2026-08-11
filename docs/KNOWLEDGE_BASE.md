# ModPedia 知识库设计

## 1. 设计原则

主 Mod 只内置以下内容：

- 系统提示词模板
- 回答格式模板
- Markdown schema
- 最小使用说明
- 示例文档

具体模组知识在第一次启动时从本地安装资源生成。

## 2. 运行时目录

```text
config/modpedia/knowledge/
├── generated/
├── custom/
├── sources/
│   └── <source-id>/source.json + documents/**/*.md
├── cache/
├── manifest.json
├── keyword-index.json
├── knowledge.db
└── state.json
```

### `generated/`

由扫描器生成。重新构建时允许覆盖。

### `custom/`

玩家手工补充或修正的内容。优先级最高，始终保留。每个文件使用 Front Matter 的稳定 `id`，并可用 `language: zh_cn`、`language: en_us` 或 `language: neutral` 区分语言。

### `knowledge.db`

SQLite 派生搜索库，不是事实源。保存文档元数据、SHA-256 指纹、完整 Markdown、完整段落、标题路径和 FTS5 索引；原始 `custom/*.md` 始终保留。

### `cache/`

保存来源清单、关键词索引和扫描报告。

## 3. 内容类型与来源格式

`knowledge.db` 是统一文件，但不把所有内容混成一个语义集合：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `content_kind` | `mod_manual` | 模组提供的手册正文 |
| `content_kind` | `wiki` | 整合包作者、社区或任务 Wiki |
| `content_kind` | `task_runtime` | 当前世界和玩家的任务运行数据 |
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
```

扫描过程必须在后台执行，并向界面报告进度。

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
- SQLite 同步写入临时库并在事务提交后原子替换；失败时继续使用旧库。
- 数据库不存在、损坏或版本不匹配时，从当前模组手册和 `custom/` 全量重建。
- 当前早期测试版目标 Schema 为 v5。检测到旧 Schema、旧 FTS 形态或缺少 `knowledge_sources`、`task_*` 表时，删除 `knowledge.db`、WAL 和 SHM 后全量重建；不删除 JAR、`custom/` 或 `sources/` 原始文件。
- 搜索选择当前语言，并在没有对应本地化文档时回退 `neutral`；中英文文档可使用同一稳定 ID。

每次构建都会更新 `manifest.json`、`keyword-index.json`、`state.json` 和 `cache/build-report.json`。报告包含 `updatedCount`、`reusedCount`、`removedCount` 和警告列表。

当前 FTS5 使用 `content='segments'` 的 external-content 结构，完整 Markdown 仍从
`documents`/`segments` 事实表读取，FTS 不创建 `segments_fts_content` 正文副本。构建阶段在
Schema/索引创建后和同步提交前执行 `PRAGMA optimize`；全量或大批量变更额外执行 FTS5
`optimize`/merge，小规模增量更新跳过完整合并。检索按 FTS5 `rank` 排序，再由 Java 规则评分和
中文实体短语收窄。`./gradlew knowledgeBenchmark` 会在真实 JAR 语料和 10× 合成语料上记录
中文、英文、ID、多词、无结果查询的冷/热 p50/p95/p99、SQLite/FTS/dbstat 大小和
`EXPLAIN QUERY PLAN`，报告写入 `build/reports/modpedia/`。

助手浮窗顶部通过 `KnowledgeUpdateService.status()` 读取线程安全的 `KnowledgeStatus` 快照，显示来源数量、文档数量、最近更新时间以及更新/错误状态。界面不会阻塞等待扫描线程。

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

可选同义词配置位于 `config/modpedia/search-synonyms.json`：

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
config/modpedia/conversations/
├── conversation-*.json  # UI 历史、正文来源标注和搜索轨迹
└── memory.sqlite        # Community SQL 持久化模型上下文
```

旧版本会话文件中的 `memoryMessagesJson` 会在首次读取对应会话时迁移到 `memory.sqlite`，成功后清空旧字段；SQLite 写入失败时保留旧 JSON 并继续使用它。历史文件保存查询和来源 ID，不复制 `segment_markdown`；知识正文的唯一事实副本仍是 JAR 资源、`generated/*.md` 和 `custom/*.md`，知识 SQLite 与 AI 上下文 SQLite 分开维护。这样可以独立重建知识库，也可以在不膨胀 UI 历史文件的情况下调整上下文窗口。

## 10. 当前实现

第二阶段已经接入以下运行时组件：

```text
LocalGuideScanner
  ↓
MarkdownDocumentConverter / JsonGuideDocumentConverter
  ↓
KnowledgeCompiler
  ↓
config/modpedia/knowledge/
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

`knowledgeBenchmark` 测试任务不会覆盖运行中的 `config/modpedia/knowledge/`，而是在临时目录执行：

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

任务运行表与文本表共用物理数据库但互不污染：文本手册重建只替换
`knowledge_sources` 中的 `mod_manual`/`wiki` 和对应 `documents`；任务同步只替换对应
`task_snapshots` 及其子表。`knowledge.db` 同时保存事实正文、搜索段落和任务快照，AI 上下文仍单独保存到 `conversations/memory.sqlite`。
