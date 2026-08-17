# Worker 与卡顿问题修复指引

本文给接手维护的开发者提供最小修复路径。目标是先解除 P0 卡顿，再完善启动、重连和物品目录状态机；当前阶段无需重写 AI、SQLite 或 UI。

## 1. 修复顺序

```text
P0-1 固定 localhost 地址族
P0-2 移除 Render thread 同步等待
P0-3 物品目录失败熔断与退避
P0-4 Worker 恢复后的补偿同步
P1-1 增加故障回归测试
P1-2 清理 common/client 包边界
P2   真实整合包与客户端回归
```

## 2. P0-1：固定 IPC 地址族

### 推荐方案

父进程和 Worker 都使用明确的 IPv4 loopback，避免受 NeoForge 开发 JVM 的 IPv6 偏好影响：

```java
private static final String LOOPBACK_HOST = "127.0.0.1";

// ModPediaBridge
try (ServerSocket server = new ServerSocket(
        0,
        1,
        InetAddress.getByName(LOOPBACK_HOST)
)) {
    // 把 server.getLocalPort() 传给 Worker
}
```

```java
// WorkerMain
try (Socket socket = new Socket(LOOPBACK_HOST, port)) {
    // 建立 JSONL IPC
}
```

也可以把 host 作为命令行参数传递，但父子进程必须使用同一个地址族。修复后在日志中记录：

```text
worker_launch host=127.0.0.1 port=<动态端口> pid=<pid>
worker_connected host=127.0.0.1 port=<动态端口>
```

日志中保留端口、PID、退出码和耗时；请求令牌只记录长度，不记录内容。

### 启动失败诊断

给 `ProcessBuilder` 增加退出监听：

```java
Process launched = process;
launched.onExit().thenAccept(value -> {
    ModPedia.LOGGER.warn(
            "Worker exited before handshake: exit_code={}",
            value.exitValue()
    );
});
```

`accept()` 超时前先检查 `process.isAlive()`，子进程提前结束时立刻返回明确错误，不继续等待完整超时周期。

## 3. P0-2：移除 Render thread 同步等待

当前调用路径：

```text
FMLClientSetupEvent.enqueueWork
  → StartupKnowledgeBootstrap.runBeforeMainMenu
  → ModPediaBridge.startBeforeMainMenu
  → ServerSocket.accept().等待
```

建议将桥接层拆成异步入口：

```java
public CompletableFuture<WorkerReady> startAsync() {
    return CompletableFuture.supplyAsync(
            this::startOnce,
            lifecycle
    );
}
```

启动状态使用明确状态机：

```java
enum WorkerState {
    STOPPED,
    STARTING,
    HANDSHAKING,
    READY,
    RECONNECTING,
    FAILED
}
```

Render thread 只读取状态并绘制加载提示；Worker 启动、握手、知识库构建和等待响应全部留在生命周期执行器或 Worker JVM。

如果产品要求进入主菜单前完成预填充，推荐使用加载门：

```text
Worker 异步启动
  → 后台构建知识库
  → 客户端显示进度
  → 完成后放行主菜单
```

加载门应采用短轮询状态或完成回调，避免在 Render thread 调用 `Future.get()`。

### 时间预算

建议：

| 阶段 | 单次等待上限 |
| --- | ---: |
| Worker 首次握手 | 3 秒 |
| 单次 IPC 请求 | 按操作设置，普通请求 5 秒 |
| 知识库构建 | 单独的构建预算，不复用握手超时 |
| 自动重连 | 2、4、8、16 秒退避，上限 30 秒 |

## 4. P0-3：物品目录失败熔断

当前代码在 Worker 失败时清空 `attemptedLanguage`，使下一次客户端 Tick 重新启动完整扫描。应改为显式状态：

```java
enum CatalogState {
    IDLE,
    CAPTURING,
    PERSISTING,
    READY,
    WAITING_WORKER,
    FAILED
}
```

失败路径保留当前语言和旧目录：

```java
private static volatile CatalogState state = CatalogState.IDLE;
private static volatile String attemptedLanguage = "";
private static volatile long retryAtMillis;

private static boolean persist(...) {
    if (bridge == null || !bridge.syncItems(language, entries)) {
        state = CatalogState.WAITING_WORKER;
        attemptedLanguage = language;
        retryAtMillis = System.currentTimeMillis() + 5_000L;
        return false;
    }
    state = CatalogState.READY;
    syncedLanguage = language;
    return true;
}
```

`tick()` 只做状态检查：

```java
if (state == CatalogState.WAITING_WORKER
        && System.currentTimeMillis() < retryAtMillis) {
    return;
}
```

更理想的触发方式是 `ModPediaBridge` 在 Worker 进入 `READY` 后发送一次恢复事件，物品目录服务收到事件后再执行一次同步。语言变化仍然可以触发新同步。

## 5. P0-4：恢复后的补偿流程

Worker 初次启动失败或连接中断后，恢复流程必须明确：

```text
READY
  → 检查 knowledge.db 指纹
  → 检查知识库构建状态
  → 检查当前语言的 item_catalog
  → 仅执行缺失或过期的同步
  → 发布恢复事件
```

恢复事件至少包含：

```json
{
  "type": "worker_ready",
  "worker_pid": 12345,
  "knowledge_ready": true,
  "item_catalog_ready": true
}
```

聊天 UI 在 `STARTING`、`RECONNECTING` 和 `FAILED` 状态应显示对应状态文本；用户发送请求时直接得到可读状态，而不是进入长时间等待。

## 6. P1-1：必须新增的回归测试

### 6.1 地址族测试

```text
- 父进程 IPv4 + Worker 默认地址
- 父进程 IPv6 + Worker 默认地址
- 父子进程显式 IPv4
- 端口占用
- Worker 立即退出
```

验收：每个场景都在握手超时前返回状态，Render thread 没有同步等待。

### 6.2 物品目录失败测试

```text
1. 让 Worker 返回连接失败
2. 连续执行 500 次 tick
3. 断言 registryItems/captureBatch 只启动一次
4. 断言旧目录仍可查询
5. 模拟 Worker READY
6. 断言只执行一次补偿同步
```

### 6.3 进程级测试

保留现有：

```bash
./gradlew --offline workerIpcSelfTest
```

并新增开发运行专用测试，使用和 `runClient` 相同的：

```text
-Djava.net.preferIPv6Addresses=system
```

这样打包 JAR 自测和开发环境自测会覆盖同一地址族条件。

## 7. P1-2：整理 Worker/common/client 边界

当前 Worker 业务代码仍引用部分 `client` 包中的纯数据类型，例如：

```text
ChatMessage
MessageRole
SourceReference
ConversationSummary
```

建议移动到：

```text
io.ctyx.modpedia.model
```

或：

```text
io.ctyx.modpedia.protocol.model
```

迁移原则：

1. 纯 record、enum、协议载荷放入 common/model。
2. Screen、Minecraft、NeoForge、第三方客户端反射类留在 `client`。
3. Worker 编译和运行时 classpath 继续保持 Minecraft 类缺席。
4. Dedicated Server 与 Worker 进程级测试继续检查客户端类加载记录。

## 8. 验证顺序

```bash
cd /Users/chenhong/Documents/modpedia
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew --offline compileJava
./gradlew --offline workerIpcSelfTest
./gradlew --offline test
./gradlew --offline build
git diff --check
```

然后启动客户端：

```bash
./gradlew runClient
```

人工检查：

1. 启动日志中出现 Worker 握手成功。
2. 启动过程没有 45 秒级 DeferredWorkQueue 警告。
3. 进入存档后 `Item catalog Worker unavailable` 不出现连续刷屏。
4. 进入存档后帧率保持稳定。
5. `K` 打开助手并成功发送搜索请求。
6. F9 重建、语言切换和 Worker 重连各执行一次目标操作。
7. Dedicated Server 启动后日志保持客户端类隔离。

## 9. 回滚边界

修复时只触碰以下范围：

```text
ModPediaBridge.java
WorkerMain.java
StartupKnowledgeBootstrap.java
ItemCatalogSyncService.java
ModPediaClient.java
WorkerIpcSelfTest.java
新增 Worker/客户端失败回归测试
```

## 10. 文档导入检查

如果 `state.json` 或 `build-report.json` 只有任务 Wiki，而 `generated/` 为空，先不要
修改 FTS 或数据库结构，按以下顺序检查：

```bash
find <game-dir>/mods -type f \( -name '*.jar' -o -name '*.zip' \) | wc -l
grep -E 'Knowledge scan mods directory|Knowledge rebuild completed' <latest.log>
grep -E 'knowledge.rebuild|knowledge.scan' <config>/modpedia/worker/worker.log
```

必须同时满足：

```text
archives > 0
resources > 0（实例中存在可识别手册时）
documents >= resources 的逻辑文档数
warnings = 0 或每条警告均可解释
```

当前实现的 `WorkerGuideScanner.archiveFiles()` 会递归扫描最多三层 profile
目录。传入目录已经包含 JAR 时优先使用它；只有目录为空才从 Worker 配置目录
父级和当前工作目录回退。这样既兼容启动器目录差异，也避免把含有更多 JAR 的
无关目录误选为当前实例。

AI 提示词、SQLite Schema、UI 布局和手册解析逻辑先保持原样。完成 P0 后再决定是否进行 common/model 包迁移。
