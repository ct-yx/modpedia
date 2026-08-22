# 1.12.2 双运行时迁移准备

## 当前目标

| 项目 | 决策 |
| --- | --- |
| Minecraft | `1.12.2` |
| 编译/API 基线 | Forge `14.23.5.2860` |
| 运行时目标 | 目标加载器 `0.3+` |
| Java 客户端 | Java 8 |
| Worker | `worker-baseline-1`，独立 Java 21 JVM |
| 当前阶段 | 仅初始化，不创建 `/run` 测试环境 |

选择 Forge 1.12.2 API 作为公共编译基线，目的是让同一份 1.12.2 业务代码保持
Forge 兼容，并把目标加载器 0.3+ 当作运行时验证目标。源码不使用目标加载器专用
API，也不使用 0.6+ 模板中的 Java 25 或现代生命周期。最终是否能在目标加载器
0.3+ 上运行，仍需目标运行时的客户端/服务端证据确认。

## 已初始化内容

```text
build.gradle
gradle.properties
settings.gradle
src/main/java/io/ctyx/modpedia/ModPedia.java
src/main/resources/mcmod.info
src/main/resources/pack.mcmeta
```

构建配置使用 ForgeGradle 2.3 和 Forge 1.12.2；没有执行 Gradle、没有下载依赖，
也没有生成 `run/`、`run-client/`、`run-server/`、`build/` 或测试存档目录。

## Worker 边界

1.12.2 客户端使用 Java 8，而当前 Worker 基线使用 Java 21。后续适配层必须通过
独立的 Java 21 可执行文件启动 Worker，不能把 Worker Java 21 类加载进客户端 Java 8
进程。IPC、JSONL 协议、`worker-baseline-1` 和用户级共享 lib保持不变；只有协议或
Worker 依赖发生不兼容变化时才递增基线。

## 后续迁移顺序

1. 确认目标加载器 0.3+ 的实际构件和最小客户端/服务端启动模板。
2. 让空入口完成编译配置检查；仍不创建项目内 `run/` 目录。
3. 迁移 Forge 1.12.2 的生命周期、客户端隔离、配置路径和 Worker 启动器。
4. 迁移 UI、手册扫描、物品目录、任务读取、JEI/Jade 等可选联动。
5. 建立无联动、Forge 运行时、目标加载器运行时、Dedicated Server 和旧配置迁移矩阵。
6. 只有完成真实客户端/服务端回归后，才把目标加载器状态标记为 `verified`。

## 暂不处理

- 不复制 1.20.1/1.21.1 的现代注册、网络和生命周期代码。
- 不在此阶段修改 Worker Core。
- 不创建 `/run` 测试环境或真实游戏存档。
- 不把目标加载器 0.6+ 的模板字段当作 0.3+ 的已知 API。
