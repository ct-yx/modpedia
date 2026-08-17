# ModPedia · 模组百科

ModPedia 是一个面向 Minecraft 整合包的本地模组知识助手：它读取已安装模组中的手册资源，转换为统一 Markdown，写入 SQLite 检索库，再以搜索结果或 AI 回答的方式呈现，并保留原手册来源跳转。

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ct-yx/modpedia?include_prereleases&label=release)](https://github.com/ct-yx/modpedia/releases)

## 当前版本

| 项目 | 版本/状态 |
| --- | --- |
| Mod | **v1.0.0-fix** |
| 发布状态 | GitHub 修复预发布 |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.244**（兼容 **21.1.x**） |
| Java | **21** |
| Mod ID | **modpedia** |
| 客户端 UI 依赖 | 无外部 UI 依赖（基于 NeoForge 原生 GUI API 自绘） |
| 作者 | **ctyx** |

当前修复包的 JAR、校验文件、安装说明和已知限制位于 [GitHub Release](https://github.com/ct-yx/modpedia/releases/tag/v1.0.0-fix)。`v1.0.0` 保留为稳定基线，`v1.0.0-fix` 用于验证本次 API Key 存储和 Worker 启动链路修复。

## 快速安装

### 必需环境

1. 安装 Minecraft **1.21.1**。
2. 安装 NeoForge **21.1.x**。
3. 安装 Java **21**。

### 安装步骤

1. 下载 **modpedia-1.0.0-fix.jar**。
2. 将 ModPedia JAR 放入实例的 **mods/** 目录。
3. 启动游戏，进入单人世界或服务器。
4. 等待加载屏幕完成首次知识库和物品目录预填充；需要立即重建时按 **F9**。
5. 进入游戏后按 **K** 打开助手。

ModPedia 不捆绑 Patchouli、GuideME、Modonomicon 或内容模组。它们作为可选手册适配对象存在，实际正文来自整合包中安装的内容模组。

以下联动模组均为可选：

- **FTB Quests**：不再在客户端 Tick 中轮询或全量序列化任务。任务问题调用 `search_tasks` 时先取得当前玩家运行时进度；单机优先由 Worker 直接读取 `saves/<世界>/ftbquests/<team-uuid>.snbt`，多人或本地文件不可用时回退到游戏 JVM 的 TeamData，再由 Worker 查询静态任务数据库并在内存中覆盖结果。同一 AI 请求只读取一次，实时进度不写入数据库。运行时响应还会返回具体任务的 `timeline`：started/completed 使用 FTBQ 时间戳，进度变化使用检测时间，模型可以列出新增条目而不是只比较数量。任务 Wiki 作为独立的 `content_kind=wiki` 来源导入，不与模组手册混在检索范围内。
- **JEI**：不导入配方数据库。回答正文中的已注册物品 ID（包括模型直接输出的 `namespace:path`）按需解析为本地化名称，按住 Ctrl 显示 ID，按住 Shift 点击物品名称时尝试打开 JEI 配方界面。
- **Jade**：在 Jade 已安装时记录当前视线下的方块物品，打开助手后可一键插入物品 ID；显示区域默认显示名称，按住 Ctrl 才显示 ID。
- **物品目录**：在进入主菜单前，客户端注册表完成后把当前语言的全部物品 ID、名称和完整 Tooltip 简介写入同一个 `knowledge.db` 的 `item_catalog` 表；数万条记录先由独立 I/O 线程写成原子 JSONL 载荷，IPC 只传路径，Worker 再用短事务批量写入，不复制整个数据库，也不在游戏 Tick 中拼接大 JSON。确认物品后，AI 和仅搜索模式会先读取这份资料，再继续搜索手册。语言切换只在回到主菜单后重新捕获，不会在世界内持续扫描。

缺少这些联动模组时，助手、手册扫描、SQLite 搜索和 AI 仍可正常加载。助手界面本身不依赖外部 UI 模组。

## 第一次使用

### 仅搜索模式

适合没有 AI API 或希望完全离线使用的情况：

1. 按 **K** 打开助手。
2. 打开“设置”。
3. 将“工作模式”切换为“仅搜索”。
4. 在输入区输入模组、机器、物品或配方关键词。

此模式直接读取本地 SQLite，返回完整 Markdown 段落、标题路径、匹配分和正文内来源标注按钮，不读取 API 配置。

### AI 回答模式

在设置中选择“AI 回答”，填写：

- API 地址：兼容 Chat Completions 的 API 根地址，通常以 `/v1` 结尾；如果只填写域名，客户端会自动补全 `/v1`；
- 模型名称：可以点击右侧“获取模型列表”，成功后再次点击按钮在返回的模型之间切换；
- API Key：优先使用设置页输入；设置页留空时才使用环境变量 MODPEDIA_API_KEY。
- `config/modpedia/runtime/ai.json` 不保存 API Key 明文，而是使用当前系统标识派生的 AES-GCM 密钥保存密文；游戏进程首次读取时解密并缓存，系统标识变化后会清除密钥密文。系统标识读取失败时使用运行目录中的 `installation-id` 回退标识。
- 不需要逐个模型手测：点击设置底部“批量测试模型”，会自动探测 `/models` 返回的全部模型，分别验证普通请求、工具调用续接、SSE 和流式工具续接；脱敏报告写入 `config/modpedia/runtime/diagnostics/`。

如果连接测试提示“API 地址返回了网页内容”，说明地址指向了网页或服务根页面，而不是 API 端点；优先检查 `/v1` 路径。模型列表和连接测试都不会把 API Key 写入日志。

批量测试把模型分为“普通+工具可用”和“流式+工具可用”。如果当前开启流式响应，应优先选择报告中流式工具链通过的模型；某些图片、实时或 Codex 账户专用模型即使出现在列表中，也可能不适用于当前 Chat Completions 工具调用链。

模型可以调用 search_knowledge。当配方、步骤、前置条件或版本证据不足时，会继续改写查询并补充检索；最终只展示 3–5 个本轮实际搜索到且由模型标注用途的正文内来源按钮，并在回答底部给出三个后续问题按钮。

默认搜索预算：

| 档位 | 最大搜索轮数 | 每轮结果 | 上下文上限 |
| --- | ---: | ---: | ---: |
| 快速 | 1 | 4 | 8,000 字符 |
| 标准 | 3 | 8 | 16,000 字符 |
| 深入 | 5 | 12 | 28,000 字符 |

## 快捷键与界面

| 操作 | 行为 |
| --- | --- |
| **K** | 打开/关闭助手浮窗 |
| **Esc** | 输入框优先失焦，否则关闭当前页面 |
| **Enter** | 发送单行问题 |
| **Shift+Enter** | 在输入框中换行 |
| **F8** | 保留原版电影视角 |
| **F9** | 强制重建本地知识库 |
| 标题栏拖动 | 移动浮窗 |
| 四边/四角拖动 | 缩放浮窗 |

浮窗特性：

- 默认尺寸 **320×400**，最小尺寸 **160×110**；
- 最大尺寸 **720×720**，宽高分别不超过视口的 **85%**；
- 历史和设置在同一个助手窗口内打开，不创建全屏二级 Screen；
- 设置字段、历史列表和按钮随父窗口约束并使用 scissor 裁剪；
- 游戏背景保持清晰，只绘制蓝光半透明面板；
- 主题色、透明度和发光效果保存在 `runtime/assistant-glass.json`；
- 高对比度或减少透明度时回退到不透明表面；
- 折叠输入只保留右下角的小型入口，展开后使用单行输入。

## 手册来源：框架与内容模组

Patchouli、GuideME 和 Modonomicon 主要是手册框架或前置库，框架 JAR 本身可能没有任何正文。搜索覆盖率取决于真正提供手册资源的内容模组 JAR。

| 格式 | 扫描内容 | 来源跳转 |
| --- | --- | --- |
| Patchouli | 书籍、分类、条目、页面和常见页面节点 | 书籍/条目 |
| GuideME | Markdown 页面、语言目录和页面索引 | 书籍/页面 |
| Modonomicon | 书籍、分类、条目、页面和未知节点 | 书籍/条目/页面 |

扫描器会保留：

- 内容模组 namespace；
- sourceType、sourcePath 和版本；
- 文档 ID、标题路径和页级锚点；
- 中文、英文和 neutral 回退信息。

测试时应同时安装手册框架和实际内容模组。只安装三个框架库可以验证加载兼容性，但正文数量通常不会增加。

## 本地知识库

实际文件布局：运行时数据和整合包事实源分成两个目录。发布整合包时只保留后者：

~~~text
config/modpedia/
├── runtime/                         # 玩家运行时目录，发布整合包前删除
│   ├── ai.json
│   ├── installation-id              # 无系统 UUID 时的安装级回退标识
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/
│       ├── knowledge.db*            # SQLite 派生搜索库
│       ├── generated/                # 自动扫描手册生成的 Markdown
│       ├── cache/                    # 构建报告和扫描缓存
│       ├── manifest.json
│       ├── keyword-index.json
│       └── state.json                # JAR 与资源指纹
└── knowledge/                       # 整合包作者随包保留的事实源
    ├── custom/                      # 人工维护的 Markdown
    ├── sources/                     # 可扩展 Wiki 来源集合
    │   └── <source-id>/source.json + documents/**/*.md
    ├── source-overrides.json
    └── search-synonyms.json         # 可选搜索同义词
~~~

`knowledge.db` 使用 Schema v7。模组手册、Wiki、FTBQ 静态任务定义和物品目录共用这个文件，但通过
`content_kind`、`source_type`、`origin_type` 和 `collection_id` 分开检索；早期测试版发现
旧 Schema 或缺少 `item_catalog` 时会在旁路数据库中全量重建，成功校验后再原子替换，失败时恢复上一份数据库；原始文件不会删除。

FTS5 使用 `content='segments'` 的 external-content 结构：完整 Markdown 仍从
`documents`/`segments` 读取，检索索引不再保存 `segments_fts_content` 正文副本。全量或大批量
导入后执行 FTS5 optimize/merge，小规模增量更新只执行 `PRAGMA optimize`；查询按 `rank` 排序，
避免额外的排序临时表。

`item_catalog` 与手册 FTS 分表保存：`item_id`、当前语言、显示名称、完整 Tooltip Markdown、
来源模组和 SHA-256 指纹。物品目录只保留当前游戏语言；切换语言后重新扫描并替换对应目录。
物品上下文不会伪装成手册来源，但可以直接作为 AI 的名称和 Tooltip 事实。

通用 Wiki 来源放在 `sources/<source-id>/`，最少包含 `source.json` 和
`documents/**/*.md`。`source.json` 用来声明来源集合、语言、版本和优先级，未来可以增加
整合包作者指南或其他 Wiki，而不改变核心表结构。

### 发布整合包时的 `config` 清理

ModPedia 的 `config/modpedia/` 同时包含玩家运行时状态和整合包作者维护的知识源。发布整合包时，
不要把本地玩家数据、派生索引或 API 配置一起打包。

发布前删除整个 `config/modpedia/runtime/`，其中包括以下运行时文件和派生文件：

~~~text
config/modpedia/runtime/ai.json
config/modpedia/runtime/conversations/
config/modpedia/runtime/diagnostics/
config/modpedia/runtime/worker/
config/modpedia/runtime/assistant-window.json
config/modpedia/runtime/assistant-glass.json
config/modpedia/runtime/knowledge/knowledge.db*
config/modpedia/runtime/knowledge/generated/
config/modpedia/runtime/knowledge/cache/
config/modpedia/runtime/knowledge/manifest.json
config/modpedia/runtime/knowledge/keyword-index.json
config/modpedia/runtime/knowledge/state.json
~~~

这些内容会在玩家首次启动或按 `F9` 重建时重新生成。`knowledge.db-wal`、`knowledge.db-shm` 和临时
数据库文件也属于派生文件，不应进入整合包。

整合包作者需要随包保留的知识源如下：

~~~text
config/modpedia/knowledge/custom/**/*.md
config/modpedia/knowledge/sources/<source-id>/source.json
config/modpedia/knowledge/sources/<source-id>/documents/**/*.md
config/modpedia/knowledge/sources/<source-id>/media.json
config/modpedia/knowledge/source-overrides.json       # 使用 APP/Modonomicon 书籍分类覆盖时保留
config/modpedia/knowledge/search-synonyms.json        # 自定义搜索同义词时可选保留
~~~

- `source.json` 描述一个 Wiki 来源的 ID、集合、语言、版本、优先级和 Markdown 根目录。
- `documents/**/*.md` 是实际 Wiki 正文，启动时导入 `knowledge.db`；原文件始终保留。
- `source-overrides.json` 位于 `knowledge/` 根目录，用于把 JAR 内的 APP/Modonomicon 书籍归类为
  `wiki`，或覆盖其来源 ID、集合 ID和优先级；普通 `sources/<source-id>/` Wiki 不需要它。
- `media.json` 当前版本没有被导入器读取，不是必需文件。若 Wiki 目录包含图片或其他原始媒体，
  作者可以保留这些文件供后续媒体适配使用，但它们不会进入当前 SQLite 文本检索。

最小的整合包作者 Wiki 结构为：

~~~text
config/modpedia/knowledge/
├── sources/example-pack/
│   ├── source.json
│   └── documents/**/*.md
└── custom/**/*.md
~~~

### 自定义文档

将人工维护的文件放入 custom/。使用稳定 id，建议声明语言：

~~~markdown
---
id: mypack:automation
language: zh_cn
title: 自动化说明
keywords: [自动化, automation]
source_type: manual_annotation
---

# 自动化说明

这里保留完整 Markdown，供搜索和 AI 读取。
~~~

每次启动会执行：

~~~text
扫描 custom/*.md
  → 读取 id、language 和 SHA-256 指纹
  → 只导入新增/修改文件
  → 删除已移除文件对应记录
  → 事务提交 SQLite
  → RetrievalService.reload()
~~~

原始 Markdown 始终保留，SQLite 只是可重建的派生搜索库。自定义文档优先于自动扫描文档；Front Matter 错误或事务失败时保留上一份有效记录。

## 开发

### 项目结构

~~~text
src/main/java/io/ctyx/modpedia/
├── ai/           # AI 客户端、工具调用、上下文和会话
├── knowledge/    # 手册扫描、转换、增量构建和自定义导入
├── search/       # SQLite、FTS 和规则检索
├── task/         # 任务快照、进度、依赖、奖励和查询
└── client/       # NeoForge 客户端 UI、来源预览和手册跳转

docs/
├── ARCHITECTURE.md
├── AI_MEMORY_STORAGE_RESEARCH.md
├── DEVELOPMENT.md
├── DEVELOPMENT_LOG.md
├── KNOWLEDGE_BASE.md
└── ROADMAP.md
~~~

模型正文中的可选物品和来源协议为：

~~~text
[[item:namespace:path|显示名称]]
[[tag:namespace:path|显示名称]]
[[source:document_id|来源说明]]
~~~

ID 保存在搜索结果和会话轨迹中，普通显示区域渲染为本地化名称；未注册的 ID 保留原文。来源标注按钮会嵌入回答中对应引用所在行，点击后优先跳转原手册页面，目标暂不可用时打开来源预览。未安装 JEI 或目标不存在时，
物品仍作为普通文本显示，不影响回答。

### 本地运行

运行客户端前，将测试用内容模组放入 run/mods/：

~~~bash
./gradlew runClient
~~~

无图形环境时，客户端窗口回归需要转移到实际桌面环境；纯 Java 测试和构建仍可执行。

### 验证命令

~~~bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
~~~

知识库规模和双语搜索基准：

~~~bash
./gradlew knowledgeBenchmark
~~~

报告输出到：

~~~text
build/reports/modpedia/knowledge-benchmark.json
build/reports/modpedia/knowledge-benchmark.md
~~~

基准会从当前 `run/mods/` 和 Downloads 语料重新构建临时 v7 数据库，不依赖旧的运行库；同时
记录中文/英文、ID、多词和无结果查询的冷/热 p50/p95/p99、SQLite/FTS/dbstat 大小、查询计划，
并比较 contentful 与 external-content 以及 optimize 前后结果。

详细的 Mod 开发清单、手动回归和发布流程见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。
本轮合并前维护、测试记录和交付检查见 [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md)。

## 已知限制

- 只扫描整合包中已安装 JAR 的本地手册资源，不联网下载手册。
- 不同版本或第三方改版可能改变资源路径，影响扫描覆盖率。
- 手册框架没有正文时只作为依赖型 JAR 统计；正文覆盖率取决于内容模组。
- 具体来源跳转依赖目标手册模组的客户端公开入口；入口缺失时保留来源预览。
- AI 回答需要玩家自行配置兼容 API；仅搜索模式可以完全离线使用。
- FTB Quests 不会在每个 Tick 生成运行时快照；只有进入世界后实际询问任务时才读取当前进度。单机优先由 Worker 直接读取小型 SNBT 文件，多人或本地文件不可用时才读取 TeamData 的有界运行时索引，再从 SQLite 取静态任务定义。任务 Wiki 网络更新失败时使用内置副本。
- JEI 配方跳转和 Jade 目标识别依赖各自客户端运行时 API，缺少或版本不匹配时仅关闭对应按钮。
- 当前仅搜索使用规则检索，向量检索仍属于后续增强方向。
- 首次启动会在加载屏幕阶段完成 SQLite 派生库和物品目录；F9 重建期间知识库尚未完成时搜索结果可能暂时为空。

完整列表见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。

## 相关文档

- [Mod 开发清单](docs/DEVELOPMENT.md)
- [开发日志](docs/DEVELOPMENT_LOG.md)
- [架构设计](docs/ARCHITECTURE.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)
- [AI 持久化方案调研](docs/AI_MEMORY_STORAGE_RESEARCH.md)
- [后续开发路线](docs/ROADMAP.md)
- [更新日志](CHANGELOG.md)
- [安装说明](INSTALL.md)
- [已知限制](KNOWN_LIMITATIONS.md)
- [Release](https://github.com/ct-yx/modpedia/releases/tag/v1.0.0-fix)

## 作者与许可证

- 作者：ctyx
- Mod ID：modpedia
- 包名：io.ctyx.modpedia
- 许可证：当前元数据标记为 All Rights Reserved。
