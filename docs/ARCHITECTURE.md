# ModPedia 架构设计

## 1. 总体流程

```text
游戏 JVM
    │  UI、注册表/Tooltip、可选运行时 API
    ▼
ModPediaBridge
    │  localhost JSONL 控制消息 + 随机令牌
    ▼
ModPedia Worker JVM
    ├── WorkerGuideScanner / KnowledgeCompiler
    ├── RetrievalService / SQLite / FTS5
    ├── AiClient / LangChain4j Tool Loop
    ├── ConversationStore / ChatMemory
    └── Markdown、Wiki 和任务静态导入
    │
    │  WorkerCompatibility：API level、基线、能力集合
    ▼
AssistantScreen（非暂停客户端 Screen）
    │
    ├── FloatingAssistantWindow
    ├── MessageList / MessageBubble
    ├── AssistantInput
    └── SourceCard / SourceNavigator
```

## 1.1 实际文件布局

`config/modpedia/` 分成“运行时目录”和“随整合包分发的事实源目录”。Worker 只把前者当作
可删除、可重建的派生数据目录；手册源、Wiki 和人工 Markdown 始终从后者读取：

```text
config/modpedia/
├── runtime/                         # 玩家本地运行时数据，发布整合包前删除
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
└── knowledge/                       # 整合包作者维护的事实源
    ├── custom/**/*.md
    ├── sources/<source-id>/
    │   ├── source.json
    │   ├── documents/**/*.md
    │   └── media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json                           # 跨游戏实例共享的用户级 AI 设置
├── installation-id                   # 无系统 UUID 时的共享回退标识
└── worker/lib/worker-baseline-1/      # 同一 Worker 基线共享的依赖库
```

旧版本散落在 `config/modpedia/` 根目录的运行时文件会在启动时迁移到 `runtime/`；原始
`custom/`、`sources/`、`media.json` 和覆盖文件不会被搬走或删除。旧版
`config/modpedia/ai.json` 和 `config/modpedia/runtime/ai.json` 会迁移到
`~/.modpedia/ai.json`，供不同游戏实例共享；用户级目录不属于整合包事实源。

## 2. 模块职责

### `knowledge`

负责从本地模组资源中发现手册文件，解析不同格式，并输出统一 Markdown。转换器不直接调用 AI。Patchouli、GuideME 和 Modonomicon 是可选手册框架；文档默认归属于真正提供资源的内容模组 namespace，整合包作者可以用 `knowledge` 字段或外部覆盖文件把同格式书籍归入 Wiki。

### `search`

`KnowledgeDatabase` 将自动手册、Wiki、`custom/` 文档和物品目录写入同一个 SQLite：文档表保存元数据、指纹和完整 Markdown，段落表保存完整段落与标题路径，FTS5 表保存检索字段，`item_catalog` 保存当前语言的注册物品 Tooltip。FTBQ 静态任务定义也在同一个文件中，但只使用 `task_*` 表；玩家实时进度不落库。`RetrievalService` 优先使用 SQLite，并保留旧版 `manifest.json`/`keyword-index.json`/Markdown 回退路径。查询先由 FTS 和元数据筛选候选，再按完整段落计算规则分数并返回每篇文档的最高分段落；确认物品时先读取 `item_catalog`，再继续手册搜索。

物品目录是启动阶段的高频批量路径：游戏 JVM 只负责读取注册表和 Tooltip，数万条记录先在独立 I/O 线程写成同机原子 JSONL 载荷，IPC 只传载荷路径，不在游戏线程构造超大的 JSON 数组；Worker 读取完整载荷后使用单条预编译 UPSERT 和单事务写入 `knowledge.db`。因此数据库写入、JSON 解析和提交都不占用游戏 Tick，也不会因为每条物品单独提交而产生卡顿。载荷处理完成后自动删除，异常时旧目录由 SQLite 回滚保留。

公开接口保持纯 Java：

```java
RetrievalService service = new RetrievalService(knowledgeRoot);
SearchResponse response = service.search("自动合成");
service.reload();
```

客户端会话复用只读 SQLite 连接；重载时以文件指纹检测数据库变化，在锁内替换连接，避免每次查询重复打开数据库。

匹配顺序为文档/物品 ID、标题、关键词、正文段落、分类和路径；查询会进行 Unicode、大小写、标点、标识符拆分和中文双字词归一。结果按分数、文档 ID 稳定排序，默认最多返回 8 条，每篇文档只返回一个完整 Markdown 段落。

### 统一来源模型

内容归属和文件格式分开保存：

| 字段 | 取值示例 | 作用 |
| --- | --- | --- |
| `content_kind` | `mod_manual` / `wiki` / `task_runtime` | 决定语义集合和工具范围 |
| `source_type` | `app_json` / `patchouli_json` / `guideme_markdown` / `wiki_markdown` / `task_snapshot` | 描述输入格式 |
| `origin_type` | `jar` / `local_file` / `remote` / `runtime` | 描述来源位置 |
| `collection_id` | `example-pack` / `ftbquests-wiki` | 支持多个 Wiki 集合共存 |

`knowledge_sources` 是来源注册表；`documents`、`segments` 和 `segments_fts` 保存可检索正文。
一个 JSON 书籍采用哪种格式，不再决定它是不是模组手册。书籍根资源中的
`knowledge.content_kind` 优先级最高，其次是 `source-overrides.json`、来源目录
`source.json`，最后才使用 `mod_manual` 默认值。

### `ai`

负责 API 请求、会话、搜索工具、超时、取消、上下文长度和来源引用。通用能力优先复用 LangChain4j `1.18.1`（Apache-2.0），而不是在 ModPedia 内重新实现一套 AI 编排：

```text
AiServices
  ├── @Tool search_knowledge
  ├── @Tool search_wiki / search_tasks
  ├── @Tool calculate → 本地 BigDecimal 确定性计算
  ├── maxToolCallingRoundTrips → 搜索轮数预算
  ├── TokenStream              → 流式文本、工具调用和取消
  └── TokenWindowChatMemory    → token 窗口裁剪
          │
          ▼
PersistentChatMemoryStore
  ├── Community SQL SQLChatMemoryStore → runtime/conversations/memory.sqlite
  └── 旧版 memoryMessagesJson 一次性迁移
ConversationStore → runtime/conversations/conversation-*.json
```

`CalculationTool` 只解析受限数学表达式，不执行脚本或任意 Java 代码。它支持
四则运算、取余、整数幂、括号以及 `ceil`、`floor`、`round`、`min`、`max`、
`abs`、`pow`、`sum`，使用 `BigDecimal` 返回结构化结果。模型负责把问题转换为表达式，
本地工具负责数字计算；计算结果本身不生成手册来源引用。

`AiClient` 负责统一构造四种 API 格式：Chat Completions 继续使用 LangChain4j 原生模型，原生 Messages、Responses 和 Gemini `generateContent` 使用 `ProtocolAiModel` 做协议转换。`AiSettings.apiFormat` 会随 Worker IPC 和 `ai.json` 的 `api_format` 字段往返；请求体、认证头、工具调用续接和 SSE 解析均按该字段选择。设置页连接测试和模型列表请求复用同一套端点归一化逻辑，Chat/原生 Messages/Responses 默认使用 `/v1`，Gemini 默认使用 `/v1beta`；没有 `/models` 的服务可以直接填写模型名称。全部模型批量兼容性测试仍只对 Chat Completions 开放，因为它的探测器使用该协议的四种工具链夹具。HTML 响应和 401 响应转换为不含密钥的用户提示。`AiTokenBudget` 将首轮工具参数限制为 384 tokens，并按搜索档位将最终回答限制为 1,024/2,048/3,072 tokens；GPT-5/o 系列额外改用 `max_completion_tokens`，避免 LangChain4j 的通用 `max_tokens` 字段被新模型网关拒绝。`PromptBuilder` 明确要求检索阶段静默，不发送过程性长文本。读写历史上下文时会丢弃没有对应 `ToolExecutionResultMessage` 的未完成工具调用及其后续消息，并在新问题进入时移除旧轮次的完整工具 JSON，只保留旧的用户/助手文本，避免上游返回 `No tool output found for function call` 和重复计费。503、429、网络超时和孤立工具调用会自动清理当前失败轮次并重试一次；明确的 400/401 配置错误不重复请求。上下文窗口、工具循环和流式协议由 LangChain4j 或 `ProtocolAiModel` 管理。网络请求在后台线程执行，界面线程只接收 `AssistantUiState` 快照。

NeoForge Mod 的入口装载在 Minecraft JVM 内；重型 SQLite、FTS、知识构建、网络和 AI 工作由独立 Worker JVM 执行，双方通过带随机令牌的 localhost JSONL IPC 通信。Minecraft 的 UI、注册表/Tooltip 和可选运行时 API仍由客户端线程负责。任务问题的查询顺序固定为：`search_tasks` → 取得当前玩家运行时上下文 → Worker 得到临时快照 → Worker 查询 `knowledge.db` 中的静态任务定义 → 在内存中覆盖当前状态。单机优先只发送当前存档路径描述，由 Worker 直接读取很小的 `ftbquests/<team-uuid>.snbt`；多人服务器或本地文件不可用时，游戏 JVM 才读取已同步的 TeamData 并通过 IPC 返回。无论哪条路径，实时进度只在当前请求内存中存在，不写入数据库。

持久化读写实际由 Apache-2.0 的 LangChain4j Community SQL `SQLChatMemoryStore` 完成，ModPedia 只装配已有 SQLite 驱动、文件路径和四条 SQLite 方言 SQL。这样工具调用消息仍使用 LangChain4j 官方 JSON 序列化，原始 `tool_call_id` 不经过自研格式转换。旧版本会话中的 `memoryMessagesJson` 在首次读取时迁移到 `config/modpedia/runtime/conversations/memory.sqlite`，成功后清空旧字段；迁移失败则继续使用旧 JSON。

`AiModelCompatibilityTester` 不参与正常回答链路，使用当前 API Key 读取 `/models` 后对每个模型验证四个能力：普通非流式、非流式工具结果续接、普通 SSE、流式工具结果续接。报告同时标记普通+工具可用和流式+工具可用，写入 `config/modpedia/runtime/diagnostics/ai-model-compatibility.{json,md}`；报告只保留接口主机、模型 ID、状态、耗时和脱敏错误，不保存 API Key。设置页批量按钮和 Gradle `aiModelCompatibility` 任务共用这套探测器。

`SearchKnowledgeTool` 每轮向模型返回完整 Markdown 段落、标题路径、文档 ID、匹配分和 `has_more`；跳转路径、来源类型、匹配词等诊断元数据留在 Java 侧的 `SearchTrace`，不重复发送。`language=auto` 会同时查询当前语言和另一语言，再按文档 ID 去重合并；自然语言 `focus` 会归一化为标准值并参与段落排序。工具会优先保留查询实体锚点，避免“设置/前置条件/步骤”等通用词把示例页面抬到目标手册之前。它只把本轮实际选中的文档加入已读集合，避免把“候选但未返回”的文档提前排除；模型可针对实体、步骤、配方、前置条件或故障排查改写查询继续搜索。`PromptBuilder` 同时声明中文/英文交叉检索、物品显示协议、资料缺口和来源格式规则。回答完成后只接受模型明确引用的来源，最多保留 5 个，并从 `[来源: document_id | 标注: ...]` 提取正文对应行的来源标注按钮；按钮点击优先跳转原手册，目标不可用时回退来源预览，没有明确引用时不自动展示全部候选。客户端还会对普通文本中的已注册 `namespace:path` 物品 ID 做本地化渲染，按住 Ctrl 才显示原始 ID；模型和会话始终保留原始 ID。模型回答末尾的 `<modpedia_follow_up_questions>` 协议会被提取为三个后续问题按钮，不显示原始标签。

历史会话的 UI 消息、正文来源标注、后续问题和 `SearchTrace` 保存到 `config/modpedia/runtime/conversations/conversation-*.json`；模型上下文单独保存到同目录的 `memory.sqlite`，知识正文不复制到会话文件。API Key 优先从设置读取，设置为空时回退到 `MODPEDIA_API_KEY`，不进入搜索轨迹和错误日志。`~/.modpedia/ai.json` 只保存 API Key 的 AES-GCM 密文，不保存明文；密钥由系统 UUID 派生，进程首次读取时解密并缓存。系统 UUID 变化或密文损坏时删除密钥字段，保留其他设置；系统没有可读 UUID 时使用 `~/.modpedia/installation-id`。设置保存后会原子替换并回读校验，支持 POSIX 的系统将用户目录限制为 `0700`、配置文件限制为 `0600`。

`AiSettings.mode` 支持 `AI` 和 `SEARCH_ONLY`。仅搜索模式复用相同的 `RetrievalService` 和会话持久化，但跳过模型初始化、API Key 读取和连接测试；`LocalSearchMessageFormatter` 将完整段落转换为带正文来源标注按钮的助手消息。

### `client`

只在物理客户端加载，负责助手窗口、输入框、消息列表、加载状态、错误提示、来源预览和可选手册跳转。

生产入口使用 `WorkerAssistantSession`，只接收 Worker 事件并更新 UI 快照；`MockAssistantSession` 仅用于开发自测。`ManualSourceNavigator` 通过反射调用三个可选手册框架的公开客户端入口，因此框架模组缺少正文或未安装时仍可加载 ModPedia。FTB Quests、JEI 和 Jade 也只通过客户端适配器和反射/运行时检查联动，不是 ModPedia 的硬依赖。

阶段四的职责边界如下：

```text
ModPediaClient
  ├── K        → Minecraft.setScreen(AssistantScreen)
  ├── F9       → ModPediaBridge.rebuildKnowledgeAsync()
  └── ClientTickEvent.Post

AssistantScreen
  ├── WindowBounds          → 尺寸、视口比例、边距和拖拽缩放
  ├── AssistantWindowConfig → 客户端 JSON 持久化
  ├── AssistantSession      → 线程安全的会话状态接口
  ├── ModPediaBridge.knowledgeStatus() → 顶部只读状态快照
  └── AssistantGlassConfig  → 可调色半透明玻璃与高对比度回退
```

历史和设置不创建新的 Minecraft `Screen`。`AssistantScreen.SecondaryPanel` 只有
`NONE`、`HISTORY`、`SETTINGS` 三种状态；二级页面在主窗口内容之后、控件之前绘制，
通过 scissor 和 `secondaryPageBounds()` 将页面和滚动控件限制在原始 `WindowBounds` 内。
打开二级页面时底层输入框从事件列表移除，只保留为背景状态；标题栏、关闭按钮和缩放边框仍位于最上层。

界面层不直接读取手册 JAR，也不把知识库构建线程的对象暴露给渲染线程；渲染只读取 `KnowledgeStatus` 和 `AssistantUiState` 快照。物品协议使用 `[[item:id|名称]]`、`[[tag:id|名称]]`，来源协议使用 `[[source:document_id|标注]]`；普通显示区域显示本地化名称，Ctrl 显示 ID。

### 任务、Wiki 与可选联动

`TaskKnowledgeStore` 只将 FTB Quests 的静态任务定义写入
`task_snapshots`、`task_quests`、`task_dependencies`、`task_tasks` 和
`task_rewards`。它不把任务运行数据伪装成手册段落；AI 通过 `search_tasks` 区分任务定义、实时进度和未同步状态。

任务运行时读取是按需链路，而不是客户端轮询：`search_tasks` 先取得当前玩家运行时
上下文。单机由 `FtbQuestsClientAdapter` 在客户端线程只解析当前存档根目录、玩家 UUID
和作用域元数据，Worker 的 `WorkerTaskRuntimeFileReader` 随后直接读取
`ftbquests/<team-uuid>.snbt` 中有界的开始/完成 ID 和 `task_progress`。考虑到 FTBQ
进度文件写入很快，读取器不加文件锁、不等待刷新、不写回任何数据；它在读取前后比较
文件大小、修改时间和文件标识，撞上快速重写时立即重试，确保得到完整的旧快照或新快照。
多人服务器或
本地文件不可用时，适配器才在客户端线程读取 TeamData。Worker 收到运行时快照后，
`TaskKnowledgeStore` 才从 SQLite 取得静态任务定义、依赖、要求和奖励，并用
`TaskRuntimeSnapshot` 在内存中覆盖当前状态。`SearchKnowledgeTool` 以一次 AI 请求的
`requestKey` 做读取门控，因此模型在补搜轮次中重复调用任务工具不会再次读取 FTBQ。
快照同时携带 `timeline`：started/completed 使用 FTBQ 存档中的 epoch millis，
TeamData 没有历史的进度变化使用 ModPedia 检测时间；工具会按任务 ID 补充静态标题，
让模型能够列出具体新增条目而不是只比较状态总数。
实时快照不会写入或从 `knowledge.db` 读取，查询结束后随请求对象释放；FTBQ 全量静态
定义仍在主界面前统一预填充。

`TaskWikiSyncService` 先使用内置 Wiki Markdown，再在后台尝试更新配置的远程来源，写入
`sources/ftbquests-wiki/` 的 `wiki_markdown` 文档。网络失败时沿用本地副本，不阻塞客户端。
`search_wiki` 只检索 `content_kind=wiki`，不会把任务 Wiki 混入 `search_knowledge` 的模组手册结果。

- **JEI**：不导入配方；只在客户端运行时通过反射访问当前 JEI `IRecipeManager`。`WORKBENCH` 和 `FURNACE` 直接按输出物品焦点查询对应类别，熔炉配方同时返回处理 tick/秒数且不返回机器；`OTHER` 只返回有配方的类别、`method_id` 和合并等级后的催化机器名，模型选择后用 `DETAIL` 查询具体输入、输出和附加元数据。配方查询结果通过 `RECIPE_QUERY_REQUEST/RESPONSE` 在 Worker 与客户端之间传递，不进入 `knowledge.db`。回答中的物品仍可按 Shift+左键请求 JEI 输出配方界面。
- **Jade**：检测到 Jade 时记录当前视线目标，K 打开助手后可插入目标物品令牌；目标过期或离开世界时清理。
- 三个联动缺失、API 变化或目标不存在时，适配器返回不可用状态，不影响核心搜索和服务端隔离。

### 自定义文档导入边界

`config/modpedia/knowledge/custom/` 下的 Markdown 是人工维护的事实源，不写入
`config/modpedia/runtime/knowledge/generated/`。启动构建按以下顺序处理：

```text
扫描 custom/*.md
  ↓
读取 Front Matter 的 id/language 和 SHA-256
  ↓
只解析新增/修改文件，未变化文件从 SQLite 缓存复用
  ↓
按 (document_id, language) 选择优先级最高的记录
  ↓
事务提交 documents、segments、segments_fts
  ↓
RetrievalService.reload()
```

自定义记录优先级为 `100`，自动手册为 `0`。缺少稳定 ID 或 Front Matter 解析失败时保留上一份有效记录；删除文件由当前输入集合驱动，连同段落和 FTS 记录一起删除。

### 客户端浮窗约束

`WindowBounds.clampTo(viewportWidth, viewportHeight)` 同时约束：

```java
min = 160×110;
max = min(720×720, viewport×85%);
safeArea = viewport - 12px;
```

缩放以鼠标按下时的窗口快照为基准，四边和四角分别改变对应边；每次拖动、游戏窗口缩放、关闭和移除 Screen 都会进行约束或保存。

### 玻璃表面与透明度回退

助手不在 Screen 底层复制或重绘主帧缓冲；游戏画面直接作为窗口后的背景。`AssistantScreen` 和 `FloatingAssistantWindow` 基于 NeoForge 原生 `GuiGraphics`、`Screen` 和控件 API 自绘一层可调色的蓝光半透明表面，再绘制标题、消息、输入框和边框，避免文字被底层效果污染。高对比度或减少透明度模式使用不透明调色板。客户端 UI 不依赖外部 UI 模组，客户端类也不会进入公共或 Dedicated Server 路径。

玻璃配置文件示例：

```json
{
  "themeColor": "#4D9CFF",
  "backgroundOpacity": 0.70,
  "glow": 0.78
}
```

## 3. 首次启动策略

首次启动在客户端加载屏幕期间读取当前实例中已安装模组的本地资源，扫描 `custom/` 和 `sources/` 导入 SQLite，保证手册正文与玩家实际使用的模组版本一致。随后在同一加载阶段，`ItemCatalogSyncService` 读取当前语言的全部物品 Tooltip 并写入 `item_catalog`；这一步不再等到进入世界后通过 Tick 继续扫描。只有任务 Wiki 的远程更新会在进入主菜单后后台尝试，网络失败继续使用内置或上次缓存，不阻塞基础知识库。

## 4. 更新策略

使用以下信息计算资源指纹：

```text
模组 ID + 模组版本 + 手册资源路径 + 资源内容哈希
```

启动时读取 `config/modpedia/runtime/knowledge/state.json`：

- 指纹未变化且生成文件存在：复用现有 Markdown。
- 新增来源或指纹变化：重新转换该来源。
- 当前扫描中不存在的旧来源：删除对应生成文件。
- `manifest.json`、`keyword-index.json` 和扫描报告每次都会重新生成。
- `runtime/knowledge/knowledge.db` 作为派生搜索库旁路构建：先写临时数据库，事务成功后原子替换；同步失败时继续使用上一份数据库。
- 数据库损坏或 Schema 不匹配时不复用旧数据库；从当前模组手册、`custom/`、本地 Wiki 和内置任务 Wiki 写入旁路数据库，成功校验后再原子替换正式库。替换失败时恢复上一份数据库，WAL/SHM 与临时文件一并清理；原始 JAR、Markdown 和 Wiki 源文件不删除。

按键 `F9` 触发强制完整转换和索引重建；后台任务正在运行时，重复请求合并为一次 pending 构建，不丢失 Wiki 更新，也不并发改写 SQLite。

## 5. 数据流约束

- 生成 Markdown 与玩家自定义 Markdown 分离。
- AI 只接收检索到的文档片段，不接收整本手册。
- 每次回答只记录模型明确引用的来源文档 ID，并保存其简短用途标注。
- API key 不进入日志、知识库和提交记录。

## 6. 当前类结构

```text
io.ctyx.modpedia.client/
└── LocalGuideScanner              # NeoForge 资源扫描，只在客户端适配层

io.ctyx.modpedia.knowledge/
├── ScannedResource
├── GuideDocumentConverter
├── MarkdownDocumentConverter
├── JsonGuideDocumentConverter
├── AppGuideDocumentConverter
├── KeywordExtractor
└── KnowledgeCompiler
```

```text
io.ctyx.modpedia.search/
├── KnowledgeDatabase
├── RetrievalService
├── SearchQuery / SearchResponse / SearchStatus
├── SearchResult / SearchLanguage
├── SearchTextNormalizer
└── MarkdownSegmenter
```

```text
io.ctyx.modpedia.task/
├── TaskKnowledgeStore
├── TaskSnapshot / TaskQuest / TaskRequirement / TaskReward
├── TaskQuery / TaskResponse / TaskResult
└── TaskQueryMode / TaskStatus
```

```text
io.ctyx.modpedia.ai/
├── AiAssistantSession
├── AiClient
├── AiSettings / AiSettingsStore / AssistantMode
├── LocalSearchMessageFormatter
├── SearchIntensity / SearchKnowledgeTool
├── PromptBuilder
├── ConversationRecord / ConversationStore
├── SearchTrace
├── SQLiteDialect
└── PersistentChatMemoryStore
```

`WorkerKnowledgeService` 只在 Worker JVM 中调用扫描器和编译器；客户端通过
`ModPediaBridge.rebuildKnowledge*()` 提交构建请求，并通过 `knowledgeStatus()` 读取
Worker 发布的来源数、文档数、更新时间及更新/错误状态。游戏 JVM 不直接创建编译器、
SQLite 或 FTS 连接。

### 搜索结果结构

```java
SearchResult(
    documentId, title, sourceMod, sourceType, category,
    sourceVersion, sourcePath, headingPath,
    segmentMarkdown, score, matchedTerms
)
```

`segmentMarkdown` 保留完整段落、连续列表和 fenced code block；`headingPath` 保存最近的标题层级。索引文件缺失、损坏、空查询和无匹配分别通过 `SearchStatus` 表达。

### APP 手册来源

APP 框架 JAR 与内容模组 JAR 分开处理。扫描器只接受 APP 专用的
`data/<namespace>/modonomicon/books/**/*.json` 资源，并使用内容资源的 namespace
作为 `sourceMod`；框架自身的示例资源不进入知识库。书籍、分类和条目资源都会被
转换；一个 JSON 包含多个 `entries` 时，转换器按以下形式生成独立文档：

```text
<mod_id>:app/<book_id>/<category_id>/<entry_id>
```

`source_path` 保留原始资源路径并追加 `#book=...&category=...&entry=...`，客户端跳转适配器据此尝试页级入口，找不到页级入口时回退到书籍入口。生成文件路径和 `state.json` 同时记录一个资源对应的多个输出文件，保证增量重建和删除清理不残留旧条目。

### SQLite 派生库

```text
documents(document_id, language, source_key, fingerprint, priority, metadata, markdown)
segments(document_id, language, segment_index, heading_path, title, keywords, markdown, normalized_text)
segments_fts(FTS5 external-content -> segments: title, keywords, heading_path, normalized_text)
knowledge_sources(source_id, collection_id, content_kind, source_type, origin_type, version, fingerprint)
item_catalog(item_id, language, display_name, display_name_normalized, description_markdown, source_mod, fingerprint)
task_snapshots / task_quests / task_dependencies / task_tasks / task_rewards
metadata(schema_version, updated_at, document_count)
```

当前派生库为 Schema v7，早期测试阶段不做旧库迁移；检测到版本、FTS 形态或 `item_catalog` 缺失时，把新结构写入 staged 数据库，校验成功后再替换正式库，失败时恢复旧库。`documents.markdown` 和 `segments.markdown` 仍然保存完整 Markdown，FTS5 使用 `content='segments'` 的 external-content 形态，不再创建 `segments_fts_content` 正文副本；`segments.normalized_text` 同时供 Java 二次评分和 FTS 中文双字词召回使用。`item_catalog` 保存完整 Tooltip Markdown，但不参与手册 FTS。物品目录同步不复制整个 `knowledge.db`，而是在 Worker 内使用单事务、预编译 UPSERT 和批量绑定，SQLite 回滚保证失败时保留旧目录。`task_*` 只保存静态任务定义，实时玩家进度只存在于 `TaskRuntimeSnapshot` 内存对象。`knowledge.db` 不替代 `custom/*.md` 或 JAR 资源；它可以随时从事实源重建。`Reader` 复用只读连接，FTS 候选先批量加载文档元数据，避免大规模语料下的连接初始化和 N+1 查询。

写入完成后执行 `PRAGMA optimize`；全量构建或达到自适应大批量阈值时额外执行 FTS5 `optimize`/merge，小规模增量更新不重复合并整个 FTS B-tree。查询按 FTS5 隐藏 `rank` 列排序，避免 `bm25(...)` 触发额外的排序临时表。性能基准由 `knowledgeBenchmark` 记录冷/热查询 p50/p95/p99、`dbstat` 对象大小和 `EXPLAIN QUERY PLAN`，不把操作系统页缓存清空称为冷查询。

### Worker 请求与线程契约

```text
IPC reader
  ├─ raw listener：只处理明确声明 reader 线程的协议/诊断逻辑
  ├─ client listener：Bridge 统一切回 Minecraft 主线程
  └─ submit runtime context：不在 reader 上等待

Worker
  ├─ bounded AI executor：默认 2 并发 + 4 排队，按 conversation_id 串行
  ├─ single knowledge executor：扫描、Wiki、物品目录和 SQLite 写入隔离
  └─ read-only retrieval connections
```

Worker 的嵌入依赖不再写入每个实例的 `config/modpedia/runtime/worker/lib/`，而是放在用户级
`~/.modpedia/worker/lib/worker-baseline-1/`。固定基线编号表示一套兼容的 Worker 运行库；
只要依赖没有增删或升级，不同 ModPedia 版本和不同整合包实例共用同一目录。依赖发生变化时
必须递增基线编号，例如改为 `worker-baseline-2`，避免新旧运行库混用。实例级
`runtime/worker/` 仍只保存 `worker.log`、临时 payload 和其他可删除运行时文件。
共享目录中的 `manifest.sha256` 记录每个嵌入 JAR 的大小和 SHA-256。启动时由客户端按当前
发布 JAR 校验并在文件锁内原子修复，Worker JVM 建立 IPC 前再次校验清单；因此多个游戏实例
可以并发启动而不会使用截断或跨基线的依赖。

运行时 FTBQ 进度只在需要回答任务问题时读取。客户端侧读取协调器限制为一个活动
上下文读取，相同聊天请求合并，短时快照复用；超时、取消、世界切换和退出都会清理
等待者。任务静态定义继续进入 `knowledge.db`，玩家实时进度不写入数据库。
