# ModPedia 开发日志

## 2026-08-21 · v1.2.0 发布与 AI/配方联动收尾

### 对比 v1.1.0 的变更

- 增加 Chat Completions、原生 Messages、Responses 和 Gemini `generateContent` 四种 API 格式；统一设置、模型列表、连接测试、认证、工具调用续接和 SSE 解析链路。
- 增加本地 `calculate` 工具，以 `BigDecimal` 处理比例、总量、取整和多步算术；计算结果不伪装为手册来源。
- 增加 `query_item_recipes`：工作台/熔炉直接查询，熔炉返回处理时间；其它方式使用 `OTHER → DETAIL`，机器等级合并，结果通过 Worker IPC 返回且不写入知识库。
- 修正 Jade/FTBQ/JEI/容器槽位/Tooltip 的物品识别；按下 `K` 时冻结目标，打开助手后由界面按钮显式插入，避免底层页面继续改变目标。
- 物品目录改为主菜单阶段一次性缓存；第三方 Tooltip 监听器异常时熔断简介捕获并继续导入 ID/名称，避免大型整合包重复异常造成日志恶性膨胀。
- 放宽 Token 压缩策略，保留最近两次工具回合的完整证据，更早历史保留来源 ID、标题路径、来源路径和正文首尾；按搜索强度使用分级预算。
- 原生 Minecraft 选项/按键页面不再被 `K` 呼出助手；补充协议、配方、计算、输入策略、物品目录调度和成本优化自测。

### 发布验证

```text
./gradlew test
./gradlew build
git diff --check
```

本轮测试与构建结果记录在发布工件 `build/release-artifacts/v1.2.0/VERIFICATION.txt`；发布 JAR、校验文件和当前版本更新日志由 `v1.2.0` 标签流水线生成。

## 2026-08-18 · v1.1.0 发布与用户级 AI 配置迁移

### 变更

- `ai.json` 从实例内的 `config/modpedia/ai.json` / `config/modpedia/runtime/ai.json` 迁移到 `~/.modpedia/ai.json`，不同 Minecraft 版本和整合包共用同一份用户级配置。
- `installation-id` 同步放到 `~/.modpedia/installation-id`，作为无法读取系统 UUID 时的共享回退标识。
- AI 配置继续使用系统标识派生的 AES-GCM 密文；支持 POSIX 的系统将用户目录限制为 `0700`、配置文件限制为 `0600`。
- 修正 Worker IPC 自测夹具，使用注入的临时用户目录，避免测试改写维护者真实的共享 AI 配置。
- 同步更新 README、英文 README、架构、知识库、项目入门、开发清单、Schema 和 GitHub Pages 发布警告。

### 验证

- 用户级配置读取回归：通过；API Key 仅输出存在性和长度，不输出内容。
- `./gradlew test build`：通过，48 个任务成功。
- `git diff --check`：通过。
- 迁移前配置 SHA-256、加密后文件权限和独立副本回滚结果记录在临时验证包中；不写入仓库。

## 2026-08-18 · v1.0.1 正式发布准备

- 将版本号从 `1.0.0-fix` 更新为 `1.0.1`，保留 `v1.0.0-fix` 作为历史修复预发布标签。
- 将生成的 ModPedia 图标写入 `src/main/resources/modpedia.png`，并在 NeoForge 元数据中设置 `logoFile`。
- 更新 Mod 列表介绍、README、安装说明、开发清单和 GitHub Pages 下载链接。
- 完成测试、构建和 JAR 元数据验证后提交并发布 `v1.0.1`。

## 2026-08-18 · v1.0.0-fix 修复预发布

### 变更

- `ai.json` 改为保存系统标识派生的 AES-GCM API Key 密文；原始 API Key 仅在进程内缓存。
- Worker 启动时预加载设置，避免首次聊天请求才执行密钥解密。
- 系统标识变化、密文损坏和旧版明文配置均有对应处理和回归测试。
- ATM10 测试实例已替换为当前构建 JAR，并完成源文件与目标文件 SHA-256 校验。

### 验证

- `./gradlew test`：通过。
- `./gradlew build`：通过。
- `git diff --check`：通过。
- 发布目标：`v1.0.0-fix`，由 GitHub Release 流水线生成修复预发布资产。

## 2026-08-18 · 运行时与整合包事实源目录分离

- 将 `config/modpedia/` 拆为 `runtime/` 和 `knowledge/`：前者保存 AI 设置、会话、诊断、Worker、窗口配置以及派生知识库；后者只保存 `custom/`、Wiki 来源、媒体元数据、来源覆盖和同义词配置。
- 启动入口自动把早期散落在根目录的运行时文件迁移到 `runtime/`；`custom/`、`sources/`、`media.json` 和 `source-overrides.json` 原地保留。
- Worker 接收独立的 runtime knowledge root 与 content root；SQLite、生成 Markdown、索引和状态写入 `runtime/knowledge/`，手册/Wiki 事实源从 `knowledge/` 读取。
- 新增 `modPediaPathsSelfTest` 和分离目录 Worker IPC 夹具，覆盖迁移幂等、事实源保留、独立 JVM 构建和 runtime SQLite 输出。
- 移除开发运行时额外注入的 Gson 2.11 依赖，继续使用 NeoForge/Minecraft 提供的 Gson，修复 `runServer` 的 Gson 版本约束冲突。

## 2026-08-18 · v1.0.0 正式发布

### 发布状态

- 当前发布目标：`v1.0.0`。
- 发布分支：`main`。
- `v0.3.0` 保留为上一阶段正式版本；`v1.0.0-beta.1` 和 `v1.0.0-beta.2` 保留为历史测试标签。
- 本次发布包含 Worker 性能门禁修复、整合包 `config` 发布清理说明和版本文档收尾。

### 验证边界

- 本地 `test`、`build`、严格 Worker p95 自测和 `git diff --check` 通过。
- 大型整合包人工回归已完成；图形客户端、可选联动和 Dedicated Server 证据继续按
  [`KNOWN_LIMITATIONS.md`](../KNOWN_LIMITATIONS.md) 单独记录。
- 发布包不包含 `knowledge.db`、运行目录、历史会话、API 配置、诊断报告或密钥。

## 2026-08-17 · v0.3.0 合并候选

### 当前状态

- 当前发布目标：`v0.3.0`。
- 合并候选分支：`agent/full-maintenance-checkpoint`。
- `main` 基线：`v0.2.0`。
- 用户已完成大型整合包人工回归；本地自动化测试、构建和差异检查均通过。

### 自动化验证

```text
JAVA_HOME=<Java 21 JDK> ./gradlew test --no-daemon       BUILD SUCCESSFUL
JAVA_HOME=<Java 21 JDK> ./gradlew build --no-daemon      BUILD SUCCESSFUL
git diff --check                                         exit 0
```

本次发布不把 `knowledge.db`、游戏运行目录、会话数据、API 配置或密钥纳入提交。

> 这份文件记录可审阅的工程维护、验证证据和合并准备状态；它不替代
> [`CHANGELOG.md`](../CHANGELOG.md)，也不记录 API Key、请求头或本地运行目录内容。

## 2026-08-12 · 合并前维护

### 基线

| 项目 | 值 |
| --- | --- |
| 分支 | `agent/full-maintenance-checkpoint` |
| 基准提交 | `406a205` (`feat: integrate task wiki and knowledge search`) |
| 目标版本 | Minecraft `1.21.1` / NeoForge `21.1.244` / Java `21` |
| 当前 Mod 版本 | `0.3.0` |
| Mod ID / 包名 | `modpedia` / `io.ctyx.modpedia` |
| 提交作者 | GitHub 登录账号 `ct-yx` |

### 本轮整理范围

- 将 Worker、IPC 协议、任务运行时读取、知识库构建和物品目录同步保持在独立包中；游戏侧只负责客户端注册表/Tooltip 捕获、UI 和轻量事件。
- 保留 LangChain4j Community SQL 的 `SQLChatMemoryStore`，项目代码只维护 SQLite 数据源和方言装配，不重新实现通用上下文存储。
- 保留 Schema v7 的统一 `knowledge.db`：模组手册、Wiki、静态 FTBQ 任务定义和 `item_catalog` 使用独立表/来源字段。
- GuideME 采用 `zh_cn → en_us → neutral` 回退并去除重复地区语言；来源统计区分手册内容来源和 Patchouli/GuideME/Modonomicon 前置框架。
- FTBQ 实时进度只在任务问题触发时读取并保存在请求内存快照中；静态任务定义继续导入数据库。
- 物品目录通过 Worker 的批量载荷同步，避免游戏线程直接打开 SQLite 或构造超大 IPC JSON。
- 清理已移除的旧 UI 依赖桥接和旧知识库更新入口，保持原生 NeoForge GUI 与 Worker 入口唯一。
- 新增/保留纯 Java 自测、Worker IPC 自测、语言回退自测、FTS5 性能自测和物品目录批量同步自测。

### 当前知识库现场快照

以下数据来自本地开发实例的 `run/config/modpedia/runtime/knowledge/knowledge.db`，不是提交内容：

```text
Schema:      7
来源:        9
文档:        543
段落:        8033
物品目录:    8709
扫描归档:    18
扫描资源:    542
扫描警告:    0
```

9 个来源为 8 个内容模组手册来源和 1 个 FTB Quests Wiki 来源；手册框架、JEI、Jade
和没有可识别正文的前置 JAR 不计为正文来源。

### 自动化验证

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home ./gradlew --offline test` | 通过 | 39 个可执行测试任务；包含 Worker IPC、SQLite/FTS、双语搜索、AI 链路和物品目录自测 |
| `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home ./gradlew --offline clean build` | 通过 | 从干净构建目录完成编译、Jar-in-Jar、全部自测和最终 JAR 生成 |
| `git diff --check` | 通过 | 无空白错误 |
| 真实模型请求 | 未执行 | 本轮不调用模型；AI 错误夹具仅验证重试和 503 处理 |
| Minecraft 客户端 GUI 回归 | 待人工 | 需要目标整合包和 GUI Scale 4 实机 |
| Dedicated Server 实机回归 | 待人工 | 需要单独启动目标运行配置 |

测试中的 `temporary unavailable` 日志来自 AI 客户端自测的模拟 503，不是本轮真实 API
请求，也不代表 API Key 失效。

### 合并前检查清单

- [x] 源码、测试和文档均使用 UTF-8，`git diff --check` 通过。
- [x] 运行目录、SQLite、API 设置、会话、日志和构建目录保持 Git 忽略。
- [x] 构建使用 Java 21 和 NeoForge 1.21.1 基线。
- [x] 测试 JAR 已重新构建并完成 SHA-256 校验（见下方）。
- [ ] 提交前再次审阅 `git diff --stat`，确认没有把用户的本地运行产物加入暂存区。
- [ ] 使用 GitHub 登录账号 `ct-yx` 创建提交；建议提交信息：`feat: isolate worker and expand knowledge integration`。
- [ ] 合并前完成人工客户端、来源跳转、任务联动、JEI/Jade 可选依赖和 Dedicated Server 回归。

### 测试 JAR

测试副本不进入 Git，便于直接替换到测试实例：

```text
build/test-artifacts/modpedia-0.2.0-test.jar
build/test-artifacts/SHA256SUMS
```

生成方式是先构建 `build/libs/modpedia-0.2.0.jar`，再复制为带 `-test` 后缀的副本；
副本内容与 Gradle 构建产物一致，文件哈希记录在 `build/test-artifacts/SHA256SUMS`：

```text
SHA-256: 8c491d068f54b4638f7e48fa59176f6dbae1fbfd8b19a9f9f6755974ecd20d8c
```

## 日志维护规则

- 每次合并候选至少记录：分支/基准提交、改动范围、验证命令、未完成的人工回归和测试产物。
- 不把 `run/`、`logs/`、`build/`、`knowledge.db`、模型配置或密钥复制进文档。
- 自动化通过不等于真实客户端通过；图形、第三方手册跳转和外部模型均单独标记。
- 正式版本变更摘要写入 `CHANGELOG.md`，详细工程证据保留在本文件中。

## 2026-08-13 · 全库维护继续

### 本轮变更

- 将 Worker 知识库操作的合并状态抽为 `KnowledgeOperationGate`，锁定“运行中只保留一个最新 pending 请求”的状态机，避免 F9、Wiki 更新和启动构建互相覆盖。
- 增加 staged 数据库替换失败后的旧库恢复回归；同时修正测试中残留的旧 Schema 文案。
- 将维护交接、项目入门、已知限制和历史审查文档改为区分“历史风险”与“当前已修复状态”。

### 本轮验证

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home ./gradlew --offline test --no-configuration-cache  通过
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home ./gradlew --offline build --no-configuration-cache  通过
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home ./gradlew --offline knowledgeOperationGateSelfTest --no-configuration-cache  通过
git diff --check  通过
```

本轮自动化仍不调用真实模型；客户端实机、Dedicated Server 和大型整合包回归保持待人工状态。

## 2026-08-14 · 审查风险点收尾

### 本轮修复

- SQLite staged 替换不再先移走唯一的正式库：先复制并校验 `.previous`，再替换
  `knowledge.db`；`replace-state` 在启动时恢复未完成替换。恢复和清理同时覆盖
  `-wal`、`-shm` 以及当前默认 DELETE journal 的 `-journal` 文件。
- 增加无 marker 的 staged/restore 临时库清理，避免进程在写恢复 marker 前退出后
  持续积累临时数据库。
- Wiki `source.json`、Markdown 导入或 UTF-8 校验失败时直接终止本次编译，不进入
  SQLite 同步，因此上一份有效 Wiki 索引不会被不完整输入删除。
- FTBQ 运行时进度的 fallback 绑定增加父 quest 来源约束；当多个来源复用同一
  quest ID 时，不能只凭唯一 task ID 把进度套到错误来源。
- `RetrievalService` 增加显式关闭连接，并在关闭时清空旧快照状态，避免数据库替换
  后长期保留旧只读连接。

### 自动化验证

```text
./gradlew --offline knowledgeDatabaseSelfTest taskKnowledgeStoreSelfTest \
  --no-configuration-cache --no-daemon --console=plain  通过
./gradlew --offline test --no-configuration-cache --no-daemon --console=plain  通过
./gradlew --offline build --no-configuration-cache --no-daemon --console=plain  通过
./gradlew --offline knowledgeFtsPerformanceSelfTest \
  --no-configuration-cache --no-daemon --console=plain  通过，400 docs/1200 segments，p95 3.61 ms
git diff --check  通过
```

本轮完整测试为 42 个 actionable tasks；Worker IPC 夹具 p50 `0.068 ms`、p95
`0.109 ms`、p99 `0.146 ms`，20,000 条物品目录同步约 `116.38 ms`。所有测试使用本地
夹具，不调用真实模型、不读取用户 API Key。

### 尚未替代人工验收的项目

- 没有执行真实断电/硬杀进程，只验证了 marker、备份、sidecar 和孤儿临时文件的
  确定性恢复路径。
- ATM10/大型整合包真实客户端 FPS、Dedicated Server、FTBQ/JEI/Jade 真实版本 API、
  手册跳转和用户配置模型仍需人工回归。
- 当前分支仍有前序未提交和未跟踪改动；本轮没有提交、推送或清理工作区。

## 2026-08-14 · Worker 并发与运行时读取边界

### 本轮变更

- AI 请求改用独立有界执行器：默认最多 2 个并发请求、4 个排队槽位；队列满时立即
  返回 `WORKER_BUSY`，不再使用无界 `newCachedThreadPool`。
- 同一 `conversation_id` 增加活动请求租约；同一会话只允许一个 AI 回合，不同会话
  仍可并行。取消排队请求时会释放租约，运行中的请求等实际退出后释放。
- 运行时任务读取改为单线程有界协调器：相同聊天请求单飞合并，短时快照缓存命中时
  不再重复切回 Minecraft 线程；超时、取消、世界切换和 Worker 关闭会完成 waiter。
- FTBQ 客户端适配器增加 750 ms 的当前世界/玩家快照缓存，并在退出时清理。
- Bridge 明确区分 `addRawListener` 和 `addClientListener`；后者由 Bridge 统一切回
  Minecraft 线程，Worker 会话和设置面板已迁移，调用方不再各自包裹 `Minecraft.execute`。

### 自动化验证

```text
./gradlew --offline runtimeContextCoordinatorSelfTest workerConcurrencySelfTest \
  --no-configuration-cache --no-daemon --console=plain  通过
```

本轮没有调用真实模型；待后续在大型整合包中验证 AI 并发背压、FTBQ 任务查询和世界
切换生命周期。
