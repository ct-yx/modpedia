# ModPedia 开发流程

> 这份文档同时是 ModPedia 的 Mod 开发清单。每完成一项就勾选对应复选框；
> `[~]` 表示代码已具备但仍需要真实游戏或整合包人工回归，`[ ]` 表示后续工作。

本次发布前的具体变更、命令输出和测试 JAR 记录见[开发日志](DEVELOPMENT_LOG.md)。

## 0. 当前版本快照

| 项目 | 当前值 |
| --- | --- |
| 发布版本 | `v1.2.0-fix` |
| GitHub 发布状态 | 正式发布，自动化门槛已完成 |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.244` |
| Java | `21` |
| Mod ID | `modpedia` |
| 包名 | `io.ctyx.modpedia` |
| 作者 | `ctyx` |
| 客户端 UI 依赖 | 无外部 UI 依赖；基于 NeoForge 原生 GUI API 自绘 |
| 默认快捷键 | `K` 助手、`F9` 重建；`F8` 保留原版电影视角 |
| 当前发布分支 | `main` |

发布资产与校验文件位于：

```text
https://github.com/ct-yx/modpedia/releases/tag/v1.2.0-fix
```

后续阶段、稳定版门槛和暂缓功能以[开发路线](ROADMAP.md)为准。Worker 独立 JVM 的基线、握手字段和迁移矩阵见[Worker 基线与兼容层](WORKER_BASELINE.md)。

运行目录、SQLite 数据库、测试日志和测试 JAR 均属于本地验证产物，不进入提交；交付记录统一写入
`docs/DEVELOPMENT_LOG.md`。

## 1. Mod 工程基础清单

- [x] `mod_id=modpedia`、显示名 `ModPedia · 模组百科`、作者 `ctyx` 全部一致。
- [x] 基础包名固定为 `io.ctyx.modpedia`，技术标识不随显示名调整。
- [x] Minecraft、NeoForge 和 Java 版本写入 `gradle.properties` 并锁定。
- [x] `neoforge.mods.toml` 只声明 Minecraft、NeoForge 等实际必需依赖；客户端 UI 使用原生 GUI API。
- [x] 客户端入口与服务端入口隔离；Dedicated Server 不解析 `client/` UI 类。
- [x] `./gradlew build` 能生成独立的 Mod JAR。
- [ ] 每次升级 Minecraft/NeoForge 后重新核对对应版本官方 API 和映射。

## 2. 手册适配清单

- [x] Patchouli 书籍、分类、条目和页面扫描。
- [x] GuideME 页面扫描、语言目录回退和来源跳转候选修复。
- [x] Modonomicon/APP 书籍 JSON、分类、条目和页面展开。
- [x] 框架 JAR 与内容模组 JAR 分离统计；框架本身没有正文时标记为依赖型 JAR。
- [x] `zh_cn → en_us → neutral` 语言回退和多语言去重。
- [x] 保留 `sourceType`、`sourcePath`、页面锚点和内容模组 namespace。
- [x] 书籍框架缺失或公开跳转 API 不存在时保留来源预览。
- [~] 用大型真实整合包复核更多自定义页面节点和第三方改版路径。
- [ ] 增加更多页面类型的专用渲染适配。

## 3. 知识库与检索清单

- [x] 自动手册转换为完整 Markdown，并保留标题、列表、代码块和未知节点。
- [x] SQLite Schema v7 保存完整 Markdown、段落索引、标题路径、FTS 数据、静态 FTBQ 任务定义和独立 `item_catalog`；旧派生结构通过 staged 数据库成功校验后替换，失败恢复旧库。
- [x] `custom/*.md` 按稳定 ID、语言和 SHA-256 指纹启动增量导入。
- [x] 新增、修改、删除自定义文档均在事务内同步 SQLite、段落和 FTS。
- [x] 自定义文档优先级高于自动手册，原始 Markdown 保持为事实源。
- [x] 支持中文双字词、英文大小写、ID、标题、关键词、路径和同义词匹配。
- [x] 搜索结果按完整段落返回，每篇文档保留一个最高分段落。
- [x] `reload()` 原子替换快照，并兼容旧版 Markdown/JSON 索引。
- [x] 双语 10× 基准记录冷/热 p50/p95/p99、SQLite/FTS/dbstat 大小和查询计划，搜索 p95 目标为 `≤50 ms`。
- [x] FTS5 使用 external-content，正文从 `segments` 事实表读取；Schema/索引创建后执行 `PRAGMA optimize`，全量/大批量变更执行 FTS5 optimize/merge，小增量跳过完整合并。
- [x] FTS 查询按 `rank` 排序，避免 `bm25(...)` 的排序临时表；性能自测覆盖短语、删除、增量更新和事务回滚。
- [x] 将 `config/modpedia/` 分为 `runtime/` 与 `knowledge/`：会话、Worker、生成 Markdown、索引和 SQLite 全部位于 `runtime/`；custom/Wiki/source-overrides 等整合包事实源位于 `knowledge/`；跨实例共享的 AI 配置位于用户目录 `~/.modpedia/ai.json`。
- [x] 启动时迁移早期散落路径，且不搬移或删除 `knowledge/custom/`、`knowledge/sources/`、`media.json` 和来源覆盖文件。
- [x] `modPediaPathsSelfTest` 覆盖旧布局迁移、运行时数据库/生成文件分离、事实源原地保留和分离目录检索。
- [x] Worker 本地 FTBQ 文件读取自测默认验证正确性并输出 p50/p95/p99；墙钟 p95 门禁只在明确执行
  `./gradlew workerTaskRuntimeFileSelfTest -PstrictPerformance=true` 时启用，避免 CI 机器负载造成随机失败。
- [~] Worker 使用 `worker-baseline-1`、API level 和能力集合握手；纯 Java DTO 已移出 `client` 包，
  具体基线、禁止依赖和迁移矩阵见 [docs/WORKER_BASELINE.md](WORKER_BASELINE.md)；可执行证据矩阵见
[WORKER_VERIFICATION_MATRIX.md](WORKER_VERIFICATION_MATRIX.md)。
- [x] Worker 共享 lib 使用 `manifest.sha256`、SHA-256 指纹、跨进程文件锁和原子替换；客户端同步后，
  Worker JVM 在 IPC 前再次校验，`workerLibraryVerifierSelfTest` 覆盖首次安装、无变化复用、损坏修复、
  依赖升级和清单重建。
- [~] 在大型整合包中确认所有前置库只计入扫描覆盖统计，不干扰内容来源排序。
- [ ] 只有基准证明必要时才引入段落预索引或向量检索。

### 3.1 统一知识库与任务联动

- [x] 统一使用 `config/modpedia/runtime/knowledge/knowledge.db`，Schema v7 同时包含文本知识、静态 `task_*` 任务定义和物品目录；玩家实时进度只在查询内存中覆盖。`config/modpedia/knowledge/` 只保存 custom/Wiki 事实源。
- [x] 用 `content_kind` 区分 `mod_manual`、`wiki` 和 `task_runtime`，用 `source_type` 区分 JAR、Markdown、Wiki 和任务快照格式。
- [x] 增加 `knowledge_sources` 来源注册表和 `sources/<source-id>/source.json` 扩展目录。
- [x] APP/Modonomicon 书籍支持 `knowledge.content_kind`、`source-overrides.json` 和 `source.json` 分类覆盖；整合包作者指南可以归入 Wiki。
- [x] 旧数据库结构不迁移：发现旧 Schema 或缺少任务表时从原始来源写入旁路库，成功后原子替换，失败恢复旧库。
- [x] 文本重建和任务同步使用不同表及事务；手册重建不会删除任务快照，任务同步不会修改 FTS。
- [x] 内置 FTB Quests Wiki 作为 `wiki_markdown` 来源，后台更新失败时保留内置或本地缓存。
- [x] FTB Quests、JEI、Jade 仅作为可选联动；客户端 UI 不依赖外部 UI Mod。FTBQ 任务问题先取得运行时进度：单机由 Worker 直接读取小型存档 SNBT，多人或文件不可用时回退 TeamData，再查询静态任务定义，实时状态不落 SQLite。
- [x] 移除 FTB Quests 客户端 Tick 全量轮询；`search_tasks` 才按问题触发运行时读取，TeamData 读取有界，同一 AI 请求只读取一次，结果只在 `TaskRuntimeSnapshot` 内存中复用。
- [x] 任务运行时快照增加时间线：读取 FTBQ started/completed 的原始时间戳，记录 TeamData 进度变化检测时间，并通过 `search_tasks.timeline` 返回具体条目和静态标题；时间线不写入数据库。
- [x] 不导入 JEI 配方；仅解析物品 ID、渲染本地化名称并尝试 Shift+左键配方跳转。
- [x] 增加 `query_item_recipes` 分阶段联动：工作台/熔炉直接查询，其它方式 `OTHER → DETAIL`，熔炉返回处理时间，机器等级去重，结果通过 Worker IPC 返回。
- [~] 用真实 FTB Quests、JEI、Jade 客户端版本回归任务快照、配方界面和视线目标识别。

### 3.2 物品与来源渲染协议

- [x] 支持 `[[item:namespace:path|显示名称]]` 和 `[[tag:namespace:path|显示名称]]`。
- [x] 普通显示区域渲染名称，支持协议令牌和模型直接输出的已注册 `namespace:path`；按住 Ctrl 显示稳定 ID，未知 ID 保留原文。
- [x] 客户端注册表完成后按当前语言导入全部物品 ID、显示名称和完整 Tooltip Markdown；物品目录与手册 FTS 分表保存。
- [x] 物品目录写入使用 Worker 单事务批量 UPSERT；大批量记录通过独立 I/O 线程的原子 JSONL 载荷传递，IPC 不发送超大 JSON 数组；首次、语言切换和增量更新都不复制或替换整个数据库，相同指纹启动只做指纹比较。
- [x] Jade 确认物品后，AI 与仅搜索模式先读取 `item_catalog`，再继续手册搜索，并把物品简介作为独立事实上下文。
- [~] 在大型整合包中确认物品目录批量导入耗时、语言切换和 Tooltip 内容质量。
- [x] 来源标注只来自本轮真实搜索轨迹，并嵌入正文对应位置；点击优先跳转原手册，失败时回退来源预览，不自动堆叠所有候选来源。
- [~] 在真实整合包中确认模组语言表、JEI 运行时和物品点击命中区域的一致性。

## 4. 客户端 UI 清单

- [x] 助手关闭时完全隐藏，不常驻 HUD；`AssistantScreen` 为唯一 Screen。
- [x] 标题栏拖动、四边/四角缩放、位置尺寸持久化和视口边界约束。
- [x] 最小 `160×110`、最大 `720×720`、视口占比 `85%` 和安全边距约束。
- [x] 历史与设置作为同层 `SecondaryPanel` 绘制，不创建第二个 Minecraft Screen。
- [x] 二级页面背景、文字、控件和页脚通过 scissor 限制在父窗口内。
- [x] 设置页滚动时，标签和控件只有在完整可见时才绘制，避免半截文字和输入框重叠。
- [x] 设置和历史按钮统一使用助手自定义按钮材质。
- [x] 游戏背景保持清晰；只绘制蓝光半透明面板，支持透明度、主题色和高对比度回退。
- [x] 折叠输入只保留右下角紧凑入口，展开后使用单行输入。
- [~] 在 GUI Scale `4` 的最小窗口、普通窗口和最大窗口分别截图回归。
- [~] 在窗口拖动、缩放和游戏视口变化过程中人工检查鼠标命中区域。

## 5. AI、历史与仅搜索清单

- [x] 使用 LangChain4j 管理 Chat Memory、工具调用轮次、上下文窗口和流式响应。
- [x] 使用 LangChain4j Community SQL `SQLChatMemoryStore` 持久化上下文，SQLite 只保留本地方言和路径装配。
- [x] 旧版 `memoryMessagesJson` 首次读取时迁移到 `config/modpedia/runtime/conversations/memory.sqlite`，迁移失败保留旧数据。
- [x] `search_knowledge` 返回完整 Markdown 段落、来源、匹配分、`returned_count` 和 `has_more`。
- [x] 证据不足时支持改写查询、跨语言补搜和已返回文档排除。
- [x] `language=auto` 合并双语候选、实体锚点过滤通用词误命中，并归一化中文自然语言 `focus`。
- [x] 重试或上游中断后清理没有工具结果的持久化调用，避免后续请求复用损坏的工具消息链。
- [x] 快速、标准、深入和自定义搜索预算可配置。
- [x] 历史会话保存用户/助手消息、正文来源标注、三个后续问题和 SearchTrace，不复制知识正文。
- [x] API Key 仅用于认证，不写入日志和会话；`ai.json` 只保存系统标识派生的 AES-GCM 密文，进程首次读取后复用内存缓存；系统标识变化时清除密钥字段，空白时回退到环境变量。
- [x] AI 设置保存使用原子替换并回读校验，失败时不会显示“已保存”。
- [x] `AiClient` 支持按协议读取 `/models` 模型列表、模型 ID 去重排序、根地址自动补全 `/v1`/`/v1beta` 和 HTML/401 友好错误提示。
- [x] 设置页模型名称右侧提供“获取模型列表”，再次点击可循环切换已获取模型。
- [x] 设置页支持 Chat Completions、原生 Messages、Responses 和 Gemini `generateContent`；`api_format` 随设置和 Worker IPC 持久化，端点按协议自动归一化。
- [x] 新增协议适配器覆盖普通请求、工具调用续接、分段 SSE、认证头和 Gemini `models/<model>` 路由；本地 Mock 不访问外网。
- [x] 设置页提供“批量测试模型”，一次性测试 `/models` 返回的全部模型，不要求玩家逐个模型手测；报告写入 `config/modpedia/runtime/diagnostics/`。
- [x] 兼容性探测分别覆盖普通请求、非流式工具续接、普通 SSE 和流式工具续接，并区分普通+工具可用与流式+工具可用。
- [x] 503、429、网络超时和孤立工具调用自动重试一次；明确的配置错误直接显示，不重复请求。
- [x] 设置页支持 `AI` / `SEARCH_ONLY`；仅搜索模式跳过 API 配置和网络请求。
- [x] 增加本地 `calculate` 工具；复杂算术、比例、配方总量和取整使用 `BigDecimal` 确定性计算，不调用模型或执行脚本。
- [x] 平衡 AI 成本与证据完整性：首轮工具参数使用 1,536 tokens，GPT-5/o 使用 3,072；回答预算按搜索档位为 1,280/2,560/4,096。检索阶段静默；结果保留来源、内容类型、路径、匹配词和完整当前 Markdown。历史上下文保留最近两次工具回合，只有更早回合压缩正文首尾和重复字符串，不删除工具调用、来源 ID 或标题路径。GPT-5/o 系列使用 `max_completion_tokens`，旧模型继续使用 `max_tokens`。
- [x] 增加 `AiCostOptimizationSelfTest`，覆盖提示词长度、首轮/回答输出预算、历史工具证据分层保留和来源字段完整性；不调用真实模型。
- [x] Mock 会话与真实 AI 会话接口兼容，支持离线 UI/搜索测试。
- [~] 使用真实模型回归多问题补搜、流式输出、取消、超时和历史恢复。

## 6. 每次修改后的自动检查

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
# AI HTTP 地址、模型列表和错误提示夹具
./gradlew aiClientSelfTest
# 四种 API 格式的普通请求、工具调用、模型列表和 SSE 夹具
./gradlew aiProtocolSelfTest
# 批量测试当前 API 的全部模型（不会打印或写入 API Key）
./gradlew aiModelCompatibility \
  -PaiSettingsFile="$HOME/.modpedia/ai.json" \
  -PaiReportDirectory=build/reports/modpedia \
  -PaiProbeParallelism=2
```

按改动范围补充：

```bash
./gradlew assistantSecondaryLayoutSelfTest
./gradlew manualSourceNavigatorSelfTest
./gradlew knowledgeDatabaseSelfTest
./gradlew modPediaPathsSelfTest
./gradlew workerIpcSelfTest
./gradlew runClient       # 需要可用图形环境
./gradlew runServer       # Dedicated Server 隔离回归
```

每次检查都要确认：

- [ ] 日志中没有 API Key、完整请求头或模型密钥。
- [ ] `run/config/modpedia/runtime/`、SQLite 数据库和本地 JAR 没有被误提交；整合包事实源仅来自 `run/config/modpedia/knowledge/`。
- [ ] `git diff --check` 无空白错误。
- [ ] 改动涉及客户端时补做实际游戏截图；涉及服务端时补做 Dedicated Server 启动。

## 7. v1.2.0-fix 发布清单

- [x] `gradle.properties`、Mod 元数据、README、安装说明和发布页面统一为 `v1.2.0-fix`。
- [x] 对比 `v1.1.0` 整理四种 AI API 格式、模型列表/连接测试、分阶段 JEI 配方查询和本地 `calculate` 工具。
- [x] 修复物品目标冻结、显式插入、原生选项页 `K` 拦截和 Tooltip 异常导致的扫描日志膨胀。
- [x] 调整 Token 与历史证据压缩，保留当前检索事实、来源字段和标题路径。
- [x] `./gradlew test`、`./gradlew build`、`git diff --check` 通过。
- [x] 发布 JAR、`SHA256SUMS`、`CHANGELOG.md`、`INSTALL.md` 和 `KNOWN_LIMITATIONS.md` 由标签流水线生成。
- [x] `main` 使用 GitHub 登录账号 `ct-yx` 提交并推送，版本标签为 `v1.2.0-fix`。
- [~] 真实模型兼容性和不同大型整合包的持续回归按 `KNOWN_LIMITATIONS.md` 单独记录。

## 7.1 历史：v1.2.0 发布清单

- [x] `v1.2.0` 已作为历史正式版本保留。

## 7.2 历史：v1.1.0 发布清单

- [x] `gradle.properties`、Mod 元数据、README、安装说明和发布页面统一为 `v1.1.0`。
- [x] `./gradlew test`、`./gradlew build`、严格 Worker 性能自测和 `git diff --check` 通过。
- [x] 发布 JAR、`SHA256SUMS`、`CHANGELOG.md`、`INSTALL.md` 和 `KNOWN_LIMITATIONS.md` 由标签流水线生成。
- [x] `main` 使用 GitHub 登录账号 `ct-yx` 提交并推送，版本标签为 `v1.1.0`。
- [x] API Key 密文存储、旧配置迁移、系统标识变化清除和 Worker 启动缓存已通过自动化测试。
- [x] Mod 列表图标和 Mod 介绍已写入元数据并进入构建 JAR。
- [~] 图形客户端、第三方手册跳转、可选联动和 Dedicated Server 的持续回归仍按已知限制记录。

## 7.3 历史：v0.2.0 发布清单

- [x] 版本号、Mod ID、显示名、作者和 NeoForge 元数据一致。
- [x] `./gradlew test`、`./gradlew build`、`git diff --check` 通过。
- [x] 构建产物 `build/libs/modpedia-0.2.0.jar` 已生成。
- [x] `SHA256SUMS` 与发布 JAR 校验一致。
- [x] GitHub `main` 已推送，标签 `v0.2.0` 已推送。
- [x] GitHub 发布资产包含 JAR、校验、更新日志、安装说明和已知限制。
- [~] 在真实图形客户端完成小窗口 UI、三种手册跳转和完整整合包回归。
- [~] GUI Scale 4、Dedicated Server、三种手册真实跳转和大型整合包仍需目标实例人工回归。

## 8. 后续开发入口

后续开发不在本清单中重复维护，统一参见[开发路线](ROADMAP.md)。AI 上下文、数据库 v8 和外部百科的详细实施顺序见
[专题后续计划](NEXT_DEVELOPMENT_PLAN.md)。当前版本级顺序为：

```text
M0 Beta 稳定性收尾
→ M1 大型整合包知识库 / M2 客户端 UI 稳定 / M3 AI 可靠性
→ M4 发布与维护能力
→ M5 可选语义检索
```

本清单继续保留每次实现和发布时的验收复选框。

## 9. 分支、提交与评审规范

- 功能分支使用 `codex/<feature-name>`；发布提交可以直接合并到 `main`。
- 提交作者使用登录的 GitHub 账号 `ct-yx`，不要使用本地电脑用户名。
- 提交信息保持简短并说明实际变化：

```text
feat: add local guide scanner
fix: repair compact settings layout
docs: update mod development checklist
```

- 每次提交只包含当前功能相关文件；运行目录、JAR、SQLite 数据库、API 配置和会话记录不进入 Git。
- 客户端 UI 改动必须同时更新纯 Java 几何测试或手动回归步骤。
- 手册适配改动必须同时更新来源跳转测试、语言回退测试和大型数据基准说明。

## 10. 新增手册适配器清单

新增 Patchouli、GuideME、Modonomicon 或其它手册格式时，按以下顺序完成：

- [ ] 先确认 Minecraft/NeoForge 版本和实际资源目录，不凭框架名称猜正文位置。
- [ ] 在扫描器中只匹配该格式的专用目录，避免普通 JSON/Markdown 被误收录。
- [ ] 记录稳定 `documentId`、`sourceType`、`sourcePath`、内容模组 namespace 和版本。
- [ ] 实现 `zh_cn → en_us → neutral` 回退，并对多语言页面去重。
- [ ] 将书籍、分类、条目、页面和未知节点转换成完整 Markdown。
- [ ] 在 `ManualSourceNavigator` 中增加客户端反射适配；框架缺失时仍保留来源预览。
- [ ] 增加合成 JAR 夹具，覆盖标题、列表、代码块、配方、未知节点和页级跳转。
- [ ] 用真实内容模组 JAR 做只读回归；前置框架 JAR 单独统计，不与正文覆盖率混淆。
- [ ] 更新 `README.md`、`docs/ARCHITECTURE.md` 和 `docs/KNOWLEDGE_BASE.md`。

## 11. 手动回归清单

### UI 与窗口

- [ ] 在 GUI Scale `4` 下测试 `160×110`、普通尺寸和最大尺寸。
- [x] `K` 打开/关闭助手；Minecraft 原生选项及按键控制页面拦截 `K`，JEI、FTBQ、容器等其它界面仍可呼出，关闭时游戏画面完全恢复。
- [ ] 拖动标题栏后关闭并重新打开，确认位置保存。
- [ ] 拖动四边和四角，确认宽高始终满足 `160×110`、`720×720` 和 `85%` 视口限制。
- [ ] 缩放游戏窗口，确认浮窗和二级页面同步约束在可见范围内。
- [ ] 历史、设置只在原 `AssistantScreen` 的父窗口内绘制；底层文字和输入框不穿透。
- [ ] 设置页滚动到每个字段，确认标签、输入框、按钮和状态文字没有重叠。
- [ ] 折叠输入只显示紧凑入口，点击后展开单行输入；`Enter` 发送，`Esc` 按焦点优先级处理。
- [ ] 确认背景清晰、面板半透明蓝光可见；修改主题色/透明度后重新打开助手验证。
- [ ] 确认高对比度或减少透明度时回退为不透明面板。
- [ ] `F8` 保留原版电影视角，`F9` 继续触发知识库重建。

### 搜索与跳转

- [ ] 使用中文名称、英文名称、模组 ID、物品 ID 和模糊关键词分别搜索。
- [ ] 确认结果返回完整 Markdown 段落、标题路径、分数和正文来源标注按钮。
- [ ] 分别测试 Patchouli、GuideME、Modonomicon 三种来源；正文来源标注可预览并跳转。
- [ ] 只安装手册框架时确认加载正常；安装内容模组后确认正文数量增加。
- [ ] 删除或更新 JAR 后按 `F9` 重建，确认生成文档、SQLite 和来源记录同步变化。
- [ ] 新增、修改、删除 `config/modpedia/knowledge/custom/*.md` 后重启游戏，确认 ID/语言更新正确。

### AI、历史与服务端

- [ ] 仅搜索模式在空 API 配置下直接返回本地结果。
- [ ] AI 模式测试首次搜索不足时的补搜、跨语言查询、`has_more` 和重复查询抑制。
- [ ] 测试流式输出、取消、超时、错误重试和历史会话恢复。
- [ ] 检查日志和会话文件中没有 API Key。
- [x] 启动 Dedicated Server，确认 ModPedia 不解析 `AssistantScreen` 和第三方客户端反射类。

## 12. 发布流程

发布前执行：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
jar_file="$(find build/libs -maxdepth 1 -type f -name 'modpedia-*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | head -n 1)"
printf '%s  %s\n' "$(shasum -a 256 "$jar_file" | awk '{print $1}')" "$(basename "$jar_file")" > SHA256SUMS
```

发布资产至少包含：

```text
modpedia-<version>.jar
SHA256SUMS
CHANGELOG.md
INSTALL.md
KNOWN_LIMITATIONS.md
```

推送版本标签后，`.github/workflows/release.yml` 会在 GitHub Actions 中重新测试、构建、生成校验并创建发布。发布完成后从远端下载 JAR，执行：

```bash
shasum -a 256 -c SHA256SUMS
```

最后确认：

- [ ] 发布页为正式发布状态，版本号与 JAR 文件名一致。
- [ ] 发布资产可下载，SHA-256 校验通过。
- [ ] 安装说明与当前最小尺寸、依赖版本和快捷键一致。
- [ ] 已知限制明确说明手册覆盖率、AI API 和图形客户端回归范围。

### 12.1 CurseForge 自动发布

`.github/workflows/publish-curseforge.yml` 与版本标签发布流程分开：推送 `v*` 标签时自动执行，
需要重试时也可以通过 `workflow_dispatch` 输入已有标签。它会重新测试、构建并从当前版本的
`CHANGELOG.md` 只提取一个版本段落，然后上传 NeoForge 1.21.1 的 Mod JAR。

首次启用前，在仓库的 `Settings → Secrets and variables → Actions` 添加：

```text
Repository variable: MODPEDIA=<项目 ID>
Repository secret:   MODPEDIA=<发布 API Token>
```

工作流也兼容 `CURSEFORGE_PROJECT_ID` 和 `CURSEFORGE_TOKEN` 这组标准名称。Token 只通过 Secret 注入
`mc-publish` Action，不进入源码、JAR、更新日志或普通日志。发布前检查：

- [ ] `MODPEDIA` Repository variable 与目标项目匹配。
- [ ] `MODPEDIA` Repository secret 具有上传/发布权限且未写入任何文件。
- [ ] `CHANGELOG.md` 包含与标签完全一致的标题，例如 `## v1.2.0-fix`。
- [ ] Action 的 `loaders` 为 `neoforge`、`game-versions` 为 `1.21.1`。
- [ ] 首次发布后检查外部发布页的文件名、版本类型、更新日志和加载器信息。
