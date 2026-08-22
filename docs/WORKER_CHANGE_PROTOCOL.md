# Worker 修改与版本适配协议

本文是主仓库中 Worker 变更的入口。完整的 Worker Core 实现位于独立 Worker 仓库；
Minecraft 版本分支只维护客户端适配层，不复制 Worker 业务逻辑。

## 1. 什么时候需要走本协议

以下改动属于 Worker 改动，必须先生成变更摘要，再交给 Worker 仓库处理：

- Worker IPC JSONL 字段、请求/响应语义或握手规则变化；
- SQLite、FTS、知识库构建、任务静态导入或 AI 工具编排变化；
- Worker 依赖、Java 运行时要求或共享库内容变化；
- Worker 线程并发、缓存、超时、降级或持久化策略变化。

只改 Minecraft UI、注册表、Tooltip、Jade/JEI/FTBQ 客户端入口，且不改变协议和
纯 Java DTO 时，不需要复制 Worker 代码；完成客户端适配测试即可。

## 2. 版本对话 → Worker 对话

版本对话提出改动时，先输出以下摘要并发送给 Worker 对话：

```text
[WORKER_CHANGE_REQUEST]
基线：worker-baseline-1
API level：1
协议版本：WorkerProtocol.VERSION
目标：<一句话说明需要的 Worker 能力>
涉及接口：<JSONL 请求/响应或纯 Java DTO>
兼容要求：<必须保持的旧行为>
降级行为：<Worker 不可用、能力缺失或超时后的行为>
验收：<纯 Java/Worker 测试、SQLite/FTS、Mock AI 或 IPC 测试>
不涉及：Minecraft UI、发布网页、Release 和 API Key
[/WORKER_CHANGE_REQUEST]
```

Worker 对话完成修改后，必须返回以下适配摘要：

```text
[WORKER_ADAPTER_UPDATE]
基线：worker-baseline-<n>
API level：<数字>
协议版本：<数字>
能力集合：<能力名列表>
协议变化：<无，或列出新增/删除/语义变化>
客户端适配：<具体版本需要修改的入口和 DTO>
降级行为：<Worker 不可用或旧能力下的行为>
验证：<执行过的命令和结果>
[/WORKER_ADAPTER_UPDATE]
```

版本对话只根据 `WORKER_ADAPTER_UPDATE` 修改自己的适配层，并验证实际游戏环境。
如果协议或依赖发生不兼容变化，必须使用新的 `worker-baseline-N`；只替换 Minecraft
客户端版本、而 Worker API、协议和依赖不变时，可以继续复用当前基线。

## 3. 当前基线边界

| 项目 | 当前值 |
| --- | --- |
| Worker 基线 | `worker-baseline-1` |
| API level | `1` |
| 协议 | `WorkerProtocol.VERSION = 1` |
| 共享库 | `~/.modpedia/worker/lib/worker-baseline-1/` |
| 实例运行目录 | `config/modpedia/runtime/worker/` |
| 当前客户端适配 | NeoForge 1.21.1 / Java 21 |

共享库内容改变时先更新基线和清单，再修改客户端启动器。不能在不改编号的情况下覆盖
已有基线目录，也不能把共享库、API Key、会话或运行日志提交到仓库。

## 4. 验收顺序

```text
Worker 纯 Java 边界与单元测试
  → Worker 独立 JVM 启动和 IPC 握手
  → SQLite/FTS、知识构建、任务和 AI Mock
  → 具体 Minecraft 版本的客户端适配
  → Dedicated Server 和可选联动回归
  → main 分支统一更新 README、docs、CHANGELOG、Release 和 Pages
```

发布文档和 GitHub Pages 只由主仓库 `main` 维护，具体版本分支和 Worker 仓库不要复制
发布工作流或网页。发布边界见 [RELEASE_AND_PAGES.md](RELEASE_AND_PAGES.md)，基线细节见
[WORKER_BASELINE.md](WORKER_BASELINE.md)。
