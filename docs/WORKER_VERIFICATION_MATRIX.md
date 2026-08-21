# Worker 兼容验证矩阵

本矩阵记录 Worker Core 与 Minecraft 客户端适配层的验证边界。它只描述 Worker 的
兼容性，不把 Minecraft 版本号直接当作 Worker 基线号。

## 状态标记

```text
[x] 已有自动化证据
[~] 已构建或实现，但还需要目标游戏实例人工回归
[ ] 尚未开始
[-] 不适用
```

## 当前矩阵

| ID | 目标 | Worker 基线 | Java | 必须证据 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| W1 | Worker Core 纯 Java 边界 | `worker-baseline-1` | 21 | 源码边界、协议版本、共享 lib 常量 | `[x]` |
| W2 | 独立 Worker 启动与 IPC 握手 | `worker-baseline-1` | 21 | Jar-in-Jar 启动、正确握手、错误 Token/协议/基线拒绝 | `[x]` |
| W3 | SQLite/FTS 与本地 AI Mock | `worker-baseline-1` | 21 | 知识库重建、FTS 查询、Mock 请求和会话重启 | `[x]` |
| W4 | Worker 知识扫描 | `worker-baseline-1` | 21 | JAR 扫描、语言回退、文档导入和来源统计 | `[x]` |
| W5 | 任务存档读取 | `worker-baseline-1` | 21 | FTBQ 静态导入、运行时文件读取、p50/p95/p99 | `[x]` |
| C1 | NeoForge 1.21.1 客户端适配层 | `worker-baseline-1` | 21 | `build`、`runClient`、UI/注册表/可选联动回归 | `[~]` |
| C2 | NeoForge 1.21.1 Dedicated Server | `worker-baseline-1` | 21 | `build`、`runServer`、不解析客户端类 | `[~]` |
| F1 | 未来 Minecraft 版本的新客户端适配层 | `worker-baseline-1` 或递增 | 21 | 新适配层编译、握手、客户端/服务端组合回归 | `[ ]` |

W1–W5 可以在无真实模型、无外网的环境完成；C1/C2 不得用纯 Java 自测代替真实游戏
证据。F1 只有在目标版本明确后建立独立分支和矩阵行。

## W1–W5 的证据映射

| 验证项 | 命令 | 产出/覆盖内容 |
| --- | --- | --- |
| W1 | `./gradlew workerCoreBoundarySelfTest` | 检查 Worker、协议、API、知识、检索、任务、配方、存储和 Worker 使用的 AI 类没有直接引用客户端/加载器类 |
| W2 | `./gradlew workerCompatibilitySelfTest workerIpcSelfTest` | 独立 JVM、API level、基线、能力集合、错误握手、SQLite 和会话恢复 |
| W3 | `./gradlew knowledgeDatabaseSelfTest aiLangChainSelfTest workerIpcSelfTest` | SQLite/FTS、工具调用和本地 HTTP Mock 链路 |
| W4 | `./gradlew workerGuideScannerSelfTest workerIpcSelfTest` | 纯 JDK JAR 扫描、来源分类和 Worker 导入 |
| W5 | `./gradlew workerTaskStaticImporterSelfTest workerTaskRuntimeFileSelfTest` | 静态任务表与运行时任务文件分离，进度不写回知识库 |

## 客户端适配层证据要求

C1 至少要在目标实例中记录：

```text
1. K 打开助手并完成 Worker 握手
2. 小窗口/GUI Scale 下 UI 正常
3. 物品目录导入不阻塞主线程
4. 手册来源、配方和视线目标可选联动缺失时仍能启动
5. 真实模型请求、取消、重试和历史恢复
```

C2 至少要记录：

```text
1. Dedicated Server 启动成功
2. 日志没有 AssistantScreen、Minecraft client 或可选客户端 API 类加载失败
3. Worker 可以独立启动或在客户端不存在时安全退出
4. 核心配置、知识库和任务静态数据路径可读
```

## 复用基线决策

```text
Worker API、JSONL 协议和嵌入依赖都不变
    → 新客户端适配层继续使用 worker-baseline-1

Worker API 或协议不兼容
    → 递增 API level，并创建 worker-baseline-2+

Worker 嵌入依赖增删/升级
    → 递增 worker-baseline-2+

只有 Minecraft/NeoForge 客户端 API 变化
    → 新增 client_adapter，Worker 基线不变
```

每次基线变化都要同步更新：

```text
docs/WORKER_BASELINE.md
docs/WORKER_VERIFICATION_MATRIX.md
src/main/java/io/ctyx/modpedia/compat/WorkerCompatibility.java
ModPediaPaths.workerLibraryRoot()
```
