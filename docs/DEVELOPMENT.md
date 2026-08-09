# ModPedia 开发流程

## 1. 分支与提交

推荐分支命名：

```text
codex/<feature-name>
```

提交信息保持简短并说明实际变化，例如：

```text
feat: add local guide scanner
docs: describe knowledge cache
fix: preserve custom knowledge files
```

## 2. 开发顺序

### 阶段一：工程改名

- 使用 `modpedia` 作为 Mod ID。
- 使用 `io.ctyx.modpedia` 作为基础包名。
- 移除模板示例内容。
- 保留可构建的最小 Mod。

### 阶段二：知识库扫描（已完成基础版本）

- [x] 实现 JSON 手册扫描器。
- [x] 实现 Markdown 手册扫描器。
- [x] 适配 APP 书籍 JSON，按内容模组 namespace 归属来源并展开多条目文档。
- [x] 解析 `zh_cn`/`en_us` 语言 key。
- [x] 输出统一文档格式。
- [x] 生成 manifest 和关键词索引。
- [x] 合并 `custom/` 手工知识。
- [ ] 接入更多页面类型的专用渲染。

### 阶段三：增量更新（已完成）

- [x] 保存资源指纹，并兼容阶段二的旧版 `state.json`。
- [x] 只重新转换新增或指纹变化的来源。
- [x] 清理已经移除的来源文件。
- [x] 生成更新、复用、删除数量和警告报告。
- [x] 提供 `F9` 手动完整重建入口。

实现约束：每次构建都会重建 `manifest.json`、`keyword-index.json` 和 `state.json`；
`cache/build-report.json` 保存本次构建报告，`state.json` 使用 schema version 2。

### #4：规则搜索后端（已完成）

- [x] 读取 `manifest.json`、`keyword-index.json` 和生成/自定义 Markdown。
- [x] 实现中文双字词、ID、标题、关键词、分类和路径匹配。
- [x] 按完整 Markdown 段落返回结果，并保留标题路径和来源元数据。
- [x] 支持结果评分、组合命中、同文档去重、稳定排序和 `1–20` 条限制。
- [x] 支持 `reload()`、索引更新时间自动刷新和可选同义词配置。
- [x] 使用纯 Java 搜索回归测试覆盖索引状态、段落边界、代码块和同义词。

### #5：自定义 Markdown 自动导入 SQLite（已完成）

- [x] 启动扫描 `config/modpedia/knowledge/custom/*.md`，读取稳定 `id`、语言和 SHA-256 指纹。
- [x] 新增/修改文档按 `(id, language)` 增量替换，未变化文档复用已解析的 SQLite 记录。
- [x] 删除文件同步删除文档、段落和 FTS 记录；自定义优先级高于自动手册。
- [x] 保存完整 Markdown，原始 `.md` 继续作为事实源；数据库只作为派生搜索库。
- [x] Front Matter 错误保留上一份有效记录，SQLite 同步失败回滚，损坏数据库全量重建。
- [x] 支持 `zh_cn`、`en_us` 和 `neutral` 语言选择与回退。
- [x] 使用可复用只读连接、批量元数据加载和精确 ID 索引控制搜索延迟。

对应回归任务：

```bash
./gradlew knowledgeDatabaseSelfTest
```

### 阶段四：助手界面（已完成）

- [x] 关闭时完全隐藏，不常驻 HUD。
- [x] `K` 打开/关闭，`Esc` 按输入焦点优先级关闭。
- [x] 默认居中偏右，标题栏拖动，四边和四角缩放。
- [x] `180×140` 最小值、`720×720` 固定上限和视口 `85%` 上限。
- [x] 游戏窗口缩放后自动修正尺寸和位置；客户端 JSON 保存最后状态。
- [x] 紧凑标题栏/输入区、半透明玻璃表面和无额外背景模糊层。
- [x] 高对比度/减少透明度不透明表面回退。
- [x] 消息滚动、自动定位、单行输入、发送、取消和关闭。
- [x] 欢迎、提问、加载、来源回答、无结果、错误重试和知识库状态。
- [x] 来源预览卡片和“打开原手册”按钮。
- [x] `MockAssistantSession` 接入 `RetrievalService`，可在游戏内测试完整段落、匹配分数和来源跳转。
- [x] 通过反射适配 Patchouli `openBookGUI/openBookEntry` 与 GuideME `GuidesCommon.openGuide`，第三方手册模组保持可选。
- [x] 通过反射适配 APP 书籍/条目入口；框架 JAR 与实际手册内容分离统计。

默认会话实现是 `AiAssistantSession`；`MockAssistantSession` 仍用于不联网的确定性 UI/搜索验证，会用 `error`/“错误”触发错误状态，用 `unknown`/“不存在”触发无结果状态。启动客户端时加 `-Dmodpedia.ai.mock=true` 可切换到模拟会话。

### 知识库规模基准

- [x] 增加 `knowledgeBenchmark` 测试专用任务。
- [x] 分别装载 `zh_cn` 与 `en_us` 语料，验证双语数据规模和搜索延迟。
- [x] 覆盖当前基线、额外 JAR 实际扩展集和 10× 唯一文档集。
- [x] 统计 JAR、模组、来源、文档、关键词、posting、段落、构建和搜索 p50/p95/p99。
- [x] 将无手册资源的前置模组记录为依赖型 JAR，不把它们误报为扫描失败。
- [x] 默认把搜索 p95 预算设为 `50 ms`，为后续大语言模型请求保留时间。

运行：

```bash
./gradlew knowledgeBenchmark
```

可通过 `-PbenchmarkSearchSamples=N` 和 `-PbenchmarkWarmupSamples=N` 调整采样次数。报告写入 `build/reports/modpedia/`，基准转换结果使用临时目录，不改动 `run/config/modpedia/knowledge/`。

### 阶段五：AI 对话、历史和上下文（基础版本已接入）

- [x] 使用 LangChain4j `1.18.1`（Apache-2.0）复用 `AiServices`、`@Tool`、工具调用轮数、`TokenStream` 和 `TokenWindowChatMemory`。
- [x] 使用 OpenAI Chat Completions 兼容接口；配置保存到 `config/modpedia/ai.json`。
- [x] `search_knowledge` 返回完整 Markdown 段落、标题路径、匹配分、来源和 `has_more`。
- [x] 证据不足时允许模型改写查询继续搜索；同一文档只在实际返回后加入排除集合。
- [x] 使用 `ChatMessageSerializer` 和 `PersistentChatMemoryStore` 保存模型上下文；不手写 token 裁剪和 SSE 分段协议。
- [x] 保存 UI 消息、来源卡片和 `SearchTrace` 到 `config/modpedia/conversations/`，不复制 SQLite 中的知识正文。
- [x] 支持中文/英文/neutral 搜索切换、快速/标准/深入/自定义预算、流式取消和 API Key 脱敏输入。
- [x] 历史抽屉支持新建、切换、重命名和删除；设置页支持保存、连接测试和恢复默认。
- [x] `-Dmodpedia.ai.mock=true` 可切回不联网的规则搜索模拟会话。

默认预算：

| 档位 | 最大搜索轮数 | 每轮结果 | 上下文上限 |
| --- | ---: | ---: | ---: |
| 快速 | 1 | 4 | 8,000 字符 |
| 标准 | 3 | 8 | 16,000 字符 |
| 深入 | 5 | 12 | 28,000 字符 |

AI 相关纯 Java 回归任务：

```bash
./gradlew conversationStoreSelfTest
./gradlew aiSettingsSelfTest
./gradlew promptBuilderSelfTest
./gradlew searchKnowledgeToolSelfTest
./gradlew appGuideAdapterSelfTest
```

当前不引入向量数据库或自定义 SSE 解析器：SQLite 负责本地规则检索，LangChain4j 负责模型上下文和流式协议，后续只在基准数据证明必要时增加检索增强。

## 3. 每次修改后的检查

```bash
./gradlew build
git diff --check
```

涉及客户端界面时，再运行：

```bash
./gradlew runClient
```

手动回归顺序：

1. 进入单人世界，确认世界继续运行；按 `K` 打开，再按 `K`/`Esc` 关闭。
2. 拖动标题栏，关闭后重新打开，确认位置保留。
3. 依次拖动四边和四角，确认尺寸始终在 `180×140`、`720×720`、`85%` 视口约束内。
4. 缩放游戏窗口，确认浮窗仍完整可见；滚轮只影响消息区域。
5. 输入一行普通问题并按 `Enter` 发送；加载时点击 `×` 取消。
6. 输入 `error` 测试错误与“重试”，输入 `unknown` 测试无匹配，点击来源卡片测试预览。
7. 确认 ModPedia 不额外绘制底层背景模糊，窗口和游戏画面均保持清晰；修改 `config/modpedia/assistant-glass.json` 的 `themeColor`/`backgroundOpacity`/`glow` 后重新打开助手确认样式变化；打开高对比度选项，确认面板切换为不透明。
8. 点击历史抽屉，确认新建、切换、重命名、删除；重启游戏确认消息、来源卡片和搜索轨迹恢复。
9. 点击标题栏设置入口，确认设置以右侧同层抽屉打开，不切换到新 Screen；确认抽屉打开时底层输入框不抢焦点，填写兼容 API 地址和模型，API Key 输入显示圆点，测试连接不阻塞界面，保存后提问。
10. 提问同时包含配方、步骤和前置条件的问题，确认首次资料不足时模型继续调用 `search_knowledge`，补搜不重复已返回来源，最终回答只引用本轮来源。
11. 确认 `F8` 仍为原版电影视角，`F9` 仍触发知识库重建。

Dedicated Server 回归：

```bash
./gradlew runServer
```

服务端不需要 ModernUI；`ModPediaClient`、`AssistantScreen` 和第三方 UI 反射入口均通过客户端入口与 `Dist.CLIENT` 隔离。

## 4. 发布检查

- 检查 Mod ID 和版本号。
- 检查 JAR 内资源路径。
- 检查日志中没有 API key。
- 检查生成的知识缓存未被误提交。
- 检查构建产物可以被加载。
