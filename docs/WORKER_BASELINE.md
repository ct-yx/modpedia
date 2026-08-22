# Worker 基线与兼容层

本文件定义独立 Worker 与 Minecraft 客户端适配层之间的稳定边界。当前基线为
`worker-baseline-1`，只适用于本仓库当前的 NeoForge 1.21.1 客户端适配层；它不是
新的运行库目录，也不替代 `config/modpedia/runtime/` 的实例级状态。

## 1. 当前基线

| 项目 | 值 |
| --- | --- |
| Worker 基线 | `worker-baseline-1` |
| Worker API level | `1` |
| JSONL 协议 | `WorkerProtocol.VERSION = 1` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.244` |
| Java | `21` |
| SQLite JDBC | `3.53.2.1` |
| LangChain4j | `1.18.1` |
| LangChain4j Community SQL | `1.18.0-beta28` |
| Gson | `2.11.0`，由游戏运行时提供 |
| SLF4J API | `2.0.9`，由 NeoForge 运行时提供 |
| 客户端适配层 | `neoforge-1.21.1` |

共享依赖库位于：

```text
~/.modpedia/worker/lib/worker-baseline-1/
```

同一基线可以被不同 ModPedia 版本和不同游戏实例复用。实例自己的 Worker JAR、
IPC 载荷、日志、会话和知识库仍然位于实例运行目录，不放入共享 `lib`。

## 2. 兼容规则

```text
Worker 依赖或协议发生不兼容变化 → 递增基线编号
Worker API level 发生不兼容变化   → 递增 API level，并通常递增基线编号
Minecraft 版本变化，但 Worker API、协议和依赖不变 → 可以复用同一基线
客户端适配层变化                  → 只更新 client_adapter，不自动递增 Worker 基线
```

如果新客户端需要 Worker 尚不存在的能力，应先增加握手能力字段并完成双方实现；
删除或改变已有能力语义时不能只修改字符串，必须更新 API level 和迁移说明。

## 3. 握手边界

客户端发送 `hello` 时包含：

```json
{
  "protocol_version": 1,
  "worker_api_level": 1,
  "worker_baseline": "worker-baseline-1",
  "client_adapter": "neoforge-1.21.1",
  "client_java": "21",
  "client_capabilities": ["chat", "knowledge_rebuild", "knowledge_items_sync"]
}
```

Worker 返回 `hello_ack` 时包含：

```json
{
  "accepted": true,
  "worker_api_level": 1,
  "worker_baseline": "worker-baseline-1",
  "worker_java": "21",
  "worker_capabilities": ["chat", "knowledge_rebuild", "knowledge_items_sync"]
}
```

实际能力列表以 `WorkerCompatibility.CAPABILITIES` 为准。握手失败只返回协议、
认证或兼容性原因，不返回 Token、API Key、请求正文或完整环境路径。

对应代码边界：

```text
io.ctyx.modpedia.compat.WorkerCompatibility
io.ctyx.modpedia.protocol.WorkerProtocol
io.ctyx.modpedia.worker.WorkerMain
io.ctyx.modpedia.worker.WorkerServer
io.ctyx.modpedia.protocol.WorkerPayloadCodec
io.ctyx.modpedia.api.ChatMessage
io.ctyx.modpedia.api.MessageRole
io.ctyx.modpedia.api.SourceReference
io.ctyx.modpedia.api.ConversationSummary
```

## 4. Worker Core 约束

Worker 代码和传输模型不得引入：

```text
net.minecraft.*
net.neoforged.*
io.ctyx.modpedia.client.*
```

客户端只负责 Minecraft/NeoForge 适配、注册表和 UI；Worker 负责 AI 编排、SQLite/FTS、
知识库构建、任务文件解析、会话和协议服务。跨边界只传 JSONL 和纯 Java DTO，避免
把游戏对象、Screen、Level 或 ItemStack 传入 Worker JVM。

当前第一阶段先完成 API/DTO/协议边界，后续可以继续把纯 Java 的检索、知识库和任务
解析类整理到 Worker Core 包；不能为了移动目录而复制出第二套实现。

## 5. 迁移验证矩阵

| 客户端适配层 | Worker 基线 | Java | 状态 | 必须验证 |
| --- | --- | --- | --- | --- |
| NeoForge 1.21.1 | `worker-baseline-1` | 21 | `[~]` | 编译、独立启动、握手、SQLite/FTS、Mock AI、知识扫描、任务文件读取 |
| 未来 Minecraft 版本 + 新适配层 | `worker-baseline-1` | 21 | `[ ]` | 仅在协议、API 和依赖未变化时复用；补客户端回归 |
| 未来 Worker API/依赖变化 | `worker-baseline-2+` | 21 | `[ ]` | 新旧基线隔离、迁移说明、IPC 全链路回归 |

状态含义：

```text
[x] 已完成自动化验证
[~] 已实现但需要目标游戏/整合包人工回归
[ ] 尚未开始
[-] 暂不适用
```

## 6. 验证矩阵

详细的 W1–W5、C1/C2 和未来版本适配层矩阵见
[WORKER_VERIFICATION_MATRIX.md](WORKER_VERIFICATION_MATRIX.md)。当前客户端适配层仍需真实
`runClient` 和 Dedicated Server 证据，不能只用 Worker 自测标记为完全验证。

## 7. 验收命令

Worker 变更必须先由具体游戏版本对话生成 `[WORKER_CHANGE_REQUEST]`，由本仓库完成
Worker 修改和验证，再为每个游戏版本生成 `[WORKER_ADAPTER_UPDATE]`。详细字段和回传格式见
[WORKER_CHANGE_PROTOCOL.md](WORKER_CHANGE_PROTOCOL.md)。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew workerCoreBoundarySelfTest
./gradlew workerCompatibilitySelfTest
./gradlew workerIpcSelfTest
./gradlew test
./gradlew build
git diff --check
```

`workerIpcSelfTest` 使用最终 JAR 启动独立 JVM，不调用真实模型、不访问外网；真实模型
回归仍使用用户配置的低成本模型。只有完成编译、独立 Worker、IPC 握手、SQLite/FTS、
知识扫描和任务文件读取后，才能把矩阵中的 `[~]` 改为 `[x]`。
