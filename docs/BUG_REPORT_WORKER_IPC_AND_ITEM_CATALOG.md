# Worker IPC 与物品目录卡顿报告

> 面向维护者的可复现故障报告。本文记录的是 2026-08-12 对开发实例的检查结果，基于当前工作区快照。

## 1. 基本信息

| 项目 | 值 |
| --- | --- |
| 项目 | ModPedia · 模组百科 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.244 |
| 游戏 JVM | Eclipse Adoptium OpenJDK 21.0.12 |
| 分支 | `agent/full-maintenance-checkpoint` |
| 检查时 HEAD | `406a205` |
| 实例目录 | `/Users/chenhong/Documents/modpedia/run` |
| 复现整合包 | 当前开发实例，20+ 个模组 |
| 严重级别 | P0：启动阻塞 + 进入世界后持续掉帧 |

当前工作区包含大量尚未提交的 Worker、物品目录、AI 和文档改动。修复时先保留现状，建立单独 checkpoint，再逐步修改。

## 2. 摘要

故障由两个连续缺陷组成：

```text
Worker IPv4/IPv6 地址族不一致
    ↓
Worker 三次连接失败
    ↓
启动流程在 Render thread 等待 3 × 15 秒
    ↓
Worker 未就绪
    ↓
物品目录失败状态被清空
    ↓
每个客户端 Tick 重新捕获整批 Tooltip
    ↓
进入存档后持续出现帧时间尖峰
```

SQLite 写入和 IPC 本身不是当前帧率问题的主要来源。进程级基准已经证明 Worker 处理 20,000 条物品的 IPC 与数据库同步耗时处于毫秒级。

## 3. 用户可见影响

### 3.1 启动阶段

日志显示 Worker 启动超时三次：

```text
ModPedia Worker 启动超时
ModPedia Worker 启动超时
ModPedia Worker 启动超时
Mod 'modpedia' took 45.54 s to run a deferred task.
```

证据：

```text
/Users/chenhong/Documents/modpedia/run/logs/latest.log:248-252
```

### 3.2 进入存档后

物品目录失败日志从 `01:31:29.892` 持续到 `01:33:37.215`：

```text
总次数：1985
持续时间：127.323 秒
频率：约 15.59 次/秒
```

证据：

```text
/Users/chenhong/Documents/modpedia/run/logs/latest.log
```

这段时间内客户端反复执行注册物品枚举和 Tooltip 捕获，因此会出现间歇性掉到 1 帧的现象。

### 3.3 聊天功能

Worker 连接未进入 ready 状态，聊天请求在桥接层直接进入错误回调。此时表现为发送后助手没有正常生成回答，根因位于本地 Worker 链路，而不是模型响应耗时。

## 4. 根因分析

### 4.1 IPC 地址族不一致：已锁定

游戏开发运行参数包含：

```text
-Djava.net.preferIPv6Addresses=system
```

证据：

```text
/Users/chenhong/Documents/modpedia/build/moddev/clientRunVmArgs.txt:15
```

父进程代码：

```java
// ModPediaBridge.java
new ServerSocket(0, 1, InetAddress.getLoopbackAddress())
```

位置：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java:127
```

在该 JVM 参数下，父进程的 loopback 地址解析为 `::1`。

Worker 子进程只接收了 `-Dmodpedia.worker=true`，没有继承 IPv6 偏好参数：

```java
new Socket(InetAddress.getLoopbackAddress(), port)
```

位置：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/worker/WorkerMain.java:32
```

子进程默认解析为 `127.0.0.1`，连接到父进程的 IPv6 监听端口时返回：

```text
java.net.ConnectException: Connection refused
```

证据：

```text
/Users/chenhong/Documents/modpedia/run/config/modpedia/worker/worker.log
```

这是当前开发环境连接失败的直接原因。打包 JAR 的进程级自测使用了相同地址族，因此自测通过，开发运行参数差异没有被覆盖。

### 4.2 Render thread 同步等待：已确认

启动入口：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/client/StartupKnowledgeBootstrap.java:20-31
```

该入口在 `FMLClientSetupEvent.enqueueWork` 中执行同步 Worker 启动。

桥接层：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java:96-114
```

启动策略是三次尝试，每次 `ServerSocket.accept()` 等待 15 秒。由于调用线程是 Render thread，三次失败形成 45.54 秒启动阻塞。

即使地址修复，知识库重建和完整物品目录捕获仍需避免直接使用 Render thread 长时间等待。

### 4.3 物品目录失败后无限重试：已确认

客户端 Tick 每次调用：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/ModPediaClient.java:103-109
```

扫描服务在 Worker 失败时清空 `attemptedLanguage`：

```java
if (bridge == null || !bridge.syncItems(language, entries)) {
    attemptedLanguage = "";
    return false;
}
```

位置：

```text
/Users/chenhong/Documents/modpedia/src/main/java/io/ctyx/modpedia/client/ItemCatalogSyncService.java:193-203
```

下一次 Tick 再次满足语言检查，重新进入：

```text
registryItems()
→ captureBatch(..., 128)
→ Minecraft.execute(...)
→ persist(...)
```

`capture()` 调用 Minecraft Item Tooltip API，工作仍发生在客户端线程。Worker 失败期间，这条链路反复消耗游戏帧预算。

## 5. 非主因排除

| 观察项 | 结论 |
| --- | --- |
| Java 25 | 当前游戏日志记录的是 Java 21.0.12，Java 25 不是本次实例的运行时 |
| SQLite 写入 | 20,000 条夹具同步约 138.49 ms，Worker 端不是持续掉帧来源 |
| IPC 延迟 | p50 0.100 ms、p95 0.174 ms、p99 0.306 ms |
| AI API | Worker 尚未 ready，当前问题发生在模型请求之前 |
| 第三方资源警告 | 日志中存在其他模组的资源/模型警告，但与 ModPedia 的重复扫描链路分开处理 |

## 6. 最小复现

### 6.1 开发实例

```bash
cd /Users/chenhong/Documents/modpedia
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runClient
```

复现步骤：

1. 等待客户端进入主菜单。
2. 进入一个单人存档。
3. 观察 `run/logs/latest.log` 中 `Item catalog Worker unavailable` 的重复输出。
4. 观察进入存档后帧时间尖峰。
5. 按 `K` 打开助手并发送一条测试消息，确认 Worker 未就绪时的错误路径。

### 6.2 单独验证地址族

```bash
grep -n 'preferIPv6' build/moddev/clientRunVmArgs.txt
grep -n 'ConnectException' run/config/modpedia/worker/worker.log
```

### 6.3 验证 Worker 本体

```bash
cd /Users/chenhong/Documents/modpedia
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline workerIpcSelfTest
```

当前结果：

```text
BUILD SUCCESSFUL
Worker IPC self-test passed
```

## 7. 期望行为

```text
游戏启动
  → Worker 在本地稳定握手
  → 知识库/物品目录构建进入明确状态
  → 失败时保留旧数据并进入等待重试
  → 客户端 Tick 保持稳定
  → Worker 恢复后自动继续同步
  → K 打开助手并正常提交请求
```

## 8. 验收标准

- Worker 在开发运行和打包 JAR 两种路径都能握手。
- 启动阶段 Render thread 不出现 15 秒级阻塞。
- Worker 失败后物品目录只记录一次失败状态，不进行每 Tick 重扫。
- Worker 恢复后物品目录只同步一次。
- 20+ 模组实例进入存档后，ModPedia 线程不制造持续帧时间尖峰。
- `K` 打开助手、发送、取消和重试行为正常。
- `./gradlew --offline test`、`./gradlew --offline build`、`git diff --check` 通过。
- Dedicated Server 继续保持客户端类隔离。

## 9. 相关文件清单

```text
src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java
src/main/java/io/ctyx/modpedia/client/StartupKnowledgeBootstrap.java
src/main/java/io/ctyx/modpedia/client/ItemCatalogSyncService.java
src/main/java/io/ctyx/modpedia/ModPediaClient.java
src/main/java/io/ctyx/modpedia/worker/WorkerMain.java
src/main/java/io/ctyx/modpedia/worker/WorkerServer.java
src/test/java/io/ctyx/modpedia/worker/WorkerIpcSelfTest.java
run/logs/latest.log
run/config/modpedia/worker/worker.log
```

## 10. 文档导入回归（2026-08-12）

卡顿链路修复后，开发实例出现知识库只有 1 篇文档、`generated/` 为空的现象。
只读检查得到：

```text
run/mods：18 个 JAR
当前 Worker 扫描器直接扫描：636 个手册资源
临时 SQLite 构建：636 篇文档、8 个来源、0 个警告
运行实例旧状态：1 篇文档、0 个生成 Markdown
```

因此本次故障不是 FTS5、Markdown 转换或 SQLite 导入过滤，而是运行时 Worker
实际使用的模组目录/Worker 产物与当前源码不一致。修复内容：

- Worker 记录请求目录、最终目录、JAR 数量、资源数量和文档数量；
- `mods/` 支持有限深度的 profile 子目录；
- Worker 在传入目录为空时从配置目录父级和当前工作目录回退；
- 传入目录已有 JAR 时保持客户端选择，不被其他候选目录覆盖；
- 空 JAR 目录且已有生成文档时中止构建，保留上一份知识库，避免错误路径清库；
- 客户端启动、F9 和任务 Wiki 同步使用同一套模组目录解析；
- 新增 Worker 扫描器夹具，覆盖嵌套目录、普通 JSON 排除和空目录诊断。

验证结果：

```text
Worker guide scanner self-test passed
Worker service probe: archives=18, resources=636, documents=636, warnings=0
```

真实客户端重新启动后，应在 `latest.log` 看到类似：

```text
Knowledge scan mods directory: <mods-directory>
Knowledge rebuild completed: mods=<mods-directory> archives=<n> resources=<n> documents=<n> warnings=0
```

## 11. HEAD 406a205 全量审核追加（2026-08-14）

本节记录对 `406a205d49d2399e2a7a53336869fb03a768033f` 相对父提交
`13b6af48b51cd1ea69309c67249e9a4dff67d94e` 的只读审核结果。当前工作树中后续未提交及未跟踪的 Worker/IPC 修复不属于本节范围。

### 11.1 P1：FTB Quests 快照未按玩家/世界作用域隔离

位置：

```text
src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java:60-63,105-113
src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java:143-164,483-500
```

适配器只用 `snapshot.fingerprint()` 判断是否需要同步，但 fingerprint 未包含 `scopeKey`；同时 `sourceKey` 只包含维度名。切换存档、玩家或具有相同维度的世界时，可能跳过同步并继续使用旧快照。旧式空 `snapshot_id` 进度还会作为当前快照 fallback，可能造成相同 quest/task ID 的跨快照进度污染。

复现条件：两个世界或玩家使用相同任务树，或先写入不带 `snapshotId` 的进度后切换世界。`search_tasks` 可能返回旧世界/旧玩家的 `snapshot_id`、`scope_key` 或进度。

### 11.2 P1：任务进度变化不会可靠触发快照刷新

位置：

```text
src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java:95-113,188-217,260-264
```

用于 fingerprint 的 `rawJson` 没有包含任务当前进度、完成状态和动态 `teamData` 字段。若 quest 对象的 `toString()` 不随进度变化，玩家增加数量或完成任务后 fingerprint 仍不变，后续 tick 会跳过 `syncSnapshot()`。

复现条件：玩家推进收集任务，但 quest 对象的类型和值摘要不变。之后 `search_tasks` 仍可能返回旧的 `blocked_requirement` 或未完成状态。

### 11.3 P1：FTB Quests 快照写库阻塞客户端 Tick 线程

位置：

```text
src/main/java/io/ctyx/modpedia/ModPediaClient.java:74-79
src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java:51-64
src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java:32-37
```

`ClientTickEvent.Post` 直接执行 `adapter.tick()`；fingerprint 变化时同步调用 `store.syncSnapshot(snapshot)`。该调用会初始化/打开 SQLite，并批量删除和插入 quest、依赖、task、reward。

复现条件：首次进入包含大型任务树的整合包，或任务快照发生变化。完整事务在客户端 Tick 线程运行，会造成输入延迟、帧时间尖峰，极端情况下使客户端明显暂停。

### 11.4 P2：任务 `has_more` 误报且没有真实分页

位置：

```text
src/main/java/io/ctyx/modpedia/task/TaskQuery.java:5-10
src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java:54-65,281-331
src/main/java/io/ctyx/modpedia/ai/SearchKnowledgeTool.java:440-442
```

`TaskQuery` 没有 offset/cursor/page；存储层先物化全部匹配任务，再在 Java 层截断到 `limit`。工具层使用 `quests.size() >= limit` 推断 `has_more`，因此恰好有 `limit` 条时也会报告 `true`。重复调用相同参数仍返回同一批结果，无法获得下一页，且查询成本不受 limit 控制。

复现条件：调用 `search_tasks(limit=8)`，数据库有 8 条或更多匹配任务。返回结果会显示 `has_more=true`，但没有可用的下一页位置。

### 11.5 P2：首次启动时 Wiki 同步可能未被索引

位置：

```text
src/main/java/io/ctyx/modpedia/ModPediaClient.java:63-66
src/main/java/io/ctyx/modpedia/client/TaskWikiSyncService.java:41-43,52-74
src/main/java/io/ctyx/modpedia/knowledge/KnowledgeUpdateService.java:40-53
```

初始知识库构建和 Wiki 同步分别提交到不同 executor。Wiki 文件变化后调用 `KnowledgeUpdateService.rebuildAsync()`；如果初始构建仍在运行，请求会被拒绝且没有 pending 标记、重试或后置构建。

复现条件：首次启动时初始扫描尚未完成，Wiki 文件先写入并触发 rebuild。当前构建看不到该文件，文件虽存在于磁盘，`search_wiki` 仍要等到 F9 或下一次完整构建才能查到。

### 11.6 审核验证与覆盖缺口

在干净的 `406a205` 归档副本中运行：

```text
./gradlew test --no-daemon --console=plain
BUILD SUCCESSFUL
```

目标提交的 SQLite、FTS5、任务存储、搜索工具、AI 和客户端 self-test 均通过；`git diff --check main...406a205` 也通过。上述测试没有覆盖：

- 玩家/世界/维度切换后的快照隔离；
- 仅实时进度变化时的 fingerprint 更新；
- 客户端 Tick 与后台写库边界；
- 任务结果为 0、恰好 limit、limit+1 和大于 limit 时的真实分页；
- Wiki 同步与首次知识库构建的启动竞态。

### 11.7 修复优先级

1. 将 FTB Quests 快照身份与实时进度分离，并使用玩家/世界/source/scope 复合键隔离；
2. 将 SQLite 快照写入移到有界后台 executor，处理 generation、世界切换和关闭生命周期；
3. 在 SQL/数据层实现真实分页并由数据层提供准确 `hasMore`；
4. 为知识库 rebuild 增加 pending 合并语义，确保 Wiki 变化最终触发一次后置重建。

这些问题应在合并前补充确定性回归测试；本节不表示已完成修复。

## 12. 当前工作树相对 HEAD 406a205 审核追加（2026-08-14）

本节记录当前工作树（包含已跟踪修改和未跟踪 Worker/IPC/客户端代码）相对
`406a205d49d2399e2a7a53336869fb03a768033f` 的只读审核结果。它与第 11 节的
历史提交审核分开统计；本节问题尚未修复。

### 12.1 P1：运行时任务状态被硬截断，导致进度和完成状态错误

位置：

```text
src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java:45-47,269-271
src/main/java/io/ctyx/modpedia/worker/WorkerTaskRuntimeFileReader.java:30-32,176-178
```

客户端和 Worker 文件读取路径分别将 `started`、`completed` 和
`task_progress` 限制为 32、128 和 512 项，并按 Map 迭代顺序直接 `.limit()`。
读取前没有按照当前 `TaskQuery`、静态候选任务或任务 ID 筛选。

当存档中的条目超过上限时，被截断任务不会获得运行时状态覆盖，查询会回退到
静态数据库状态。已完成任务可能显示为未完成，正在推进的任务可能显示为旧进度
或 0。这不是正常分页，而是核心 `search_tasks` 结果的错误状态。

复现条件：

1. 准备包含超过 32 个 started、128 个 completed 或 512 个 progress 条目的 FTBQ 存档；
2. 查询一个不在截断前缀中的任务；
3. 对比 FTBQ 实际状态与 `search_tasks` 返回的状态/进度。

### 12.2 P1：IPC 断线竞态可在发送路径抛出未捕获 NullPointerException

位置：

```text
src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java:1301-1314,1317-1321
src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java:965-983
```

`sendIfReady()` 先检查 `writer != null`，但 `handleConnectionLost()` 可以在随后将
`writer` 设为 `null`。发送线程获得 `writeLock` 后，`send()` 直接执行
`WorkerProtocol.write(writer, message)`；此时可能传入空 writer。

`sendIfReady()` 只捕获 `IOException`，不会捕获该空指针。心跳、普通请求或运行时
上下文响应与断线同时发生时，可能跳出预期的失败/重连路径，甚至使心跳任务异常
终止。

### 12.3 P2：取消请求 ID 在 Worker 生命周期内永久保留

位置：

```text
src/main/java/io/ctyx/modpedia/worker/WorkerRequestCancellation.java:16-27
src/main/java/io/ctyx/modpedia/worker/WorkerServer.java:319-339,147-156
```

`CHAT_CANCEL` 将请求 UUID 加入 `cancelled` 集合，但单个请求完成、取消事件发送
和终态事件处理后都不会移除。集合仅在 Worker JVM 整体退出时由 `clear()` 清空。

长时间运行的 Worker 反复取消聊天请求会使集合持续增长。请求 ID 为随机 UUID，
因此不会自然复用或收敛，最终形成无界内存占用。

### 12.4 P2：流式 AI 总超时后，迟到回调仍可修改会话并发送完成事件

位置：

```text
src/main/java/io/ctyx/modpedia/worker/WorkerChatService.java:325-360,373-384
```

流式总超时分支会取消上游并发送 `ERROR`，但没有将 `completed` 闸门置为终态，
也没有将请求加入取消状态。若上游随后触发 `onCompleteResponse`，回调仍可能通过
`completed.compareAndSet(false, true)`，调用 `finish()`、追加 assistant 消息并发送
`COMPLETED`。

客户端可能先收到超时错误、随后又收到完成事件；会话文件也可能被迟到结果修改，
造成终态和持久化状态不一致。

### 12.5 P2：本地 SNBT 路径未使用统一任务 ID 规范化

位置：

```text
src/main/java/io/ctyx/modpedia/worker/WorkerTaskRuntimeFileReader.java:182-189,197-208
src/main/java/io/ctyx/modpedia/task/FtbQuestIdCodec.java:32-59
```

TeamData 反射路径通过 `FtbQuestIdCodec.fromRuntimeKey()` 将数值、十进制字符串和
短十六进制字符串转换为 16 位大写十六进制 ID；本地 SNBT 文件路径则直接保留
Map key 字符串。

若 SNBT 中出现小写、短十六进制或十进制任务 ID，同一任务在两条读取路径中会得到
不同的 ID，无法匹配静态任务定义，表现为单机文件路径显示无进度而 TeamData 回退
路径显示有进度。

### 12.6 P2：游戏关闭与 Worker 启动之间存在重启竞态

位置：

```text
src/main/java/io/ctyx/modpedia/client/ModPediaBridge.java:122-143,146-197,538-550
```

`startBeforeMainMenu()` 的同步锁与 `shutdown()` 不一致。生命周期线程可能已经进入
`startOnce()` 并启动 Worker，随后游戏线程执行 `shutdown()`；启动流程仍可能在关闭
期间完成握手、重新设置 `ready=true` 并启动 reader/heartbeat。`startOnce()` 完成后
没有再次检查 `shuttingDown`。

退出阶段可能因此重新拉起 Worker，留下孤儿 JVM 或残留 IPC/payload 文件。该竞态
需要通过统一生命周期锁、启动阶段取消检查和关闭后的最终状态校验处理。

### 12.7 验证结果与覆盖缺口

在当前工作树执行：

```text
cd /Users/chenhong/Documents/modpedia
./gradlew test --no-daemon
BUILD SUCCESSFUL
```

现有测试没有覆盖：

- 发送与断线同时发生的 writer 竞态；
- 取消集合在长生命周期中的增长；
- 流式超时后的迟到完成回调；
- 超过运行时快照上限的真实任务数据；
- SNBT 与 TeamData 两条路径的 ID 规范化一致性；
- 游戏关闭期间启动 Worker 的竞态。

### 12.8 修复优先级

1. 修复 IPC 发送与断线竞态，并确保所有请求在连接失败时进入可控终态；
2. 取消运行时状态集合的无界生命周期，并补充终态清理；
3. 为运行时任务状态按查询/候选任务筛选，或实现不会丢失状态的有界读取策略；
4. 为 AI 流式请求增加超时终态闸门，禁止迟到回调写入会话；
5. 统一 SNBT 与 TeamData 的任务 ID 编码；
6. 串行化 Worker 启动与关闭生命周期。
