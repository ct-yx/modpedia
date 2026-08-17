# ModPedia 维护者交接入口

给接手修复的开发者：先按以下顺序阅读。

1. [项目入门](PROJECT_ONBOARDING.md)
2. [Worker IPC 与物品目录卡顿报告](BUG_REPORT_WORKER_IPC_AND_ITEM_CATALOG.md)
3. [Worker 与卡顿问题修复指引](WORKER_FIX_GUIDE.md)

## 当前结论

本分支已完成上一轮 P0 代码修复的主要部分：

- Worker 使用固定 IPv4 loopback，认证令牌通过环境变量传递，不出现在命令行参数中。
- 启动等待、知识库重建、SQLite/FTS5、AI 和会话持久化在独立 Worker JVM 执行。
- 物品 Tooltip 只在进入主菜单前捕获一次；进入世界后的 Tick 不会启动全量扫描。
- 物品目录通过原子 JSONL 载荷和 Worker 短事务写入，失败时保留旧目录。
- Schema v7 采用 staged 数据库替换；替换失败会尝试恢复旧数据库并清理 WAL/SHM。
- Worker 知识库操作使用串行队列；重复请求合并为一个最新 pending 操作，不会把 Wiki 更新静默丢弃。
- FTB Quests 静态定义写入 `knowledge.db`，实时进度按任务问题读取并只存在于请求内存。
- SQLite staged 替换使用已校验备份和 `replace-state` 恢复；正式库安装后的备份清理
  失败不会被误报为同步失败，并覆盖 WAL/SHM/DELETE journal 旁路文件。
- Wiki 来源解析不完整时会终止本次构建，不执行 SQLite 替换；旧索引继续可读。
- 未能精确绑定任务来源时，运行时 task 进度必须先通过父 quest 的唯一来源约束，不能
  只凭一个恰好唯一的 task ID 绑定到其它任务书。
- `RetrievalService` 可显式关闭 SQLite 只读连接，世界/会话切换时不应遗留旧连接。

当前仍需人工验证：真实大型整合包启动时间与 FPS、GUI Scale 4 下 UI、三种手册真实跳转、
FTB Quests/JEI/Jade 的实际版本 API，以及 Dedicated Server 启动。自动化测试通过不等于这些
图形和外部模组回归已经完成。

当前工作区存在未提交改动，提交或合并前应先保存 diff，并只暂存本任务相关文件。

## 最新风险修复验证（2026-08-14）

```text
./gradlew --offline test --no-configuration-cache --no-daemon --console=plain  通过
./gradlew --offline build --no-configuration-cache --no-daemon --console=plain  通过
./gradlew --offline knowledgeFtsPerformanceSelfTest \
  --no-configuration-cache --no-daemon --console=plain  通过，p95 3.61 ms
git diff --check  通过
```

验证覆盖：Wiki 损坏/非法 UTF-8 保留旧索引、数据库缺失和替换中断恢复、残留
`knowledge.db.previous-journal` 清理、无 marker 临时库清理，以及相同 quest ID 下
不同 task ID 的跨来源进度隔离。

仍需真实运行环境验证：断电/硬杀、ATM10 大型整合包 FPS、Dedicated Server 和
可选联动模组的实际 API 跳转。当前没有提交或推送本轮改动。

## Worker 并发风险修复（2026-08-14）

- `WorkerServer` 的 AI 请求使用 `WorkerAiExecutor`，默认并发上限为 2、队列上限为 4；
  超限返回 `code=WORKER_BUSY`，知识库单线程写入继续保持独立。
- `ConversationRequestGate` 按会话限制活动 AI 请求，避免两个回合交错读写同一历史。
- `RuntimeContextCoordinator` 使用单线程有界队列、同聊天请求单飞、750 ms 快照缓存和
  6 秒过期取消；Bridge 在世界切换、连接断开和退出时清理所有 waiter。
- `ModPediaBridge.addRawListener` 只给 IPC reader 线程使用，`addClientListener` 统一在
  Minecraft 线程触发；设置页和 Worker 会话已使用后者。

本轮新增纯 Java 回归：

```text
runtimeContextCoordinatorSelfTest
workerConcurrencySelfTest
```
