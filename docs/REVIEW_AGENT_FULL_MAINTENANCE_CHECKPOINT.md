# `agent/full-maintenance-checkpoint` 合并前全量审查（历史审查记录）

> 本文保留原始风险审查记录，并附有 Codex 本轮未提交修复的只读验收结果。原始 Findings 反映 `406a205` 提交树；当前修复状态以文末“Codex 修复验收”章节为准。当前工作树中的未跟踪 Worker/IPC 文件不属于原始 PR 差异范围，但本轮验收针对工作树 `git diff HEAD` 的修复代码进行核验。

- **审查范围**：`git diff main...agent/full-maintenance-checkpoint`
- **基线**：`main` / `13b6af4` (`v0.2.0`)
- **目标**：`agent/full-maintenance-checkpoint` / `406a205`
- **差异规模**：66 个文件，约 5,167 行新增、248 行删除
- **审查方式**：只读；结合差异、目标分支上下文和多视角交叉核验
- **工作树说明**：当前工作树还有未提交及未跟踪文件。它们不属于本报告范围；尤其是未跟踪的 Worker/IPC 文件没有作为本分支 PR finding 计入。

## 结论摘要

本分支完成了 FTB Quests 任务快照、任务搜索、Wiki 同步、知识库 schema/FTS 重构以及客户端展示集成，整体 Gradle 自测可运行。但不建议在不修复下列问题的情况下直接合并：

- **2 个高严重性数据/运行时问题**：不兼容数据库重建失败时会丢失旧库；启动时 Wiki 重建请求可能被丢弃。
- **2 个高严重性客户端稳定性问题**：任务书快照和 SQLite 全量写入在客户端 tick 线程执行；远程 Wiki 无条件覆盖本地文件。
- **1 个高严重性资源耗尽问题**：Wiki HTTP 响应无大小上限。
- **1 个中高严重性查询性能问题**：任务查询在应用 `limit` 前完整加载和展开所有匹配任务。
- **2 个中等正确性问题**：任务分页 `has_more` 误报；任务快照刷新会因把实时进度纳入 snapshot ID 而清除已有进度。
- **1 个中等数据一致性问题**：删除快照后遗留 `knowledge_sources` 元数据。
- **1 个中等测试工程问题**：实时墙钟 p95 性能测试被挂到默认 `test` 门禁，可能造成环境相关的随机失败。

建议将高严重性问题作为合并阻断项；其余问题至少应在后续 issue 中明确跟踪并补回归测试。

## Findings

### 1. 高：不兼容数据库会在新库构建成功前被永久删除

**位置**：[KnowledgeDatabase.java:196](../src/main/java/io/ctyx/modpedia/search/KnowledgeDatabase.java#L196)、[KnowledgeDatabase.java:1973](../src/main/java/io/ctyx/modpedia/search/KnowledgeDatabase.java#L1973)

`sync()` 先调用 `resetIfIncompatible(database)`；当旧库 schema 不兼容或损坏时，`resetIfIncompatible()` 直接删除 `knowledge.db`、`-wal` 和 `-shm`，之后才创建 staged 数据库并执行导入。若后续 SQLite 建库、文档导入、磁盘写入或 staged 替换失败，catch 只删除 staged 文件并抛出异常，旧库已经无法恢复。

**失败场景**：用户从旧 schema 启动升级，或现有库出现可检测的不兼容状态；重建过程中断电、磁盘空间不足、输入解析失败或文件替换失败。搜索库、任务快照、任务进度及同库缓存都会丢失，而编译器文档所称的“失败保留上一版本”不成立。

**建议**：先将旧库及 WAL/SHM 原子改名为备份，成功构建并校验 staged 库后再替换；失败时恢复备份。至少不要在 staged 成功前删除唯一的旧副本。

### 2. 高：启动期间 Wiki 更新触发的重建请求会被静默丢弃

**位置**：[TaskWikiSyncService.java:64-73](../src/main/java/io/ctyx/modpedia/client/TaskWikiSyncService.java#L64)

启动流程先异步启动知识库构建，再执行 Wiki 同步。Wiki 文件变化后调用 `KnowledgeUpdateService.rebuildAsync()`；当原构建仍处于 `RUNNING` 状态时，该方法返回 `false`，没有 pending 标记或完成后的再次触发。

**失败场景**：首次启动时内置或远程 Wiki 文件刚写入，而初始知识库扫描已开始。构建不会看到新文件，Wiki 同步随后发起的 rebuild 被拒绝且丢弃；文件存在但直到用户手动触发或下一次启动前不可搜索。

**建议**：让 rebuild 请求合并为 pending generation，在当前构建完成后自动再跑一次；或将 Wiki 同步排在初始构建前，并对 changed 文件执行明确的后置重建。

### 3. 高：FTB Quests 全量快照与 SQLite 写事务阻塞客户端 tick 线程

**位置**：[FtbQuestsClientAdapter.java:55-64](../src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java#L55)

`ClientTickEvent.Post` 每 40 tick 反射遍历并序列化整个任务书；指纹变化后直接调用 `store.syncSnapshot(snapshot)`。该调用会删除旧快照、批量插入任务/依赖/目标/奖励并提交 SQLite 事务，均在客户端 tick 线程同步完成。

**失败场景**：大型整合包、首次进入世界、任务进度变化、慢速磁盘或 SQLite 锁竞争。一次全量序列化和写库会延长 tick，造成明显卡顿；极端情况下可能触发客户端 watchdog 或使输入/渲染停顿。

**建议**：在客户端线程只捕获不可变快照和调度任务，将序列化/SQLite 写入移到受控后台 executor；按世界/玩家生命周期取消旧任务，并在完成后回主线程更新 fingerprint。需要处理快照顺序，避免旧的后台写入覆盖较新的快照。

### 4. 高：远程 Wiki 响应无大小上限，配置 URL 可造成内存耗尽

**位置**：[TaskWikiSyncService.java:117-132](../src/main/java/io/ctyx/modpedia/client/TaskWikiSyncService.java#L117)

`HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)` 在内存中无界缓冲整个 HTTP 响应。URL 由 `modpedia.taskWikiUrl` 系统属性配置，代码没有响应 Content-Length 检查、流式读取上限或最大 Markdown 大小限制。

**失败场景**：配置错误、被劫持或恶意的 Wiki endpoint 返回超大响应；客户端在写文件和知识库 rebuild 前先分配完整字符串，可能造成高堆内存占用、GC 抖动甚至 OOM。

**建议**：限制最大响应字节数，拒绝超出上限的 Content-Length，并使用带计数的流式读取；同时限制最终 Markdown 文件大小，超限时保留现有缓存。

### 5. 中高：任务搜索在应用 limit 前完整物化所有匹配任务

**位置**：[TaskKnowledgeStore.java:54-64](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L54)、[TaskKnowledgeStore.java:308-315](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L308)

`readQuests()` 生成的 SQL 只有 `ORDER BY`，没有 `LIMIT`。`query()` 先读取全部匹配行，再为每个任务调用 `toResult()`；后者还会查询依赖、每个依赖的进度、任务、奖励和进度标记，最后才排序并截断到 `actual.limit()`。

**失败场景**：对大型 FTB 任务书执行宽泛 `SEARCH` 或 `NEXT`，即使 API limit 为 1–20，也会先加载和展开全部匹配任务，产生 O(N) 内存和 O(N×关联项) 数据库查询，可能卡住 AI 请求线程或造成高内存压力。

**建议**：将排序和 limit 下推到 SQL，最好使用 `limit + 1` 判断是否还有更多；只为候选页物化详情，或改为批量查询关联数据。

### 6. 中：任务搜索的 `has_more` 在恰好命中 limit 时误报

**位置**：[SearchKnowledgeTool.java:440-442](../src/main/java/io/ctyx/modpedia/ai/SearchKnowledgeTool.java#L440)

代码以 `quests.size() >= limit` 判断 `has_more`，但 `TaskKnowledgeStore.query()` 已经在返回前将结果截断到 `limit`。因此恰好只有 `limit` 条匹配时也会报告 `has_more=true`，调用方可能发起无意义的后续查询；`SearchTrace` 同样记录错误值。

**失败场景**：limit=1，数据库恰好只有一个匹配任务；返回一条结果但 `has_more=true`。

**建议**：查询层返回 total/hasMore，或 SQL 使用 `LIMIT limit + 1` 后去掉额外行；不要从已截断列表推断总量。

### 7. 中：实时进度被纳入 FTB Quests snapshot ID，刷新快照会清除旧进度

**位置**：[FtbQuestsClientAdapter.java:105-113](../src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java#L105)、[TaskKnowledgeStore.java:28-35](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L28)

适配器用包含实时任务/目标状态的 `rawJson` 生成 `snapshotId`。玩家完成任务或进度变化时，snapshot ID 随之变化。同步新快照时先按 `sourceKey` 删除旧快照及其 `task_progress`，再插入新 snapshot；进度没有按稳定的 quest/task identity 迁移。

**失败场景**：任务定义不变，玩家只推进一个目标；下一次 tick 生成新 snapshot ID，旧 snapshot 的进度行被删除，新快照没有对应进度，搜索结果中的已完成/当前值状态退回快照值或丢失。

**建议**：snapshot identity/fingerprint 只基于静态任务定义；运行时进度单独 upsert，按 `sourceKey + questId + taskId` 等稳定键迁移。若必须更换 snapshot ID，应在同一事务中迁移进度。

### 8. 中：删除任务快照不会删除对应的知识来源元数据

**位置**：[TaskKnowledgeStore.java:143-164](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L143)

`deleteSnapshot()` 删除 `task_progress` 和 `task_snapshots`，但没有删除对应的 `knowledge_sources`（通常是 `task:<sourceKey>`）行。替换或移除任务快照后，来源清单会保留孤立的 task source 元数据，可能展示不存在的来源或导致来源统计错误。

**失败场景**：同步某个 sourceKey 后，该任务书不再可用或被替换；查询不到快照，但 source inventory 仍报告原 task source。

**建议**：在同一事务中按稳定 source key 删除/更新 `knowledge_sources`，并增加“快照删除后来源不存在”的回归测试；确保外键或清理策略覆盖 task_runtime 来源。

### 9. 中：实时 p95 FTS 性能测试被接入默认 test，容易产生环境相关失败

**位置**：[build.gradle:442-452](../build.gradle#L442)、[KnowledgeFtsPerformanceSelfTest.java:65-73](../src/test/java/io/ctyx/modpedia/search/KnowledgeFtsPerformanceSelfTest.java#L65)

默认 `test` 依赖 `knowledgeFtsPerformanceSelfTest`。该 self-test 在当前机器上对实时 SQLite/FTS 查询做有限次数墙钟采样，并硬性断言 p95 不超过 50ms。该断言受 CI 负载、JIT/GC、磁盘竞争、并行任务和机器规格影响，并不只反映功能正确性。

**证据**：本次审查环境的 `./gradlew test` 成功，FTS self-test 报告 p95 8.29ms；这只能说明当前运行通过，不能消除在受载环境下越过阈值的随机失败风险。

**建议**：将性能基准保留为显式 `knowledgeFtsPerformanceSelfTest`/benchmark 任务，不作为默认单元测试门禁；若 CI 必须监测性能，应使用稳定基准环境和趋势/告警，而不是单次硬阈值。

## 未计入本次 PR 范围的事项

当前工作树存在若干未跟踪 Worker/IPC 文件。审查代理指出其中可能存在通过命令行参数暴露 Worker token 的安全风险，但这些文件不在 `git diff main...agent/full-maintenance-checkpoint` 的提交树中，因此本报告没有将其作为本分支合并 finding。若这些文件也准备随合并提交，应另行纳入审查。

## 测试与覆盖率观察

本次运行：

```bash
cd /Users/chenhong/Documents/modpedia && ./gradlew test
```

结果：`BUILD SUCCESSFUL`，39 个 actionable tasks；新增 self-test 均可运行。该结果不能覆盖以下关键场景，建议补充：

- staged 数据库构建/替换失败后的旧库恢复，以及 WAL/SHM 一致性；
- Wiki 初始构建处于 RUNNING 时的 pending rebuild；
- Wiki 响应大小、超时、截断和本地文件保护；
- FTB Quests 仅进度变化时 snapshot identity 不变且进度保留；
- 删除/替换快照后 `knowledge_sources` 清理；
- SQL limit、恰好 limit 条和 limit+1 条任务的 `has_more`；
- 大型任务书查询的查询次数、内存和后台线程行为；
- 客户端 tick 与后台同步的生命周期、取消和写入顺序。

## 代码质量与维护性

优点：

- 任务快照、知识源描述和内容种类被抽象成记录/接口，降低了 FTB Quests API 对搜索层的耦合。
- 使用 prepared statements、事务和 staged database 的方向是合理的；问题在于失败回滚和顺序控制不完整。
- 新增 self-test 覆盖了若干搜索、渲染和任务存储 happy path。
- 文档对知识库来源和已知限制有补充，便于维护者理解整体架构。

需要改进：

- 将“静态定义”和“实时状态”分开建模，避免用包含状态的 JSON 作为结构身份。
- 将同步操作的线程边界、生命周期和失败语义写成明确契约，并用测试锁定。
- 外部网络输入必须有大小、协议和缓存保护；本地可编辑文件更新必须有冲突策略。
- 让分页语义由数据层提供，而不是在已经截断的列表上猜测。
- 将性能测试与功能测试分离，避免环境噪声阻断普通构建。

## Codex 修复验收（当前工作树）

### 验收范围

本节核验的是 Codex 本轮相对 `HEAD=406a205` 的未提交代码差异：

```bash
git diff HEAD
```

未将本轮新增的审查/方案文档当作生产修复，也未修改任何源代码。验收采用只读代码复核、adversarial 交叉核验和 Gradle 测试。

### 测试证据

最终使用单进程命令：

```bash
cd /Users/chenhong/Documents/modpedia && ./gradlew test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，43 个 actionable tasks；FTS 性能 self-test 最终 p95 为 `4.01 ms`。

此前同时启动两个 Gradle 测试进程时，`knowledgeFtsPerformanceSelfTest` 曾出现 p95 `117.04 ms` 并失败；单独复跑为 `9.10 ms`，随后单进程完整测试为 `4.01 ms`。这说明性能门禁受并行资源竞争影响，不能把一次通过视为稳定基准，也不能把并行运行失败直接归因于产品查询逻辑。

### 修复确认

以下 Codex 修复经核验基本成立：

- Wiki 网络响应大小、流式读取、UTF-8、HTTP 错误和本地缓存保护已增加，对应 Worker Wiki self-test 通过。
- 知识重建已具备串行门控/pending 语义，未发现本轮目标中的“RUNNING 时请求永久丢失”复现路径。
- FTB Quests 旧的客户端 tick 同步写库路径已移除，运行时读取/Worker 文件链路和生命周期已有对应测试。
- 任务查询已加入 SQL 分页、`LIMIT/OFFSET`、候选页处理和批量关联读取；`hasMore` 已由数据层结果传递到 `SearchKnowledgeTool`，相关 self-test 通过。
- 删除任务快照时已经补充删除对应 `knowledge_sources` 的逻辑。

### 仍未通过的阻塞问题

#### A. 高：数据库替换在进程崩溃窗口中仍可能丢失正式库

位置：[KnowledgeDatabase.java:2385-2392](../src/main/java/io/ctyx/modpedia/search/KnowledgeDatabase.java#L2385)

当前替换仍是分两步移动：先把正式库移动到 `.previous`，再把 staged 库移动到正式路径。若进程在两次移动之间崩溃或断电，目录中可能只剩 `knowledge.db.previous`，而启动流程没有自动恢复该备份。因而 v8 所要求的“失败保留旧库”仍不是崩溃安全的原子状态机。

**验收结论**：Finding 1 仅部分修复，不能通过。

#### B. 中：新库安装后清理失败会产生错误的“保留旧库”报告

位置：[KnowledgeDatabase.java:2393-2397](../src/main/java/io/ctyx/modpedia/search/KnowledgeDatabase.java#L2393)、[KnowledgeCompiler.java:161-165](../src/main/java/io/ctyx/modpedia/knowledge/KnowledgeCompiler.java#L161)

如果新库已经安装成功，但删除 `.previous` 或 sidecar 失败，catch 不会恢复旧库，因为正式库仍然存在；异常却继续上抛，并被编译器记录为“保留上一版本”。日志/状态与实际数据库不一致。

**验收结论**：数据库替换失败语义仍需修正。

#### C. 高：Wiki 导入失败仍可能删除上一份有效 Wiki 索引

位置：[KnowledgeCompiler.java:339-365](../src/main/java/io/ctyx/modpedia/knowledge/KnowledgeCompiler.java#L339)、`KnowledgeDatabase` Wiki 同步逻辑

`loadWikiSources()` 在 `source.json` 损坏、Markdown 解析异常或其他 `IOException`/`RuntimeException` 时只记录 warning，不回填上一轮有效的 Wiki `DocumentInput`，后续同步仍可能删除旧 Wiki 来源并用不完整输入替换数据库。一次损坏的 Wiki 文件可能导致整体构建“成功返回”但旧 Wiki 查询为空。

**验收结论**：缓存保护未通过。应保留上一轮有效 Wiki，或将本次 build 标记为 incomplete/failed 并禁止替换。

#### D. 高：旧版 FTB Quests 运行时快照仍可能参与查询

位置：[TaskKnowledgeStore.java:429](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L429)，静态快照清理逻辑约在 `syncStaticSnapshots()` 和 `278-280`

静态同步只处理 `source_key LIKE 'ftbquests:static:%'`，旧适配器产生的 `ftbquests:<world>` 快照不会被清理；查询也未限制只读取当前静态快照。升级后旧快照可能和新静态章节同时返回，造成重复 quest、旧定义或错误的 `hasSnapshots()` 结果。

**验收结论**：旧快照迁移/隔离未通过。

### 仍需跟踪的正确性问题

#### E. 中：任务运行状态没有按 source/scope 隔离

位置：[TaskKnowledgeStore.java:448](../src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java#L448)，以及后续 `toResult()`、`PageContext.isCompleted()`

`TaskRuntimeSnapshot` 带有 `sourceKey/scopeKey`，但 started/completed quest ID 和 task progress 的匹配仍主要按 `quest_id/task_id`，没有把运行时来源约束到对应静态快照。多个任务来源存在相同 ID 且未指定 collection filter 时，一个来源的状态可能污染另一个来源。

**建议**：使用 `sourceKey + scopeKey + questId/taskId` 等稳定复合键，或在查询 SQL/状态上下文中明确约束快照来源。

### 测试覆盖缺口

以下不是当前已确认的生产故障，但应补充：

1. [WorkerIpcSelfTest.java:181-192](../src/test/java/io/ctyx/modpedia/worker/WorkerIpcSelfTest.java#L181) 的发布 JAR 检查只匹配 Gson，没有验证 SLF4J 不被重新嵌入。
2. [ItemCatalogSchedulingSelfTest.java](../src/test/java/io/ctyx/modpedia/client/ItemCatalogSchedulingSelfTest.java) 没有真正模拟语言切换、tick 和异步重新捕获，只重复了相同条件调用。
3. 尚未覆盖数据库替换中途崩溃、启动恢复 `.previous`、sidecar 清理失败以及 Wiki 损坏后保留旧索引。
4. 尚未覆盖旧版 `ftbquests:<world>` 快照升级后的清理，也未覆盖不同 source/scope 下相同 quest ID 的运行时状态隔离。

### 当前验收结论

```text
编译：通过
单进程完整测试：通过
Finding 2 pending rebuild：基本通过
Finding 3 客户端 tick 写库：主要路径通过
Finding 4 Wiki 网络大小/缓存保护：网络层通过，Wiki 导入失败保护未通过
Finding 5 SQL 分页与 hasMore：主体通过，source/scope 隔离未通过
Finding 1 数据库替换：部分修复，未通过崩溃恢复验收
```

因此，Codex 本轮修复**不能标记为 Findings 1–5 全部通过**。建议下一轮优先处理 C、D、E，再补 A/B 的恢复状态机和回归测试；完成后由本审查会话重新验收。

## Codex 后续修复验收（当前工作树追加）

### 本轮范围

本轮继续针对本文件末尾的审查结论处理，未提交、未推送，也未调用真实模型。工作区
本来就包含大量前序未提交改动，本节只记录本轮实际核对和修改的结果。

### 已处理事项

- `KnowledgeCompiler.CompileResult` 新增 `databaseSynchronized`/`successful`，SQLite
  同步失败不再被 Worker 发布为 `COMPLETED`。
- `WorkerKnowledgeService.BuildResult` 增加成功状态和失败原因；知识库或静态任务同步
  不完整时由 `WorkerServer` 发送 `ERROR`，避免启动门禁误以为构建完成。
- Worker Token 移除 `--token` 命令行回退，只接受 `MODPEDIA_WORKER_TOKEN` 环境变量；
  发布 JAR 自测同时检查 Gson 和 SLF4J API 不会被嵌入。
- 正式 SQLite 安装后增加 `isUsable(database)` 校验，校验失败时仍按备份路径处理；
  数据库恢复、staged 残留和正式库优先语义已有回归覆盖。
- 任务运行时匹配增加 `collectionIds` 约束，即使运行时 `source_key` 精确命中，也不能
  越过当前查询的来源集合；旧版运行时快照和对应孤立来源清理仍只允许静态任务查询。

### 本轮验证

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home \
./gradlew --offline test --no-configuration-cache --no-daemon --console=plain
BUILD SUCCESSFUL，42 actionable tasks

JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home \
./gradlew --offline build --no-configuration-cache --no-daemon --console=plain
BUILD SUCCESSFUL，42 actionable tasks

JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home \
./gradlew --offline knowledgeFtsPerformanceSelfTest \
  --no-configuration-cache --no-daemon --console=plain
BUILD SUCCESSFUL；400 docs、1200 segments，p95 3.46 ms

git diff --check
通过
```

定向回归 `knowledgeDatabaseSelfTest`、`taskKnowledgeStoreSelfTest`、
`workerIpcSelfTest` 也通过；Worker IPC p95 约 `0.115 ms`，20,000 条物品目录夹具同步
约 `129.59 ms`。完整测试中的 Wiki 超限、未知 Content-Length、畸形 UTF-8 和 HTTP 503
缓存保护用例均通过。

### 当前仍不能宣称全部通过的事项

- 数据库文件系统级断电/进程硬杀的真实崩溃窗口没有在本地强制注入验证；当前证据是
  staged/备份/marker 的确定性自测，不等于真实断电测试。
- Wiki 导入失败现在会终止本次知识库编译并保留旧 SQLite；未建立独立的“旧 Wiki
  文档回填到下一次构建输入”的缓存副本，因此若调用方绕过编译器直接清理来源，仍需
  额外集成测试约束。
- Dedicated Server、真实大型整合包、真实客户端 GUI 和用户配置模型仍需要人工回归。
- 当前仓库分支仍未提交或推送；本节不是发布结论。

## Codex 风险点修复复核（2026-08-14）

本节以当前工作树代码为准，补充上一节列出的 A–E 风险点。历史 finding 的原始
描述保留不变；本节不是提交或发布结论。

### A/B：SQLite 替换与恢复

- 正式库不再先移动到 `.previous`。当正式库可用时，先复制完整数据库 bundle 并
  通过 `isUsable()` 校验，再写入 `backup-ready` marker，最后替换 staged 主文件。
- `preparing`、`backup-ready`、`sidecars-cleared`、`main-installed`、`validated`、
  `installed` 状态均有启动恢复分支；正式库在 `validated/installed` 状态可用时
  优先保留新库，不因残留 `.previous` 回滚。
- bundle 处理覆盖 `-wal`、`-shm` 和默认 DELETE journal 的 `-journal`；无 marker
  的 `knowledge-*.db.tmp`/`knowledge-restore-*.db.tmp` 也会在启动时清理。
- 安装后的 `.previous`、sidecar 或 marker 清理属于善后操作，失败不会再让上层
  把已校验的新库报告成“保留旧库”。

确定性自测已覆盖：正式库缺失从 `.previous` 恢复、替换 marker 中断、只剩备份、
`validated/installed` 保留新库、残留 rollback journal 和无 marker 临时库清理。

### C：Wiki 不完整输入保护

`WikiSourceLoader.LoadResult.complete()` 为 false，或 Markdown/UTF-8 导入抛错时，
`KnowledgeCompiler.loadWikiSources()` 直接终止本次编译；不会调用 `KnowledgeDatabase.sync()`，
因此上一份正式 SQLite/Wiki FTS 不会被部分输入删除。已加入损坏 `source.json` 和
非法 UTF-8 Markdown 的回归测试。

### D：旧版 FTBQ 运行时快照

- 静态任务查询、`hasSnapshots()` 和分页 SQL 均只读取 `ftbquests:static:%`。
- Worker 重建和静态任务同步事务会删除旧版 `ftbquests:<world>`/`task:*` 运行时
  快照及孤立来源；当前查询进度不再写入 `knowledge.db`。
- 旧运行时快照即使暂时残留，也不会参与静态任务结果；清理操作在后续 Worker
  重建时完成。

### E：source/scope 与 task 进度隔离

- 精确 source、scope、collection 过滤必须同时成立；source 命中但 scope/collection
  冲突时返回空绑定。
- 未能精确绑定时，quest ID 只有在候选静态来源中唯一才可绑定；task ID 还必须属于
  已唯一绑定的父 quest。不能因为 task ID 在某一来源中恰好唯一，就绕过重复 quest
  ID 的来源歧义。
- 新增回归覆盖“相同 quest ID、不同 task ID”的跨来源污染场景。

### 当前验证结果

```text
./gradlew --offline test --no-configuration-cache --no-daemon --console=plain  BUILD SUCCESSFUL
./gradlew --offline build --no-configuration-cache --no-daemon --console=plain  BUILD SUCCESSFUL
./gradlew --offline knowledgeFtsPerformanceSelfTest \
  --no-configuration-cache --no-daemon --console=plain  BUILD SUCCESSFUL，p95 3.61 ms
git diff --check  通过
```

完整测试为 42 个 actionable tasks；Worker IPC 夹具 p50 `0.068 ms`、p95 `0.109 ms`、
p99 `0.146 ms`，20,000 条物品目录同步约 `116.38 ms`。本轮没有调用真实模型。

### 剩余验证边界

- 未执行真实文件系统断电/硬杀，只完成确定性 marker/备份恢复测试。
- ATM10/大型整合包真实客户端 FPS、Dedicated Server、FTBQ/JEI/Jade 实机 API、手册
  跳转、用户配置模型仍需人工回归。
- 当前工作树仍未提交、未推送，不能据此宣称合并或发布完成。
