# ModPedia client-core

这是供各 Minecraft 版本适配层复用的 loader-neutral 纯 Java 8 模块。它不加载 Minecraft、Forge、NeoForge 或 Worker 实现。

## 当前公共接口

- `WorkerManifest`、`PagesManifestSource`：读取 Pages `worker/latest.json`。
- `WorkerPaths`、`WorkerInstaller`、`ArtifactVerifier`：用户级 `current/staging/install.lock/install-state.json`、备用源、SHA-256、Ed25519 和原子激活。
- `JavaRuntime`、`WorkerProcessLauncher`：检查 Java 17 并使用独立 classpath 启动 `io.ctyx.modpedia.worker.WorkerMain`。
- `WorkerProtocolClient`：v1/v2 兼容判断、HmacSHA256(token, challenge) challenge proof、resume 和增量事件模型。
- `RuntimeTooltipCoordinator`：只允许用户确认或手动标记物品读取运行时 Tooltip，未确认返回 `item_not_confirmed`。

## 验证

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
../gradlew -p client-core clean check --no-daemon
```

构建使用 `--release 8`，`verifyJava8Bytecode` 会拒绝 major version 大于 52 的 class。
