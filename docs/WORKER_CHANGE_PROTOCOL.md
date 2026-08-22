# Worker 变更与版本适配流程

本文件规定 Worker 和各 Minecraft 游戏版本之间的协作方式。目标是让 Worker
只维护一份实现，各游戏版本只维护自己的客户端适配层，避免在多个版本仓库中复制
Worker 代码后产生行为漂移。

## 1. 什么情况必须走 Worker 流程

满足任一条件时，视为 Worker 变更：

- 修改 `worker/`、`protocol/`、`api/`、`knowledge/`、`search/`、`task/`、`recipe/`
  中被独立 Worker 使用的代码；
- 修改 Worker JSONL 消息、握手字段、能力集合、DTO 或错误码；
- 修改 Worker 的 SQLite/FTS、AI 编排、任务文件读取或知识库导入行为；
- 修改 Worker 的依赖、Java 运行要求、打包方式或共享 `lib` 内容；
- 修改会影响 `worker-baseline-*` 兼容判断的配置。

只修改某个游戏版本的 UI、注册表、客户端事件、可选联动或客户端适配代码，且不改变
Worker 输入输出时，不需要走 Worker 修改流程。

## 2. 两阶段流程

### 阶段 A：游戏版本对话提交 Worker 请求

发现问题的具体游戏版本对话不直接修改 Worker。先把事实压缩为一份短摘要，并把下面
的 Prompt 发给 Worker 对话：

```text
[WORKER_CHANGE_REQUEST]
来源版本：<Minecraft/Loader/Java/版本仓库>
问题：<用户可见问题和可复现步骤>
目标：<希望 Worker 提供的能力或修复结果>
当前行为：<现有 Worker 返回、错误或延迟>
期望行为：<明确的输入、输出和失败降级>
影响范围：<协议/DTO/知识库/任务/AI/依赖/性能，可多选>
已知约束：<不可改动的客户端逻辑、兼容基线、性能要求>
验证材料：<测试输出、脱敏日志、最小夹具；不得包含 API Key/Token>
版本适配提示：<需要哪些客户端回调、事件或字段>
[/WORKER_CHANGE_REQUEST]
```

摘要只描述事实和需求，不粘贴完整日志、不包含 API Key、Token、会话正文或个人路径。
如果问题最终判断为客户端适配层问题，Worker 对话应返回“无需修改 Worker”的结论，
再由原版本对话自行修复。

### 阶段 B：Worker 对话实现并回传版本 Prompt

Worker 对话收到请求后负责：

1. 先检查 Worker 仓库状态和当前 `worker-baseline-*`；
2. 对比现有协议、DTO、能力集合和测试，再修改最小范围；
3. 为 Worker Core 增加或更新纯 Java 自测，禁止引入 `net.minecraft.*`、
   `net.neoforged.*` 和 `client.*`；
4. 只有依赖、协议或 API 发生不兼容变化时才递增基线编号；
5. 运行 Worker 自测、完整测试、构建和 `git diff --check`；
6. 生成一份给每个具体游戏版本对话的适配 Prompt。

Worker 对话的交付格式：

```text
[WORKER_CHANGE_RESULT]
结论：<已修改 Worker / 无需修改 Worker / 被阻塞>
摘要：<一到三句话说明行为变化>
基线：<worker-baseline-N>
API level：<数字>
协议变化：<无；或列出新增/删除/变更字段>
能力变化：<无；或列出能力名>
代码提交：<commit/ref；未提交时明确写明>
验证：<实际执行的命令和结果>
安全边界：<确认没有输出 API Key/Token>
[/WORKER_CHANGE_RESULT]
```

### 阶段 C：给具体游戏版本的适配 Prompt

Worker 修改完成后，为每个需要适配的版本分别生成 Prompt；不要让版本对话自行猜测
Worker 的接口变化：

```text
[WORKER_ADAPTER_UPDATE]
目标版本：<Minecraft/Loader/Java>
Worker 基线：<worker-baseline-N>
Worker API level：<数字>
Worker 版本引用：<commit/ref>
变更摘要：<Worker 新增或修复的能力>
协议更新：<字段、消息、错误码或握手变化；无变化写“无”>
客户端需要做：<握手字段、DTO、回调、兼容分支、UI 展示>
客户端不应做：<不要复制 Worker 逻辑，不要在主线程执行 Worker 工作>
降级行为：<Worker 不可用、能力缺失或旧版本时的行为>
验证命令：<目标版本仓库的测试、构建、客户端/服务端回归>
[/WORKER_ADAPTER_UPDATE]
```

具体版本对话只修改自己的适配层和测试。若发现 Worker 仍缺少能力，停止继续复制逻辑，
重新生成 `[WORKER_CHANGE_REQUEST]` 返回阶段 A。

## 3. 基线和发布规则

- 同一 `worker-baseline-N` 的不同 ModPedia 版本和游戏实例共享同一套用户级 lib。
- Minecraft 版本变化但 Worker API、协议和依赖不变时，版本适配层可以继续使用原基线。
- Worker 依赖、协议或 API 不兼容时递增基线，并在结果 Prompt 中写明迁移原因。
- 版本仓库不得私自修改共享 Worker lib，也不得把另一版本的 Worker JAR 当作适配层提交。
- Worker 改动默认先留在 Worker 分支；是否提交、推送、合并或发布必须单独明确。

## 4. 最小验收清单

Worker 侧至少完成：

```text
./gradlew workerCoreBoundarySelfTest --no-daemon
./gradlew workerCompatibilitySelfTest --no-daemon
./gradlew workerIpcSelfTest --no-daemon
./gradlew test --no-daemon
./gradlew build --no-daemon --no-configuration-cache
git diff --check
```

具体游戏版本侧至少确认：

- 新旧握手均符合当前 Worker API level 和基线；
- Worker 运行在独立 JVM，客户端主线程不执行知识库、AI 或文件批处理；
- 无 Worker、旧基线、缺少可选能力时可以降级进入游戏；
- Dedicated Server 不解析客户端适配类；
- 日志、诊断和 Prompt 中没有 API Key、Token 或完整会话正文。
