# ModPedia 开发流程

> 这份文档同时是 ModPedia 的 Mod 开发清单。每完成一项就勾选对应复选框；
> `[~]` 表示代码已具备但仍需要真实游戏或整合包人工回归，`[ ]` 表示后续工作。

## 0. 当前版本快照

| 项目 | 当前值 |
| --- | --- |
| 发布版本 | `v0.2.0` |
| GitHub 发布状态 | 正式发布，M0 自动化门槛已完成 |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.244` |
| Java | `21` |
| Mod ID | `modpedia` |
| 包名 | `io.ctyx.modpedia` |
| 作者 | `ctyx` |
| 客户端 UI 依赖 | ModernUI `3.12.0.2`（客户端必需） |
| 默认快捷键 | `K` 助手、`F9` 重建；`F8` 保留原版电影视角 |

发布资产与校验文件位于：

```text
https://github.com/ct-yx/modpedia/releases/tag/v0.2.0
```

后续阶段、稳定版门槛和暂缓功能以[开发路线](ROADMAP.md)为准。

## 1. Mod 工程基础清单

- [x] `mod_id=modpedia`、显示名 `ModPedia · 模组百科`、作者 `ctyx` 全部一致。
- [x] 基础包名固定为 `io.ctyx.modpedia`，技术标识不随显示名调整。
- [x] Minecraft、NeoForge、Java 和 ModernUI 版本写入 `gradle.properties` 并锁定。
- [x] `neoforge.mods.toml` 声明 Minecraft、NeoForge 和客户端 ModernUI 依赖。
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
- [x] SQLite 保存完整 Markdown、段落索引、标题路径和 FTS 数据。
- [x] `custom/*.md` 按稳定 ID、语言和 SHA-256 指纹启动增量导入。
- [x] 新增、修改、删除自定义文档均在事务内同步 SQLite、段落和 FTS。
- [x] 自定义文档优先级高于自动手册，原始 Markdown 保持为事实源。
- [x] 支持中文双字词、英文大小写、ID、标题、关键词、路径和同义词匹配。
- [x] 搜索结果按完整段落返回，每篇文档保留一个最高分段落。
- [x] `reload()` 原子替换快照，并兼容旧版 Markdown/JSON 索引。
- [x] 双语 10× 基准记录 p50/p95/p99，搜索 p95 目标为 `≤50 ms`。
- [~] 在大型整合包中确认所有前置库只计入扫描覆盖统计，不干扰内容来源排序。
- [ ] 只有基准证明必要时才引入段落预索引或向量检索。

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
- [x] 旧版 `memoryMessagesJson` 首次读取时迁移到 `config/modpedia/conversations/memory.sqlite`，迁移失败保留旧数据。
- [x] `search_knowledge` 返回完整 Markdown 段落、来源、匹配分、`returned_count` 和 `has_more`。
- [x] 证据不足时支持改写查询、跨语言补搜和已返回文档排除。
- [x] `language=auto` 合并双语候选、实体锚点过滤通用词误命中，并归一化中文自然语言 `focus`。
- [x] 重试或上游中断后清理没有工具结果的持久化调用，避免后续请求复用损坏的工具消息链。
- [x] 快速、标准、深入和自定义搜索预算可配置。
- [x] 历史会话保存用户/助手消息、正文来源标注、三个后续问题和 SearchTrace，不复制知识正文。
- [x] API Key 仅用于认证，不写入日志和会话；设置页非空值优先，空白时回退到环境变量。
- [x] AI 设置保存使用原子替换并回读校验，失败时不会显示“已保存”。
- [x] `AiClient` 支持 `/models` 模型列表、模型 ID 去重排序、根地址自动补全 `/v1` 和 HTML/401 友好错误提示。
- [x] 设置页模型名称右侧提供“获取模型列表”，再次点击可循环切换已获取模型。
- [x] 设置页提供“批量测试模型”，一次性测试 `/models` 返回的全部模型，不要求玩家逐个模型手测；报告写入 `config/modpedia/diagnostics/`。
- [x] 兼容性探测分别覆盖普通请求、非流式工具续接、普通 SSE 和流式工具续接，并区分普通+工具可用与流式+工具可用。
- [x] 503、429、网络超时和孤立工具调用自动重试一次；明确的配置错误直接显示，不重复请求。
- [x] 设置页支持 `AI` / `SEARCH_ONLY`；仅搜索模式跳过 API 配置和网络请求。
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
# 批量测试当前 API 的全部模型（不会打印或写入 API Key）
./gradlew aiModelCompatibility \
  -PaiSettingsFile=run/config/modpedia/ai.json \
  -PaiReportDirectory=build/reports/modpedia \
  -PaiProbeParallelism=2
```

按改动范围补充：

```bash
./gradlew assistantSecondaryLayoutSelfTest
./gradlew manualSourceNavigatorSelfTest
./gradlew knowledgeDatabaseSelfTest
./gradlew runClient       # 需要可用图形环境
./gradlew runServer       # Dedicated Server 隔离回归
```

每次检查都要确认：

- [ ] 日志中没有 API Key、完整请求头或模型密钥。
- [ ] `run/config/modpedia/knowledge/`、SQLite 数据库和本地 JAR 没有被误提交。
- [ ] `git diff --check` 无空白错误。
- [ ] 改动涉及客户端时补做实际游戏截图；涉及服务端时补做 Dedicated Server 启动。

## 7. v0.2.0 发布清单

- [x] 版本号、Mod ID、显示名、作者和 NeoForge 元数据一致。
- [x] `./gradlew test`、`./gradlew build`、`git diff --check` 通过。
- [x] 构建产物 `build/libs/modpedia-0.2.0.jar` 已生成。
- [x] `SHA256SUMS` 与发布 JAR 校验一致。
- [x] GitHub `main` 已推送，标签 `v0.2.0` 已推送。
- [x] GitHub 发布资产包含 JAR、校验、更新日志、安装说明和已知限制。
- [~] 在真实图形客户端完成小窗口 UI、三种手册跳转和完整整合包回归。
- [~] GUI Scale 4、Dedicated Server、三种手册真实跳转和大型整合包仍需目标实例人工回归。

## 8. 后续开发入口

后续开发不在本清单中重复维护，统一参见[开发路线](ROADMAP.md)。当前顺序为：

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
- [ ] `K` 打开/关闭助手；关闭时游戏画面完全恢复，世界继续运行。
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
- [x] 启动 Dedicated Server，确认 ModPedia 不解析 `AssistantScreen` 和第三方客户端反射类；ModernUI 作为客户端可选依赖在服务端不加载其客户端入口。

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
