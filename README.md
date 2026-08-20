# ModPedia · 模组百科

ModPedia 是一个面向 Minecraft 整合包的本地模组知识助手：它读取已安装模组中的手册资源，转换为统一 Markdown，写入 SQLite 检索库，再以搜索结果或 AI 回答的方式呈现，并保留原手册来源跳转。

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ct-yx/modpedia?include_prereleases&label=release)](https://github.com/ct-yx/modpedia/releases)

English version: [README.en.md](README.en.md)

专项后续计划：[AI 上下文、数据库 v8 与外部百科](docs/NEXT_DEVELOPMENT_PLAN.md)

## 当前版本

| 项目 | 版本/状态 |
| --- | --- |
| Mod | **v1.2.0** |
| 发布状态 | GitHub 正式发布 |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.244**（兼容 **21.1.x**） |
| Java | **21** |
| Mod ID | **modpedia** |
| 客户端 UI 依赖 | 无外部 UI 依赖（基于 NeoForge 原生 GUI API 自绘） |
| 作者 | **ctyx** |

当前发布包的 JAR、校验文件、安装说明和已知限制位于 [GitHub Release](https://github.com/ct-yx/modpedia/releases/tag/v1.2.0)。`v1.1.0` 及更早版本保留为历史版本；`v1.2.0` 增加四种 AI API 格式、本地计算工具、分阶段 JEI 配方查询、物品目标冻结和 Tooltip 扫描日志熔断。

## 快速安装

### 必需环境

1. 安装 Minecraft **1.21.1**。
2. 安装 NeoForge **21.1.x**。
3. 安装 Java **21**。

### 安装步骤

1. 下载 **modpedia-1.2.0.jar**。
2. 将 ModPedia JAR 放入实例的 **mods/** 目录。
3. 启动游戏，进入单人世界或服务器。
4. 等待加载屏幕完成首次知识库和物品目录预填充；需要立即重建时按 **F9**。
5. 进入游戏后按 **K** 打开助手。

ModPedia 不捆绑 Patchouli、GuideME、Modonomicon 或内容模组。它们作为可选手册适配对象存在，实际正文来自整合包中安装的内容模组。

以下联动模组均为可选：

- **FTB Quests**：不再在客户端 Tick 中轮询或全量序列化任务。任务问题调用 `search_tasks` 时先取得当前玩家运行时进度；单机优先由 Worker 直接读取 `saves/<世界>/ftbquests/<team-uuid>.snbt`，多人或本地文件不可用时回退到游戏 JVM 的 TeamData，再由 Worker 查询静态任务数据库并在内存中覆盖结果。同一 AI 请求只读取一次，实时进度不写入数据库。运行时响应还会返回具体任务的 `timeline`：started/completed 使用 FTBQ 时间戳，进度变化使用检测时间，模型可以列出新增条目而不是只比较数量。任务 Wiki 作为独立的 `content_kind=wiki` 来源导入，不与模组手册混在检索范围内。
- **JEI**：不导入配方数据库。回答正文中的已注册物品 ID（包括模型直接输出的 `namespace:path`）按需解析为本地化名称，按住 Ctrl 显示 ID，按住 Shift 点击物品名称时尝试打开 JEI 配方界面。模型还可以调用 `query_item_recipes`：工作台（`WORKBENCH`）和熔炉（`FURNACE`）直接读取对应配方，熔炉附带处理时间；其它处理方式先用 `OTHER` 获取 `method_id`，再用 `DETAIL` 查询具体输入、输出、附加信息和已合并等级的机器列表。
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

- API 格式：可选 `Chat Completions`、`原生 Messages`、`Responses` 和 `Gemini generateContent`；选择后客户端会使用对应的请求体、认证头、工具调用和 SSE 格式，并把 `api_format` 写入配置；
- API 地址：填写所选协议的 API 根地址。Chat Completions、原生 Messages 和 Responses 通常使用 `/v1`，Gemini 使用 `/v1beta`；如果只填写域名，客户端会按格式自动补全版本路径；
- 模型名称：可以点击右侧“获取模型列表”，成功后再次点击按钮在返回的模型之间切换；
- API Key：优先使用设置页输入；设置页留空时才使用环境变量 MODPEDIA_API_KEY。
- `~/.modpedia/ai.json` 是跨游戏实例共享的用户级配置，不随整合包分发。它不保存 API Key 明文，而是使用当前系统标识派生的 AES-GCM 密钥保存密文；游戏进程首次读取时解密并缓存，系统标识变化后会清除密钥密文。系统标识读取失败时使用同目录的 `installation-id` 回退标识；在支持 POSIX 权限的系统上目录为 `0700`、文件为 `0600`。
- 不需要逐个模型手测：点击设置底部“批量测试模型”，会自动探测 `/models` 返回的全部模型，分别验证普通请求、工具调用续接、SSE 和流式工具续接；脱敏报告写入 `config/modpedia/runtime/diagnostics/`。

如果连接测试提示“API 地址返回了网页内容”，说明地址指向了网页或服务根页面，而不是 API 端点；请检查当前格式对应的版本路径。模型列表和连接测试都不会把 API Key 写入日志。部分原生 Messages 服务不提供 `/models`，此时直接填写模型名称即可。

“批量测试模型”当前针对 Chat Completions 的 `/models` 接口，分为“普通+工具可用”和“流式+工具可用”。其他三种协议仍可通过“测试连接”和真实对话链路验证；某些服务或模型不提供模型列表时，直接填写模型名称即可。

模型可以调用 `search_knowledge`、`search_wiki`、`search_tasks`、`query_item_recipes` 和本地 `calculate` 工具。当配方、步骤、前置条件或版本证据不足时，会继续改写查询并补充检索；涉及 JEI 配方时按工作台、熔炉或其它处理方式分阶段读取，不把配方伪装成手册来源；涉及多步配方总量、比例、取整或其他复杂数字推导时，模型把表达式交给本地 `calculate`，不依赖 LLM 心算。检索阶段只发送工具调用，不输出过程性长文本；首轮工具参数和最终回答按搜索档位限额。上下文保留最近两次工具回合的完整证据，更早回合只压缩重复正文的首尾片段，同时保留来源 ID、标题路径和来源路径，避免为了节约 Token 丢失检索事实。最终只展示 3–5 个本轮实际搜索到且由模型标注用途的正文内来源按钮，并在回答底部给出三个后续问题按钮。

默认搜索预算：

| 档位 | 最大搜索轮数 | 每轮结果 | 上下文上限 |
| --- | ---: | ---: | ---: |
| 快速 | 1 | 4 | 8,000 字符 |
| 标准 | 3 | 8 | 16,000 字符 |
| 深入 | 5 | 12 | 28,000 字符 |

AI 请求会把首轮工具调用限制为 1,536 tokens（GPT-5/o 为 3,072），最终回答按搜索强度使用 1,280/2,560/4,096 tokens，检索阶段不发送过程性文字。GPT-5/o 系列使用兼容接口要求的 `max_completion_tokens`，其他 Chat Completions 模型继续使用 `max_tokens`，避免发送两个互斥字段。

## 快捷键与界面

| 操作 | 行为 |
| --- | --- |
| **K** | 打开/关闭助手浮窗；Minecraft 原生游戏设置页和按键绑定页不会呼出 |
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

~/.modpedia/
├── ai.json                           # 跨 Minecraft 实例共享的 AI 设置（密钥为密文）
└── installation-id                   # 无系统 UUID 时的用户级回退标识
~~~

`~/.modpedia/` 位于各游戏实例之外的当前操作系统用户目录，不属于整合包文件。不同 Minecraft
版本和不同整合包会读取同一份用户级 `ai.json`；旧版本位于 `config/modpedia/ai.json` 或
`config/modpedia/runtime/ai.json` 的配置会在启动时迁移到这里。移动位置可以避免把个人 AI 配置
带入整合包发布包；API Key 仍只在当前用户进程内解密到内存，日志和会话不会记录明文。

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

发布前删除整个 `config/modpedia/runtime/`，其中包括以下运行时文件和派生文件；用户级
`~/.modpedia/` 不属于整合包发布目录，也不要复制到发布包：

~~~text
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

### CurseForge 自动发布

仓库提供 `.github/workflows/publish-curseforge.yml`：推送 `v*` 版本标签后，会重新执行测试和构建，
从当前版本的 `CHANGELOG.md` 提取单个版本段落，并上传 NeoForge 1.21.1 JAR。当前工作流优先读取
已经配置的 `MODPEDIA` 变量和 Secret，也兼容标准名称；配置位置为仓库的
`Settings → Secrets and variables → Actions`：

- Repository variable：`MODPEDIA`，填写项目 ID；
- Repository secret：`MODPEDIA`，填写发布 API Token。

如果使用标准命名，也可以配置 `CURSEFORGE_PROJECT_ID` 和 `CURSEFORGE_TOKEN`。

Token 只从 GitHub Actions Secret 读取，不写入仓库文件、构建产物或日志。若发布过程需要重试，
在 Actions 中运行 `Publish Mod Release`，输入已有的版本标签，例如 `v1.2.0`；不会重新创建 GitHub Release。

## 已知限制

- 只扫描整合包中已安装 JAR 的本地手册资源，不联网下载手册。
- 不同版本或第三方改版可能改变资源路径，影响扫描覆盖率。
- 手册框架没有正文时只作为依赖型 JAR 统计；正文覆盖率取决于内容模组。
- 具体来源跳转依赖目标手册模组的客户端公开入口；入口缺失时保留来源预览。
- AI 回答需要玩家自行配置兼容 API；仅搜索模式可以完全离线使用。
- FTB Quests 不会在每个 Tick 生成运行时快照；只有进入世界后实际询问任务时才读取当前进度。单机优先由 Worker 直接读取小型 SNBT 文件，多人或本地文件不可用时才读取 TeamData 的有界运行时索引，再从 SQLite 取静态任务定义。任务 Wiki 网络更新失败时使用内置副本。
- JEI 配方查询/跳转和 Jade 目标识别依赖各自客户端运行时 API，缺少或版本不匹配时仅关闭对应联动；配方不会写入 `knowledge.db`。
- 当前仅搜索使用规则检索，向量检索仍属于后续增强方向。
- 首次启动会在加载屏幕阶段完成 SQLite 派生库和物品目录；F9 重建期间知识库尚未完成时搜索结果可能暂时为空。

完整列表见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。

## 相关文档

- [Mod 开发清单](docs/DEVELOPMENT.md)
- [开发日志](docs/DEVELOPMENT_LOG.md)
- [架构设计](docs/ARCHITECTURE.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)
- [后续开发路线](docs/ROADMAP.md)
- [更新日志](CHANGELOG.md)
- [安装说明](INSTALL.md)
- [已知限制](KNOWN_LIMITATIONS.md)
- [Release](https://github.com/ct-yx/modpedia/releases/tag/v1.2.0)

## 作者与许可证

- 作者：ctyx
- Mod ID：modpedia
- 包名：io.ctyx.modpedia
- 许可证：[Apache License 2.0](LICENSE)。项目来源与修改版标识要求见 [NOTICE](NOTICE)。

### 再发布与修改

- 未修改的 ModPedia JAR 可以直接放入整合包并随整合包分发；整合包作者不需要声明自己是 ModPedia 的作者或修改者，但应保留 `LICENSE` 和 `NOTICE`。
- 修改源码或 JAR 后发布时，必须保留原作者 `ctyx` 和 ModPedia 的来源声明，在修改文件或发布说明中显著说明改动，并标注这是第三方修改版、分支或衍生版本。
- 修改版不得使用 `ctyx`、`ModPedia`、`ModPedia · 模组百科` 的名称、图标或原作者身份暗示官方维护、发布、背书或支持；应使用自己的维护者和版本标识。
- 以上项目标识说明与 Apache License 2.0 配合使用，代码授权范围以 [LICENSE](LICENSE) 为准。
