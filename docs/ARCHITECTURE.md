# ModPedia 架构设计

## 1. 总体流程

```text
本地模组资源
    │
    ▼
KnowledgeSourceScanner
    │
    ▼
KnowledgeCompiler
    │
    ├── Markdown 文档
    ├── manifest.json
    ├── keyword-index.json
    └── knowledge.db（SQLite 派生搜索库）
    │
    ▼
KnowledgeRepository
    │
    ▼
RetrievalService
    │
    ▼
ContextAssembler
    │
    ▼
AiClient
    │
    ▼
AssistantScreen（非暂停客户端 Screen）
    │
    ├── FloatingAssistantWindow
    ├── MessageList / MessageBubble
    ├── AssistantInput
    └── SourceCard / SourceNavigator
```

## 2. 模块职责

### `knowledge`

负责从本地模组资源中发现手册文件，解析不同格式，并输出统一 Markdown。转换器不直接调用 AI。手册框架只负责提供 API，文档归属由资源路径中的内容模组 namespace 决定。

### `search`

`KnowledgeDatabase` 将自动手册和 `custom/` 文档写入 SQLite：文档表保存元数据、指纹和完整 Markdown，段落表保存完整段落与标题路径，FTS5 表保存检索字段。`RetrievalService` 优先使用 SQLite，并保留旧版 `manifest.json`/`keyword-index.json`/Markdown 回退路径。查询先由 FTS 和元数据筛选候选，再按完整段落计算规则分数并返回每篇文档的最高分段落。

公开接口保持纯 Java：

```java
RetrievalService service = new RetrievalService(knowledgeRoot);
SearchResponse response = service.search("自动合成");
service.reload();
```

客户端会话复用只读 SQLite 连接；重载时以文件指纹检测数据库变化，在锁内替换连接，避免每次查询重复打开数据库。

匹配顺序为文档/物品 ID、标题、关键词、正文段落、分类和路径；查询会进行 Unicode、大小写、标点、标识符拆分和中文双字词归一。结果按分数、文档 ID 稳定排序，默认最多返回 8 条，每篇文档只返回一个完整 Markdown 段落。

### `ai`

负责 API 请求、会话、搜索工具、超时、取消、上下文长度和来源引用。通用能力优先复用 LangChain4j `1.18.1`（Apache-2.0），而不是在 ModPedia 内重新实现一套 AI 编排：

```text
AiServices
  ├── @Tool search_knowledge
  ├── maxToolCallingRoundTrips → 搜索轮数预算
  ├── TokenStream              → 流式文本、工具调用和取消
  └── TokenWindowChatMemory    → token 窗口裁剪
          │
          ▼
PersistentChatMemoryStore → ConversationStore → conversations/*.json
```

`AiClient` 只负责构造 OpenAI Chat Completions 兼容模型和设置页的异步连通性测试。`PersistentChatMemoryStore` 只做 LangChain4j 序列化接口到本地会话文件的适配；上下文窗口、工具循环和流式协议由 LangChain4j 管理。网络请求在后台线程执行，界面线程只接收 `AssistantUiState` 快照。

`SearchKnowledgeTool` 每轮返回完整 Markdown 段落、标题路径、文档 ID、匹配分和 `has_more`。它只把本轮实际选中的文档加入已读集合，避免把“候选但未返回”的文档提前排除；模型可针对实体、步骤、配方、前置条件或故障排查改写查询继续搜索。`PromptBuilder` 同时声明中文/英文交叉检索、资料缺口和来源格式规则。

历史会话的 UI 消息、来源卡片和 `SearchTrace` 保存到 `config/modpedia/conversations/`；知识正文不复制到会话文件。API Key 只从设置或 `MODPEDIA_API_KEY` 读取，不进入搜索轨迹和错误日志。

`AiSettings.mode` 支持 `AI` 和 `SEARCH_ONLY`。仅搜索模式复用相同的 `RetrievalService` 和会话持久化，但跳过模型初始化、API Key 读取和连接测试；`LocalSearchMessageFormatter` 将完整段落转换为带来源卡片的助手消息。

### `client`

只在物理客户端加载，负责助手窗口、输入框、消息列表、加载状态、错误提示、来源预览和可选手册跳转。

阶段四的 `MockAssistantSession` 在后台调用 `RetrievalService`，把规则搜索结果转换成带来源的助手消息；`ManualSourceNavigator` 通过反射调用三个可选手册框架的公开客户端入口，因此框架模组缺少正文或未安装时仍可加载 ModPedia。

阶段四的职责边界如下：

```text
ModPediaClient
  ├── K        → Minecraft.setScreen(AssistantScreen)
  ├── F9       → KnowledgeUpdateService.rebuildAsync()
  └── ClientTickEvent.Post

AssistantScreen
  ├── WindowBounds          → 尺寸、视口比例、边距和拖拽缩放
  ├── AssistantWindowConfig → 客户端 JSON 持久化
  ├── AssistantSession      → 线程安全的会话状态接口
  ├── KnowledgeUpdateService.status() → 顶部只读状态快照
  └── AssistantGlassConfig  → 可调色半透明玻璃与高对比度回退
```

历史和设置不创建新的 Minecraft `Screen`。`AssistantScreen.SecondaryPanel` 只有
`NONE`、`HISTORY`、`SETTINGS` 三种状态；二级页面在主窗口内容之后、控件之前绘制，
通过 scissor 和 `secondaryPageBounds()` 将页面和滚动控件限制在原始 `WindowBounds` 内。
打开二级页面时底层输入框从事件列表移除，只保留为背景状态；标题栏、关闭按钮和缩放边框仍位于最上层。

界面层不直接读取手册 JAR，也不把知识库构建线程的对象暴露给渲染线程；渲染只读取 `KnowledgeStatus` 和 `AssistantUiState` 快照。

### 自定义文档导入边界

`config/modpedia/knowledge/custom/` 下的 Markdown 是人工维护的事实源，不写入 `generated/`。启动构建按以下顺序处理：

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

助手当前不再调用背景模糊入口，也不在 Screen 底层复制或重绘主帧缓冲；游戏画面直接作为窗口后的背景。`FloatingAssistantWindow` 只绘制一层可调色的蓝光半透明表面，再绘制标题、消息、输入框和边框，避免文字被底层效果污染。ModernUI 仍作为客户端侧 UI 兼容依赖保留，但不参与助手的背景模糊；高对比度或减少透明度模式使用不透明调色板。客户端源码不把第三方类加载到公共或 Dedicated Server 路径。

玻璃配置文件示例：

```json
{
  "themeColor": "#4D9CFF",
  "backgroundOpacity": 0.70,
  "glow": 0.78
}
```

## 3. 首次启动策略

首次启动不联网下载资料，而是读取当前实例中已安装模组的本地资源，并扫描 `custom/` 导入 SQLite。这样可以保证知识内容与玩家实际使用的模组版本一致。

联网资料导入后续作为独立的构建工具，不放入运行时首启流程。

## 4. 更新策略

使用以下信息计算资源指纹：

```text
模组 ID + 模组版本 + 手册资源路径 + 资源内容哈希
```

启动时读取 `config/modpedia/knowledge/state.json`：

- 指纹未变化且生成文件存在：复用现有 Markdown。
- 新增来源或指纹变化：重新转换该来源。
- 当前扫描中不存在的旧来源：删除对应生成文件。
- `manifest.json`、`keyword-index.json` 和扫描报告每次都会重新生成。
- `knowledge.db` 作为派生搜索库旁路构建：先写临时数据库，事务成功后原子替换；同步失败时继续使用上一份数据库。
- 数据库损坏或 Schema 不匹配时不复用旧数据库，从当前模组手册和 `custom/` 全量重建。

按键 `F9` 触发强制完整转换和索引重建；后台任务正在运行时，重复请求会被忽略。

## 5. 数据流约束

- 生成 Markdown 与玩家自定义 Markdown 分离。
- AI 只接收检索到的文档片段，不接收整本手册。
- 每次回答记录来源文档 ID。
- API key 不进入日志、知识库和提交记录。

## 6. 当前类结构

```text
io.ctyx.modpedia.knowledge/
├── LocalGuideScanner
├── ScannedResource
├── GuideDocumentConverter
├── MarkdownDocumentConverter
├── JsonGuideDocumentConverter
├── AppGuideDocumentConverter
├── KeywordExtractor
├── KnowledgeCompiler
└── KnowledgeUpdateService
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
io.ctyx.modpedia.ai/
├── AiAssistantSession
├── AiClient
├── AiSettings / AiSettingsStore / AssistantMode
├── LocalSearchMessageFormatter
├── SearchIntensity / SearchKnowledgeTool
├── PromptBuilder
├── ConversationRecord / ConversationStore
├── SearchTrace
└── PersistentChatMemoryStore
```

`KnowledgeUpdateService` 只在客户端初始化后启动后台任务；公共 Mod 入口不直接引用客户端类。

`KnowledgeUpdateService.status()` 以原子引用发布 `KnowledgeStatus`，浮窗顶部读取来源数、文档数、更新时间及更新/错误状态，不等待后台构建线程。

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
segments(document_id, language, segment_index, heading_path, markdown, normalized_text)
segments_fts(FTS5: title, keywords, heading_path, content)
metadata(schema_version, updated_at, document_count)
```

`knowledge.db` 不替代 `custom/*.md` 或 JAR 资源；它可以随时从事实源重建。`Reader` 复用只读连接，FTS 候选先批量加载文档元数据，避免大规模语料下的连接初始化和 N+1 查询。
