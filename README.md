# ModPedia client-core

本分支只维护供各 Minecraft 版本适配层复用的 loader-neutral Java 8 `client-core`。

## 边界

- 生产模块位于 [`client-core/`](client-core/)；编译目标 Java 8。
- Worker 实现、Minecraft/Forge/NeoForge 类和游戏资源不属于本分支，Worker 唯一来源是
  `modpedia-refactor-worker-core`。
- 根 Gradle 工程只聚合 `:client-core`，不会生成模组 JAR，也不会编译根目录的 Worker 快照。
- 三个版本分支需要把同一 client-core 内容接入各自的客户端 source set，并声明
  `net.i2p.crypto:eddsa:0.3.0` 运行时依赖。

## 验证

```bash
./gradlew clean check --no-daemon --no-configuration-cache
```

构建会执行 client-core 自测和 Java 8 class major 检查。签名清单的 canonical payload 和
Ed25519 验签约定见 [`client-core/README.md`](client-core/README.md)。
