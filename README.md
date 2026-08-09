# ModPedia · 模组百科

`ModPedia` 是一个面向整合包的 AI 模组知识助手。

它在本地读取整合包中已安装模组的手册资源，将 JSON/Markdown 内容转换为统一格式，导入 SQLite 派生搜索库，再把相关资料交给 AI API 生成带来源的回答。

## 当前定位

当前版本为首个公开测试版 `v1.0.0-beta.1`，目标链路已经跑通：

```text
玩家提问 → 本地手册扫描 → Markdown 知识库 → SQLite 规则检索 → AI 回答 → 来源跳转
```

主工程不预置几百个模组的完整百科，只内置转换适配器、提示词资源和最小示例。首次启动时，ModPedia 从本地已安装模组的资源中生成知识库。

## 已实现能力

- 读取 JSON 手册页面（Patchouli、GuideME、Modonomicon）
- 读取 Markdown 手册页面
- 解析语言 key、物品、方块、配方和标签
- 转换为统一 Markdown
- 生成 `manifest.json` 和关键词索引
- 保留玩家手工编辑的知识文件
- 使用 AI API 回答模组问题，资料不足时允许继续检索
- 在没有 API 配置时切换到“仅搜索”模式
- 显示答案引用并跳转到对应百科页面
- 将向量检索作为整合包制作阶段的可选增强

## 开发环境

- 游戏版本：`1.21.1`
- Java：`21`
- 模组加载器：`21.1.244`
- Gradle Wrapper：项目内置

## 本地运行

```bash
./gradlew runClient
```

运行客户端前确保 ModernUI NeoForge JAR 位于 `run/mods/`；它是本地运行依赖，不会被提交。

## 构建

```bash
./gradlew build
```

构建产物位于：

```text
build/libs/
```

## 知识库目录

运行时知识库位于：

```text
config/modpedia/knowledge/
├── generated/   # 自动转换内容
├── custom/      # 玩家手工补充内容
├── cache/       # 索引和扫描缓存
├── knowledge.db # SQLite 派生搜索库
└── state.json   # 当前资源指纹
```

AI 相关运行数据单独保存，不复制知识正文：

```text
config/modpedia/ai.json             # API 地址、模型、搜索预算和流式开关
config/modpedia/conversations/      # UI 消息、来源卡片和搜索轨迹
```

`custom/` 的内容优先级高于自动生成内容，并且不会被重新扫描覆盖。原始 Markdown 是事实源，`knowledge.db` 只保存派生的文档元数据、完整 Markdown、段落索引和 FTS 数据。

## 项目目录

```text
src/main/java/io/ctyx/modpedia/
├── ai/           # API、会话和上下文组装
├── knowledge/    # 资源扫描、转换和缓存
├── search/       # 关键词检索与后续向量检索
└── client/       # 客户端界面与手册跳转

docs/
├── ARCHITECTURE.md
├── DEVELOPMENT.md
└── KNOWLEDGE_BASE.md
```

## 第二阶段：本地手册知识库（已完成基础版本）

- `LocalGuideScanner` 扫描已安装模组 JAR 内的本地资源。
- 支持 JSON 手册、Markdown 手册和 `zh_cn`/`en_us` 语言文件。
- 三个手册框架只提供运行时 API；实际手册来源按资源 namespace 归属到依赖它们的内容模组，框架 JAR 没有正文时标记为依赖型 JAR。
- APP JSON 手册使用 `app_json` 类型，支持书籍、分类、条目、页面展开和页级来源锚点。
- `KnowledgeCompiler` 生成统一 Markdown、`manifest.json`、`keyword-index.json`、`state.json` 和扫描报告。
- `KnowledgeCompiler` 在每次启动扫描 `custom/*.md`，按 SHA-256 指纹增量导入 SQLite；未变化文件复用旧记录，删除文件同步删除文档、段落和 FTS 记录。
- 自动生成内容与 `custom/` 手工内容分开保存。
- 客户端初始化后在后台执行知识库构建。

当前识别的资源路径：

```text
data/<namespace>/patchouli_books/**/*.json
assets/<namespace>/patchouli_books/**/*.json
assets/<namespace>/guides/**/*.md
assets/<namespace>/ae2guide/**/*.md
assets/<namespace>/guideme_guides/**/*.json
data/<namespace>/modonomicon/books/**/*.json
```

## 第三阶段：知识库增量更新（已完成）

- 使用 `模组 ID + 版本 + 资源路径 + 内容哈希` 生成来源指纹。
- 启动时只重新转换新增或指纹变化的来源，未变化来源直接复用已有 Markdown。
- 自动清理已经移除来源对应的生成文件。
- 每次构建重建 `manifest.json`、`keyword-index.json` 和 `state.json`。
- 在 `cache/build-report.json` 中记录更新、复用、删除数量及警告。
- 客户端按 `F9` 可强制完整转换并重建索引；构建期间重复请求会被忽略。

阶段三验证覆盖首次生成、未变化来源复用、强制重建、指纹变化、玩家自定义文档合并和来源删除清理。

## #4：本地知识库规则搜索后端（已完成）

- `KnowledgeDatabase` 使用 SQLite FTS5 保存完整 Markdown、段落、标题路径和搜索字段；`RetrievalService` 优先读取 SQLite，旧版缺少数据库时仍兼容 `manifest.json`、`keyword-index.json` 和 Markdown。
- 支持中文双字词、英文大小写归一、模组/物品 ID、标题、关键词、分类和路径匹配。
- 构建索引时从模组 `zh_cn` 语言表补充手册页面自身物品/方块 ID 的本地化名称，避免被关联页面的引用噪声抢占排序。
- 采用分层权重排序，多词命中增加组合分；每篇文档返回一个最高分结果。
- 搜索结果返回 Markdown 完整段落，并附带最近标题路径、文档 ID、模组和来源路径。
- 默认返回 `8` 条，可通过 `SearchQuery` 调整为 `1–20` 条。
- 索引快照支持线程安全的 `reload()` 和按索引文件更新时间自动刷新。
- 可选配置 `config/modpedia/search-synonyms.json` 支持中文/英文同义词组。
- 客户端会复用只读数据库连接，避免每次搜索重复初始化 SQLite；10× 唯一文档双语基准的搜索 p95 预算为 `50 ms`。

示例：

```json
{
  "groups": [
    ["自动合成", "autocrafting"],
    ["线缆", "cable"]
  ]
}
```

## 自定义 Markdown 启动导入（已完成）

`config/modpedia/knowledge/custom/` 是人工维护目录。文档必须使用稳定 `id`，建议同时填写 `language`：

```markdown
---
id: mypack:automation
language: zh_cn
title: 自动化说明
keywords: [自动化, automation]
source_type: manual_annotation
---

# 自动化说明

这里保留完整 Markdown，供搜索和后续模型读取。
```

每次启动的处理顺序为：

```text
扫描 custom/*.md → 读取 id/language/指纹 → 增量解析 → SQLite 事务提交 → 检索服务 reload
```

- 内容未变化时不重新解析；修改时按 `(id, language)` 替换。
- 删除文件时同步删除数据库中的文档、段落和 FTS 记录。
- 自定义文档优先级高于自动手册；`zh_cn`/`en_us` 查询也会回退到 `neutral`。
- 缺少 `id` 或 Front Matter 无法解析时记录警告并保留上一份有效数据库记录。
- 导入失败时旁路数据库事务回滚；数据库损坏或不存在时从当前手册和 `custom/` 全量重建。
- 原始 `.md` 文件始终保留，SQLite 只是可重建的派生缓存。

## 第四阶段：可移动、可缩放的助手浮窗（已实现）

助手不是常驻 HUD，而是一个临时的非暂停 Screen：

| 操作 | 行为 |
| --- | --- |
| `K` | 打开或关闭助手 |
| `Esc` | 输入框有焦点时先清除焦点，否则关闭助手；来源预览打开时先关闭预览 |
| 标题栏拖动 | 移动窗口并保存位置 |
| 四边/四角 | 调整窗口大小并显示对应系统缩放光标 |
| 消息区域滚轮 | 只滚动消息列表 |
| `Enter` | 发送单行问题 |
| 输入区 `×` | 模拟请求加载时取消 |
| `F9` | 保留知识库强制重建 |

窗口规则：

- 默认尺寸 `320×400`，最小尺寸 `160×110`；标题栏和输入区采用紧凑高度，输入框只占一行。
- 最大尺寸为 `720×720`，且宽高分别不超过游戏视口的 `85%`。
- 位置和尺寸保存在 `config/modpedia/assistant-window.json`，打开和游戏窗口缩放时都会重新约束到 `12` 像素安全边距内。
- 面板使用可调色的蓝光半透明玻璃表面；当前不再绘制底层背景模糊，游戏画面保持原始清晰度，文字和控件只在玻璃表面上绘制。
- 玻璃配置保存在 `config/modpedia/assistant-glass.json`，可修改 `themeColor`、`backgroundOpacity` 和 `glow`，下次打开助手时生效；旧版 `color`/`opacity` 字段仍可读取。
- Minecraft 高对比度选项或 `-Dmodpedia.reduceTransparency=true` 会切换到不透明表面，消息气泡、输入区和来源卡片保持高对比度。

客户端代码按职责拆分为：

```text
client/
├── AssistantScreen          # 生命周期、拖动、缩放、焦点和快捷键
├── FloatingAssistantWindow  # 浮窗表面、标题栏和缩放手柄
├── AssistantInput           # 单行输入组件
├── AssistantGlassConfig     # 玻璃颜色、透明度和发光配置
├── MessageList               # 消息布局适配器
├── MessageBubble             # 消息气泡布局结果
├── SourceCard                # 来源卡片与点击区域
├── AssistantSession          # 会话状态接口
├── MockAssistantSession      # 接入规则搜索的模拟会话
├── AiSettingsPanel           # 与历史抽屉同层的 API、流式响应和搜索预算设置
├── ConversationRenameScreen  # 历史会话重命名
├── SourceNavigator           # 手册跳转接口
└── ManualSourceNavigator     # 三种手册框架的可选跳转适配器
```

阶段四当前覆盖欢迎、提问、加载、带来源回答、无匹配、错误重试、取消、知识库状态展示和来源跳转。浮窗标题栏还提供历史抽屉和 AI 设置入口。

## #4 后半部分：AI 对话、历史与仅搜索模式（已接入）

通用 AI 编排不在项目中重复实现，使用 Apache-2.0 许可的 [LangChain4j](https://github.com/langchain4j/langchain4j)：

- `AiServices` + `@Tool`：声明 `search_knowledge` 和工具调用协议。
- `maxToolCallingRoundTrips`：限制快速、标准、深入或自定义搜索预算。
- `TokenWindowChatMemory`：按 token 窗口裁剪上下文，不手写字符串截断循环。
- `ChatMessageSerializer`/`ChatMessageDeserializer`：把模型上下文序列化到本地会话文件。
- `TokenStream`：处理文本增量、工具调用和取消；不另写 SSE JSON 拼接器。

ModPedia 自己保留的部分只有知识库工具、UI 历史摘要/来源轨迹和本地文件适配器：

```text
玩家问题
  → LangChain4j AiServices
  → search_knowledge（SQLite 完整 Markdown 段落）
  → 证据不足时由模型改写 query 继续搜索
  → TokenWindowChatMemory 控制上下文窗口
  → 回答 + 本轮来源 → conversations/
```

设置页面保存到 `config/modpedia/ai.json`，API Key 输入框只显示圆点，也支持 `MODPEDIA_API_KEY` 环境变量覆盖。默认搜索强度为标准：最多 3 轮、每轮 8 条、上下文 16000 字符。开发时可用 `-Dmodpedia.ai.mock=true` 切换回不联网的规则搜索模拟会话。

设置中的“工作模式”提供：

- **AI 回答**：调用兼容 Chat Completions 的模型，并由 LangChain4j 管理工具循环和上下文窗口。
- **仅搜索**：跳过 API 地址、模型和 API Key，直接从 SQLite 返回完整 Markdown 段落、标题路径、匹配分和来源卡片；搜索结果和轨迹仍保存到历史会话。

历史和设置都在同一个 `AssistantScreen` 内作为二级页面绘制。页面背景、控件和滚动区域使用 `GuiGraphics.enableScissor` 锁定在当前浮窗的 `WindowBounds` 内，不会创建全屏 Screen 或穿出底部输入区。

模型引用会与本轮 `SearchTrace` 交叉校验，只生成实际检索过的来源卡片；知识正文仍只保存在 SQLite，历史会话不复制整段 Markdown。

### ModernUI 依赖

1.21.1 NeoForge 客户端锁定使用 ModernUI 官方发行物中的：

```text
ModernUI-NeoForge-1.21.1-3.12.0.2-universal.jar
```

版本固定在 `gradle.properties` 的 `modernui_version=3.12.0.2`，模组元数据以精确版本范围声明客户端必需依赖。当前助手不启用 ModernUI 的背景模糊入口，只保留蓝光玻璃和不透明回退；JAR 不进入 Git，本地客户端测试时放入 `run/mods/`。

官方项目：[BloCamLimb/ModernUI-MC](https://github.com/BloCamLimb/ModernUI-MC)，对应发行页：[3.12.0.4](https://github.com/BloCamLimb/ModernUI-MC/releases/tag/3.12.0.4)。

## 知识库规模基准

基准任务读取 `run/mods/`、`~/Downloads/*.jar` 和 ModPedia 自身资源，所有构建结果写入临时目录，不覆盖运行中的知识库：

```bash
./gradlew knowledgeBenchmark
```

基准分别测试 `zh_cn` 和 `en_us` 语料，并包含当前基线、额外 JAR 实际扩展集和保持唯一 ID 的 10× 文档集。报告位于：

```text
build/reports/modpedia/knowledge-benchmark.json
build/reports/modpedia/knowledge-benchmark.md
```

默认搜索 p95 预算为 `50 ms`，给后续大语言模型请求预留响应时间。没有手册资源的前置 JAR 仍会被纳入 JAR、模组和依赖统计，并标记为“依赖型 JAR”，不会被当作扫描失败。

可通过 `-PbenchmarkSearchSamples=N` 和 `-PbenchmarkWarmupSamples=N` 调整采样次数；基准结果应以报告中的双语 `scale-10x` p95 为准。

## 配置原则

API 地址、模型名称和 API key 只保存在玩家本地配置中。示例配置和文档只使用占位符。

## Beta 安装与已知限制

安装说明见 [`INSTALL.md`](INSTALL.md)，已知限制见 [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)。Beta 发布资产包含 ModPedia JAR、SHA-256 校验文件和这两份说明。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [开发流程](docs/DEVELOPMENT.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)

## 作者

`ctyx`
