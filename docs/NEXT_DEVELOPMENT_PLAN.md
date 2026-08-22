# 后续开发计划：AI 上下文、数据库 v8 与外部百科

> 本文把原来的 AI 持久化上下文研究、数据库 v8 研究和外部百科按需增强方案合并为一个后续计划入口。
> 它是专题实施计划；版本级优先级和 M0–M5 总路线仍以 [ROADMAP.md](ROADMAP.md) 为准。

状态标记：**[x]** 已完成，**[~]** 已实现但需要人工回归，**[ ]** 未开始，**[-]** 暂缓或不进入当前版本。

## 1. 当前基线与总体目标

### 1.1 当前基线

| 项目 | 当前值 |
| --- | --- |
| 当前技术基线 | **worker-baseline-1 / knowledge.db Schema v7** |
| Minecraft / NeoForge | **1.21.1 / 21.1.x** |
| Java | **21** |
| 知识库 | knowledge.db Schema v7 |
| FTS | SQLite FTS5 external-content |
| AI 上下文 | LangChain4j Community SQL + SQLite 适配层 |
| 外部百科 | 研究完成，运行时增强暂缓 |

当前已经具备本地手册、Wiki、任务静态定义、物品目录、AI 工具调用、历史会话和仅搜索模式。下一阶段不再分别维护三套互相独立的研究结论，而是围绕同一条链路推进：

~~~
本地事实源
  → Worker 构建与版本追踪
  → knowledge.db v8（候选升级）
  → 本地检索与 AI 上下文
  → 本地证据不足时的外部百科增强
  → 可审计来源和可回滚结果
~~~

### 1.2 统一目标

1. 会话上下文使用成熟的 ChatMemoryStore 实现，ModPedia 只维护产品层的会话、搜索轨迹和恢复策略。
2. 数据库 v8 固定来源、版本、解析器、构建批次和内容类型，使模组更新可以局部重建并在失败时保留上一份有效库。
3. 外部百科只作为本地证据不足时的可选增强，不改变本地手册和 Wiki 的优先级，也不阻塞启动、搜索或游戏运行。
4. 三条路线共享 Worker、知识库写入队列、来源协议、缓存边界、成本预算和测试夹具。

## 2. 设计原则与边界

### 2.1 本地优先

~~~
item_catalog / 本地手册 / 本地 Wiki / 静态任务定义
                         ↓
                 search_knowledge / search_tasks
                         ↓
          证据充分 → 直接回答
          证据不足 → 按预算触发外部百科
~~~

本地结果优先于远程结果。远程页面必须保留 canonical URL、抓取时间、解析器版本和指纹，且明确标记为外部来源。

### 2.2 单库但分类型

继续使用一个 knowledge.db，不为百科或配方创建第二套数据库。内容通过 content_kind、source_type、origin_type 和 collection_id 区分：

~~~
mod_manual   模组手册
wiki         Wiki、整合包作者文档和任务 Wiki
task_runtime 任务静态/运行时关联数据
recipe       未来的结构化配方内容
external     外部百科或其他远程来源
~~~

外部 HTML/JSON 只在 Worker 内存中短暂存在。进入缓存或知识库的只能是经过清洗和校验的 Markdown 与元数据。

### 2.3 Worker 隔离

~~~
Minecraft JVM
  ├─ UI、注册表、Tooltip、Jade/JEI/FTBQ 可选适配
  └─ ModPediaBridge

Worker JVM
  ├─ AI 请求和工具循环
  ├─ ChatMemoryStore
  ├─ SQLite / FTS5 / 知识库构建
  ├─ 外部百科抓取与 Markdown 转换
  └─ 缓存、诊断和原子替换
~~~

网络、解析、压缩、数据库写入和会话持久化不进入 Minecraft 渲染线程或 ClientTick。

## 3. AI 持久化上下文

### 3.1 当前决策

状态：**[x]** 方案已选，**[~]** 完整真实模型链路仍需回归。

首选 LangChain4j Community SQL：

~~~
dev.langchain4j:langchain4j-community-sql:1.18.0-beta28
~~~

它负责通用 ChatMemoryStore、消息 JSON 序列化、工具调用消息和工具结果的读写。项目继续使用已有 Xerial SQLite 驱动；Community SQL 没有专用 SQLite 方言时，增加只包含必要 SQL 的 SQLiteDialect 胶水层，不重新实现消息生命周期。

研究依据：

- [LangChain4j Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory)
- [ChatMemoryStore 接口](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java)
- [Community SQL 模块](https://github.com/langchain4j/langchain4j-community/tree/1.18.0-beta28/chat-memory-stores/langchain4j-community-sql)
- [Community SQL artifact](https://central.sonatype.com/artifact/dev.langchain4j/langchain4j-community-sql/1.18.0-beta28)

### 3.2 职责边界

| 层 | 保存内容 | 处理方式 |
| --- | --- | --- |
| SQLChatMemoryStore | AI 上下文消息、工具调用请求、工具结果、tool-call ID | Community SQL + SQLite 方言 |
| TokenWindowChatMemory | 当前请求的上下文窗口 | 使用 LangChain4j 官方实现 |
| ConversationStore | UI 历史、回答正文、来源标注、后续问题、SearchTrace | 保留 ModPedia 产品数据模型 |
| 业务恢复策略 | 重试、中断清理、孤立工具结果修复 | 保留在 AI 请求生命周期中 |

ChatMemoryStore 不承担 UI 历史和搜索轨迹职责；ConversationStore 也不重复实现通用消息序列化。

### 3.3 迁移与恢复步骤

~~~
读取会话
  ↓
优先读取 Community SQL
  ↓
发现旧 JSON 会话
  ↓
一次性迁移 memoryMessagesJson
  ↓
校验 assistant.tool_calls 与 tool_call_id 配对
  ↓
成功后保留产品历史，失败时继续使用旧 JSON
~~~

要求：

- SQLite 写入失败时保留上一份上下文，不覆盖有效文件；
- 迁移失败时保留旧会话 JSON；
- 重试前清理没有对应工具结果的未完成工具轮次；
- 同一 conversationId 的活动请求串行，避免两个请求交错写入上下文；
- Worker 的 AI executor 有界，知识库 executor 与 AI executor 分离；
- Dedicated Server 不加载客户端 UI、AI 客户端和第三方客户端反射类。

### 3.4 AI 上下文验收

- [x] 普通消息、工具调用和工具结果使用统一序列化格式。
- [x] 重启后历史会话和本地上下文可以恢复。
- [~] 真实模型的多轮补搜、流式工具调用、取消、超时和重试保持消息顺序。
- [ ] 旧 JSON 会话迁移、损坏恢复和同会话并发的完整整合包回归。
- [ ] 记录上下文命中率、工具轮数、输入/输出 Token 和恢复失败率。

## 4. 数据库 v8 设计路线

### 4.1 v7 基线

v7 继续作为当前稳定运行基线：

~~~
knowledge_sources
documents
segments
segments_fts             SQLite FTS5 external-content
item_catalog
task_snapshots / task_quests / task_dependencies / task_tasks / task_rewards
~~~

v7 已经支持来源注册、内容类型分离、完整 Markdown、物品目录、任务静态数据和玩家查询时的运行时进度读取。v8 是下一阶段设计，不在没有真实整合包基准前直接替换当前库。

### 4.2 v8 目标

~~~
一个实例一份数据库
+ 当前安装模组版本的知识快照
+ 可重复的来源/解析器指纹
+ 可追踪的构建批次和失败状态
+ 稳定的文档与物品别名
+ 原子更新、失败保留旧库
+ 为 recipe / external 预留类型
+ LZ4 Fast 压缩事实正文，保持 FTS 字段为普通文本
~~~

数据库只保存当前可检索内容；历史记录保存版本、指纹、时间、解析器和构建结果，不默认堆积旧 Markdown 正文。

### 4.3 来源与版本表

在现有 knowledge_sources 上补充模组版本和解析器身份：

~~~sql
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
~~~

新增来源版本审计表：

~~~sql
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
~~~

status 使用 discovered、building、ready、failed、removed。有效指纹由原始资源、来源格式、解析器版本、语言回退策略和 Markdown 分段器版本共同生成：

~~~
SHA256(raw_source_fingerprint + source_type + parser_id + parser_version
       + language_fallback_version + markdown_segmenter_version)
~~~

### 4.4 文档、构建和别名

documents、segments 和 segments_fts 保持当前查询契约，增加以下追踪字段：

~~~
mod_version
parser_id
parser_version
revision_id
last_seen_build_id
~~~

新增构建批次：

~~~sql
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
~~~

版本更新和来源路径变化预留：

~~~sql
document_aliases(
    old_document_id TEXT NOT NULL,
    new_document_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(old_document_id, source_id)
)

item_aliases(
    old_item_id TEXT NOT NULL,
    new_item_id TEXT NOT NULL,
    source_mod TEXT NOT NULL,
    mod_version TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(old_item_id, source_mod)
)
~~~

### 4.5 大文本压缩

只采用 LZ4 Fast，不把整个 SQLite 文件包装成压缩容器。SQLite 页面、普通索引和 FTS5 索引继续使用原生格式。

候选压缩字段：

~~~
documents.markdown
segments.markdown
task_snapshots.raw_json
task_quests.raw_json
task_tasks.raw_json
task_rewards.raw_json
item_catalog.description_markdown
~~~

每个字段同时保存编码和解压后的 UTF-8 字节数：

~~~
<payload>_codec = lz4
<payload>_raw_bytes = 原始字节数
~~~

以下字段保持普通文本：

~~~
segments.title
segments.keywords
segments.heading_path
segments.normalized_text
segments_fts 的可检索列和索引数据
~~~

读写协议：

~~~
UTF-8 文本 → LZ4 Fast BLOB + raw_bytes
查询命中 → 解压 → 长度校验 → 搜索/模型/UI
~~~

压缩和解压只运行在 Worker 知识库线程；批量导入使用单事务和预编译语句。未知 codec、长度不匹配或损坏 BLOB 会使 staged 构建失败并继续使用上一份有效库。

### 4.6 预留内容类型

未来配方使用：

~~~
content_kind = recipe
source_type = recipe_runtime | recipe_json | recipe_markdown
~~~

未来外部来源使用：

~~~
content_kind = external
source_type = external_markdown | external_json | external_runtime
~~~

配方进入数据库前仍保持 JEI 按需查询，不导入全量配方。recipe 可以同时保存结构化字段和模型可读 Markdown；external 复用通用文档、段落、FTS 和来源字段，不新增每种外部来源的专用表。

### 4.7 更新、事务与性能

~~~
创建 build_id
  ↓
扫描并转换来源
  ↓
写入 staged 数据库/事务
  ↓
所有来源成功
  ↓
标记 build=ready
  ↓
原子替换 knowledge.db
~~~

模组版本或有效指纹变化时只重建对应来源；版本、指纹和解析器都未变化时复用文档。完整扫描确认来源消失后才删除当前文档，并保留 removed 审计记录。

规则：

- 同一时间只允许一个数据库写事务；
- 读连接使用 query_only；
- segments 与 FTS 写入在同一事务内完成；
- 完整构建后执行 PRAGMA optimize 和一次 FTS optimize/merge；
- 小规模增量更新不重复执行完整 FTS optimize；
- 数据库损坏时从 JAR、Wiki 源文件和 custom/ 全量重建；
- 重建过程不删除事实源；
- 只有真实整合包 v7/v8 对照证明收益后，才启用压缩字段和版本升级。

### 4.8 v8 验收标准

- [ ] PRAGMA user_version = 8，并完成 v7/v8 旁路对照。
- [ ] 模组版本、解析器版本、来源版本和构建批次可追踪。
- [ ] 更新失败时上一份有效库仍可搜索。
- [ ] 来源删除、文档改名和物品 ID 变化可以通过审计或 alias 处理。
- [ ] recipe 和 external 可作为预留类型，不污染默认手册搜索。
- [ ] LZ4 往返、损坏 BLOB、未知 codec 和长度校验有自测试。
- [ ] 相同 ATM10 数据集对比数据库大小、构建耗时、冷/热查询 p50/p95/p99、BM25、短语匹配、删除和回滚。
- [ ] v8 p95 相对 v7 无明显回归，目标增幅不超过 5%。

## 5. 外部百科按需增强

状态：**[~]** 方案研究完成；**[-]** 当前版本暂缓生产接入，后续按 Phase 0–5 重新开启。

这里的外部百科方案以 search-on-mcmod 作为可选定位能力候选。它不是本地知识库的事实源，也不应成为核心加载路径的硬依赖。

### 5.1 依赖与许可边界

在进入实现前完成：

1. 固定可验证的 commit/tag，而不是跟随默认分支。
2. 确认仓库许可证、再发布权限和 Minecraft/NeoForge 兼容矩阵。
3. 列出实际公开入口：导航、页面定位、canonical URL 或 API。
4. 没有稳定公开 API 或许可证依据时，只保留独立 adapter 或运行时软依赖，不复制源码。
5. 第三方更新只替换 locator/adapter，不改变 ModPedia 的 Markdown、来源和搜索契约。

页面导航和页面正文读取是两种能力：只打开页面时不抓取 HTML；读取正文时必须经过独立的 fetcher、转换器和质量校验。

### 5.2 统一接口

~~~java
interface EncyclopediaPageLocator {
    Optional<EncyclopediaPageRef> locate(ItemIdentity item);
}

interface EncyclopediaPageFetcher {
    FetchResult fetch(EncyclopediaPageRef page, FetchPolicy policy);
}

interface EncyclopediaDocumentConverter {
    ConversionResult convert(FetchedPage page);
}

interface EncyclopediaEnricher {
    EnrichmentResult enrich(ItemIdentity item, EnrichmentRequest request);
}
~~~

核心值对象：

~~~
ItemIdentity       item_id / display_name / mod_id / language
EncyclopediaPageRef canonical_url / page_kind / locator / confidence
FetchedPage        final_url / status / content_type / memory_body / fingerprint
KnowledgeDocument  stable_id / Markdown / source URL / parser version / metadata
~~~

### 5.3 触发规则

模型只调用结构化工具，例如：

~~~
lookup_external_encyclopedia_item(item_id, display_name, reason, language)
~~~

工具负责校验物品 ID、注册表身份、站点白名单、重定向、超时和缓存。模型不直接提供任意 URL。

只有同时满足以下条件才触发外部读取：

1. 本轮已经执行至少一次本地 search_knowledge；
2. 本地结果为空、相关性不足或缺少当前问题所需焦点；
3. 查询或 UI 目标包含稳定物品 ID；
4. 用户已开启远程百科增强；
5. 本轮尚未用尽外部增强预算。

每次回答最多执行一次页面定位和一次页面抓取；外部增强不得递归触发新的百科读取。

### 5.4 页面转换范围

不把整页 HTML 原样送入模型，采用：

~~~
HTML → 页面结构白名单 → EncyclopediaSection → Markdown
~~~

第一阶段只处理：

- 页面标题、物品名称和 ID；
- 模组/整合包归属；
- 基础描述；
- 获取方式；
- 用途；
- 可可靠识别的配方和属性表；
- 明确的版本说明。

评论、广告、推荐位、脚本、隐藏节点、复杂图片文字和语义不确定的表格只保留链接或直接丢弃。

生成文档保留以下元数据：

~~~yaml
content_kind: external
source_type: external_markdown
origin_type: remote
source_url: https://...
canonical_url: https://...
fetched_at: 2026-08-13T00:00:00Z
parser_version: mcmod-html-v1
language: zh_cn
source_fingerprint: sha256:...
~~~

外部页面中的指令性文本按普通事实资料处理，不改变系统规则、工具权限或请求目标。

### 5.5 两级缓存与知识库关系

~~~
L1 请求内缓存
  只服务当前 AI 请求，结束后释放

L2 本地百科缓存
  config/modpedia/knowledge/cache/encyclopedia/
  只保存转换后的 Markdown 和元数据
~~~

缓存键为 canonical URL + locale + parserVersion。原始 HTML/JSON 仅存在于 Worker 内存，临时文件、日志、SQLite 和 IPC 均不接收它们；转换失败时保留已有 Markdown 缓存。

分阶段处理：

~~~
Phase 1 只返回本次请求上下文，不进入 knowledge.db
Phase 2 缓存通过校验的 Markdown 和元数据
Phase 3 可选导入 content_kind=external 的 FTS
~~~

远程内容优先级低于本地来源：

~~~
本地自定义修正 100
本地官方手册 80
本地 Wiki      60
远程百科       30
远程搜索摘要   10
~~~

默认 TTL 为 7–30 天可配置。过期缓存可以先返回并标记 stale；刷新失败继续使用旧缓存。本地 custom/ 永远由作者控制，不被远程内容覆盖。

### 5.6 网络、线程和失败降级

网络边界：

- 仅允许 https 和白名单 host；
- 校验每次重定向后的最终 URL；
- 限制连接/读取超时、响应字节数和重定向次数；
- 不发送 Cookie、Authorization 或 AI 配置；
- 日志只记录状态、耗时、字节数和脱敏指纹。

线程边界：

- 抓取和解析只在 Worker 有界 executor 执行；
- 同一 canonical URL 的并发请求合并；
- 原始响应转换为 Markdown 后立即释放；
- 不在渲染线程、ClientTick 或 Minecraft world 对象上执行网络、解析和写库。

失败时回退本地搜索，并保留已有缓存。第三方缺失、接口变化、超时、非 2xx、验证码、结构异常、超限和质量校验失败均与客户端启动、SQLite 主构建和普通 AI 对话隔离。

### 5.7 外部百科分阶段路线

#### Phase 0：依赖与定位验证 [ ]

- 固定版本、核验许可和兼容性；
- 记录公开入口和定位结果；
- 使用一个已知物品做不进入生产代码的 spike；
- 第三方缺失时确认本地搜索和启动链路保持正常。

#### Phase 1：物品身份与页面导航 [ ]

- 复用现有 ItemQueryParser、JEI/Jade 目标数据；
- 实现 ItemIdentity 和 EncyclopediaPageLocator；
- 精确定位失败时回退站内搜索；
- 不抓取 HTML、不进入 FTS。

#### Phase 2：页面读取与 Markdown 转换 [ ]

- 实现 fetcher、转换器、超时、大小、编码和重定向限制；
- 使用本地 HTML fixture 覆盖成功页、错误页、超大页和结构变更；
- 写入转换后的 Markdown 和元数据缓存。

#### Phase 3：按需 AI 工具 [ ]

- 新增结构化外部百科工具；
- 本地证据不足时最多触发一次；
- 返回来源 URL、缓存状态和资料缺口；
- 加入外部正文隔离和请求预算。

#### Phase 4：可选持久导入 [ ]

- 将通过校验的 Markdown 以 content_kind=external 增量导入 FTS；
- 支持 TTL、stale、来源清理和独立优先级；
- 本地手册和 custom/ 优先。

#### Phase 5：扩展栏目 [ ]

按独立 fixture 和质量阈值逐项增加：基础信息、配方、获取方式、用途、方块/实体/流体、模组总览、版本差异和相关条目。

### 5.8 外部百科验收

- [ ] 本地证据充分时无外部网络请求。
- [ ] 外部读取最多一次，失败时本地回答仍可完成。
- [ ] 原始 HTML/JSON 不落盘、不入库、不经 IPC 传输。
- [ ] 只允许白名单站点和结构化工具调用。
- [ ] 页面标题、正文、来源 URL、抓取时间和解析器版本可追踪。
- [ ] 外部内容不会改变工具调用或系统提示规则。
- [ ] 本地来源优先级、缓存 TTL、stale 和删除策略可测试。

## 6. 三条路线的统一实施顺序

| 阶段 | 工作内容 | 状态 |
| --- | --- | --- |
| P0 | 固定当前 worker-baseline-1/v7 基线、补齐指标和失败恢复夹具 | [~] |
| P1 | 完成 Community SQL 的 SQLite 方言、会话迁移和同会话串行 | [~] |
| P2 | 实现 v8 来源版本、构建批次、别名和内容类型预留 | [ ] |
| P3 | 在真实整合包上做 v7/v8 staged 对照；证据充分后再启用 LZ4 | [ ] |
| P4 | 完成外部百科 Phase 0–1，只做定位和导航 | [-] |
| P5 | 完成外部百科 Phase 2–3，增加按需读取和临时上下文 | [-] |
| P6 | 根据真实召回缺口决定 Phase 4 持久导入和 Phase 5 栏目扩展 | [-] |

依赖关系：P1 不依赖远程百科；P2/P3 先稳定本地知识库；P4 在依赖、许可和定位能力确认后开启；P5/P6 不得反向改变本地搜索的基础契约。

## 7. 统一性能、成本和质量指标

### 本地知识库

~~~
规则搜索 p95 ≤ 50 ms
结果不重复，每篇文档最多返回一个最佳段落
中文、英文、ID、neutral 回退稳定
完整构建可区分成功、失败和中断
~~~

### AI 上下文

~~~
工具调用轮数、输入/输出 Token、缓存命中率、补搜次数可统计
同一会话请求顺序稳定
工具调用消息无孤立结果
仅搜索模式不产生网络请求
~~~

### 外部百科

~~~
本地证据充分时外部请求数 = 0
单次请求外部抓取 ≤ 1
原始响应持有时间和字节数受限
远程失败不影响本地回答和游戏启动
~~~

## 8. 测试计划

### 纯 Java / Worker 测试

- Community SQL 消息写入、读取、更新和删除；
- assistant.tool_calls 与 tool_call_id 配对；
- 旧 JSON 迁移失败保留原文件；
- 同会话并发请求串行；
- v8 Schema、来源版本、构建批次和 alias；
- LZ4 空文本、中文、英文、ID、长 Markdown、JSON、损坏 BLOB 和未知 codec；
- FTS 搜索、BM25、短语、删除、增量更新和事务回滚；
- 外部百科 locator、fetcher、转换器和缓存全部使用本地 fixture；
- 站点白名单、重定向、超时、大小上限、错误页和结构变更；
- 原始 HTML/JSON 仅保留在 Worker 内存，文件、SQLite、日志和 IPC 均不接收它们。

### 真实整合包回归

1. 记录 JAR 数、来源数、文档数、段落数、物品目录数和未识别路径。
2. 保存 v7 基线数据库大小、正文表大小、FTS 大小、构建耗时和查询 p50/p95/p99。
3. 在旁路库完成 v8 构建并重复同一批查询，比较结果集合和来源跳转。
4. 验证 Worker 重启、游戏重启、模组更新、模组删除和构建失败恢复。
5. 外部百科开启/关闭各测试一次，确认本地证据充分时不会产生远程请求。

### 验证命令

~~~bash
cd /Users/chenhong/Documents/modpedia
./gradlew test
./gradlew build
git diff --check
~~~

## 9. 暂缓项与明确不采用的方案

- 当前不把外部百科接入核心启动和默认搜索；
- 不让模型直接请求任意 URL；
- 不复制没有明确许可证和稳定 API 的第三方源码；
- 不把整页 HTML 原样送给模型；
- 不在客户端 Tick、渲染线程或 Minecraft world 对象上抓取和解析网页；
- 不为百科、配方或每个外部来源新增独立数据库；
- 不在真实性能和召回证据前引入向量数据库；
- 不导入 JEI 全量配方；
- 不默认保留所有历史 Markdown 正文；
- 不持久化玩家实时任务进度；
- 不在没有 v7/v8 对照数据前把 LZ4 或 v8 标记为稳定发布能力。

## 10. 交付与文档维护

每个阶段完成后同步：

- README.md / README.en.md：用户可见行为、配置和已知限制；
- [ARCHITECTURE.md](ARCHITECTURE.md)：线程、IPC、数据库和外部来源边界；
- [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md)：Schema、来源、缓存和检索契约；
- [DEVELOPMENT.md](DEVELOPMENT.md)：自测和开发分支交付边界；
- ROADMAP.md：版本级优先级和状态；
- 发布版本和更新日志不在本分支维护，交给 `main` 分支作为唯一事实源。

提交前必须区分：已实现、已通过纯 Java/Worker 测试、已通过真实游戏回归和仍处于研究阶段的内容。数据库 v8 与外部百科只有完成对应人工验收后，才从计划状态进入版本路线。
