# v1.4.0 发布对比与验收记录

## 发布定位

`v1.4.0` 是以 `v1.1.0` 为对比基线的三版本发布。中间的 `v1.2.0`、`v1.2.0-fix`
和 `v1.3.0` 只作为开发/修复过程中的历史技术标签，不作为本次变更的对比基准；本次把
已经完成的跨版本适配、Worker 隔离、知识库、AI、物品和整合包联动统一发布。

## 版本构建矩阵

| 发布资产 | Minecraft | 加载器/兼容线 | Java | Worker 基线 | 构建结果 |
| --- | --- | --- | --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | 1.21.1 | NeoForge 21.1.x | 21 | `worker-baseline-3` | `[x]` |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | 1.20.1 | Forge 47.x | 21 | `worker-baseline-3` | `[x]` |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | 1.12.2 | Cleanroom 0.3+ 兼容线（Forge 1.12.2 API） | 游戏 Java 8；Worker Java 21 | `worker-baseline-3` | `[x]` |

> 1.12.2 的 ForgeGradle 2.3/Pack200 发布构建使用 Java 8；玩家运行时仍按目标加载器要求使用
> 游戏 Java 8，独立 Worker 使用 Java 21。Java 17 及更高版本移除了该旧构建所需的 Pack200 API。

## 相对 v1.1.0 的新增

### 三版本与 Worker

- 增加 Forge 1.20.1 兼容实现和 Cleanroom 0.3+ / 1.12.2 兼容实现。
- 将 Worker、AI、SQLite/FTS5、任务静态解析和知识库构建从游戏主线程隔离到独立 JVM。
- 增加 Worker 握手、能力协商、固定基线和用户级共享依赖目录；实例只保留日志、IPC 状态和临时载荷。
- 为旧启动器覆盖 `user.home` 的情况增加 `HOME`/`USERPROFILE` 解析和旧配置、旧 Worker 库迁移。

### 本地知识库与文档来源

- 统一 `knowledge.db` 保存模组手册、Wiki、任务静态定义和当前语言物品目录，并通过内容类型、来源类型和集合 ID 隔离检索。
- Patchouli 书籍、分类、条目和页面：支持 `zh_cn → en_us → neutral` 回退，保留标题路径、原始路径和来源跳转。
- GuideME/Guide-API/1.12.2 兼容线的 Markdown、文本和语言目录：保留页面索引、Markdown 和跳转信息。
- Modonomicon/APP JSON 书籍：支持书籍、分类、条目、页面、配方/物品/链接节点及未知节点的完整 Markdown 降级；可通过 `knowledge` 字段、`source.json` 或覆盖文件归类为模组手册或 Wiki。
- 整合包作者自定义来源：支持 `config/modpedia/knowledge/custom/**/*.md`、`sources/<source-id>/source.json`、`documents/**/*.md`、`media.json`、`source-overrides.json` 和 `search-synonyms.json`。
- 明确区分手册框架与正文内容模组：Patchouli、GuideME、Modonomicon/APP 等前置本身没有正文时，不会误报为扫描失败。
- FTS5 使用 external-content，正文从事实表读取；大批量导入后合并索引，小规模更新只执行 `PRAGMA optimize`，保留完整 Markdown。

### AI、搜索和成本控制

- 支持 Chat Completions、原生 Messages、Responses、Gemini `generateContent` 四种 API 形态。
- AI 可以调用本地知识、Wiki、任务、配方和 `calculate` 工具；证据不足时按预算补搜，检索阶段不输出无用过程文本。
- 仅搜索模式不读取 API 配置，直接返回完整段落、标题路径、匹配分、物品信息和正文内来源跳转。
- 使用 LangChain4j Community SQL 的持久化上下文适配，保留工具调用消息、会话恢复和产品层搜索轨迹。
- 采用分级上下文保留而非激进截断：最近工具证据完整保留，更早证据保留来源 ID、路径和正文关键片段，减少丢失检索事实。
- 增加模型列表、连接测试、四种协议的请求/流式/工具调用续接以及错误回退自测。

### 任务、物品和配方联动

- FTB Quests：导入静态任务定义、依赖、要求和奖励；查询任务时按需读取当前玩家完成/进行中进度，不把运行时进度写入全局知识库；支持阻塞原因、候选下一步和时间线。
- JEI：不复制配方到数据库。模型先声明工作台、熔炉或其它处理方式；工作台/熔炉直接查配方，熔炉返回处理时间，其它方式采用 `OTHER → DETAIL` 两阶段查询并合并同类机器等级。
- Jade 和原准星/悬浮目标：目标在按 `K` 时冻结，打开助手后由“插入”按钮明确写入；底层界面不会继续改变目标。
- 物品目录：按当前语言保存所有注册物品的 ID、显示名称、完整 Tooltip 和来源模组；普通区域显示名称，按住 Ctrl 显示 ID，Shift+点击物品名称尝试打开 JEI。
- 对可选联动全部使用客户端隔离和软依赖；缺少 FTB Quests、JEI、Jade、手册模组时，核心助手和 Dedicated Server 仍可加载。

### UI、稳定性与发布维护

- 历史、设置、来源、物品和后续问题按钮保持在单一助手窗口图层内，随窗口缩放并受 scissor 边界约束。
- 原生选项页和按键绑定页不会呼出助手；第三方界面可返回助手层，来源跳转关闭后恢复原 UI。
- 增加 Markdown、来源标注、API Key 加密、首次请求、503/429、流式回退、任务快照、路径迁移和大型注册表批量导入自测。
- 三版本发布资产、SHA-256、GitHub Release、CurseForge 文件和 GitHub Pages 下载矩阵由 `main` 统一维护。

## 本次删除或不再承诺的内容

- 不再把配方全量导入 `knowledge.db`；配方事实由 JEI 运行时按需提供。
- 不把外部手册框架当作正文来源；正文必须来自内容模组、整合包作者 Wiki 或自定义 Markdown。
- 不在游戏 Tick 或渲染线程执行全量物品 Tooltip 扫描、网络请求、SQLite 写入和 AI 请求。
- 不在发布仓库保存 JAR、玩家会话、数据库、API Key、Worker 临时目录或本地构建缓存。
- 1.20.1 和 1.12.2 分支不重复维护 Pages、Release 和 CurseForge 工作流；发布事实源只在 `main`。

## 修复项

- 修复启动器覆盖 `user.home` 后把 `ai.json` 和 Worker 库写入游戏目录的问题。
- 修复 AI 首次请求、工具续接、SSE/非流式回退、503/429 重试和模型协议差异导致的无响应/答非所问链路。
- 修复 Markdown 标题、列表、代码、物品令牌和正文内来源按钮的显示与点击区域问题。
- 修复 FTBQ 完成任务数量重复统计、进度只读链路、世界/维度归属和事件时间线问题。
- 修复 JEI 当前版本运行时入口、Shift 点击、物品名称回退和多机器等级重复展示问题。
- 修复大型整合包物品目录扫描造成的主线程卡顿、日志膨胀、Tooltip 异常重复输出和语言切换重复扫描。
- 修复 1.12.2 显示名称编码、实例路径隔离、Worker 嵌入和 Cleanroom 兼容线的旧 API 使用问题。

## 验收命令

三条代码分支均已执行对应的纯 Java 自测试和构建。发布分支最终还需执行：

```bash
./gradlew test
./gradlew build
git diff --check
```

发布资产的校验值保存在构建目录 `build/release-artifacts/v1.4.0/SHA256SUMS`，正式 Release
会再次由 GitHub Actions 生成并上传。

## 仍需人工回归

- 三个目标加载器的真实大型整合包启动、Dedicated Server 和手册来源跳转。
- 1.12.2 Cleanroom 0.3+ 的实际客户端 UI、JEI/FTBQ/Jade 组合。
- 用户配置的低成本模型在四种 API 格式下的多轮工具调用、流式、取消、超时和历史恢复。
- CurseForge 页面完成三份文件的实际审核后，核对文件所标记的加载器和游戏版本。
