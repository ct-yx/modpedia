# ModPedia client-core

这是供各 Minecraft 版本适配层复用的 loader-neutral 纯 Java 8 模块。它不加载 Minecraft、Forge、NeoForge 或 Worker 实现。

## 当前公共接口

- `WorkerManifest`、`PagesManifestSource`：读取 Pages `worker/latest.json`。
- `WorkerPaths`、`WorkerInstaller`、`ArtifactVerifier`：用户级 `current/staging/install.lock/install-state.json`、备用源、SHA-256、Ed25519 和原子激活。
- `JavaRuntime`、`WorkerProcessLauncher`：检查 Java 17 并使用独立 classpath 启动 `io.ctyx.modpedia.worker.WorkerMain`。
- `WorkerProtocolClient`：v1/v2 兼容判断、HmacSHA256(token, challenge) challenge proof、resume 和增量事件模型。
- `RuntimeTooltipCoordinator`：只允许用户确认或手动标记物品读取运行时 Tooltip，未确认返回 `item_not_confirmed`。

## Worker 清单签名契约

`artifact.signature` 是对以下 canonical payload 的 Ed25519 签名，不是对裸 JAR 的签名：

```text
schema=<schema>\n
channel=<channel>\n
worker_version=<worker_version>\n
java_major=<java_major>\n
protocol_version=<protocol_version>\n
primary_url=<artifact.primary_url>\n
fallback_url=<artifact.fallback_urls[0]>\n
fallback_url=<artifact.fallback_urls[1]>\n
...\n
sha256=<artifact.sha256>\n
```

编码固定为 UTF-8，行尾固定为 LF，备用地址保持清单顺序；`signature` 字段不参与签名。
客户端安装时同时验证 JAR 的 SHA-256 和该 payload 的 Ed25519 签名。未配置可信公钥、清单
缺少摘要或签名时安装会失败。公钥由发布端以 X.509 DER 的 Base64 形式提供，client-core
不生成或猜测公钥。

验签使用 `net.i2p.crypto:eddsa:0.3.0`，因此 Java 8 客户端不依赖 Java 15+ 内置的
Ed25519 provider；版本适配构建必须把该依赖放入客户端运行时 classpath。

## 验证

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
../gradlew -p client-core clean check --no-daemon
```

构建使用 `--release 8`，`verifyJava8Bytecode` 会拒绝 major version 大于 52 的 class。
