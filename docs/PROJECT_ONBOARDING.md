# ModPedia 项目入门

本文面向第一次接手项目的维护者，目标是在当前发布基线下快速理解工程、运行边界和验证方式。

## 1. 项目定位

ModPedia 是 Minecraft 1.21.1 + NeoForge 1.21.1 的本地知识助手：

```text
已安装模组手册 / Wiki / 自定义 Markdown
        ↓
统一 Markdown 文档
        ↓
SQLite + FTS5
        ↓
规则搜索或 AI 工具调用
        ↓
助手浮窗、来源标注和原手册跳转
```

项目还包含三个可选联动：

- FTB Quests：导入静态任务定义；玩家实时进度按任务问题读取。
- JEI：解析物品 ID，并尝试打开配方界面；配方正文暂不导入数据库。
- Jade：读取视线目标并插入物品令牌。

Patchouli、GuideME、Modonomicon、FTB Quests、JEI 和 Jade 都属于可选联动对象。ModPedia 核心加载路径只依赖 NeoForge 和自身内置依赖。

## 2. 当前基线

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.244 |
| Java | 21 |
| Mod ID | `modpedia` |
| 包名 | `io.ctyx.modpedia` |
| 当前发布版本 | `v1.2.0` |
| 当前检查分支 | `main` |
| 当前检查 HEAD | 以 `git log -1` 为准 |
| 主配置目录 | `config/modpedia/` |
| 知识库 | `config/modpedia/runtime/knowledge/knowledge.db` |
| 会话目录 | `config/modpedia/runtime/conversations/` |
| Worker 日志 | `config/modpedia/runtime/worker/worker.log` |

维护者进入项目后的第一项工作是确认当前分支、版本和工作区状态：

```bash
cd /Users/chenhong/Documents/modpedia
git status --short
git log -1 --oneline --decorate
```

## 3. 代码结构

```text
src/main/java/io/ctyx/modpedia/
├── ModPedia.java                    # 模组公共入口
├── ModPediaClient.java              # 客户端事件和按键
├── client/                          # Screen、渲染、注册表和可选联动
├── knowledge/                       # 手册扫描、格式转换、文档编译
├── search/                          # SQLite、FTS5、检索和物品目录
├── ai/                              # AI、工具、会话和上下文
├── task/                            # 静态任务模型与运行时数据模型
├── protocol/                        # Worker IPC JSONL 协议
└── worker/                          # 独立 Worker JVM 服务
```

### 3.1 两个 JVM 的职责

```text
游戏 JVM
  ├── Minecraft UI
  ├── 注册表和 Tooltip 捕获
  ├── Jade / JEI / FTBQ 客户端适配
  └── ModPediaBridge

Worker JVM
  ├── SQLite / FTS5
  ├── JAR 手册扫描与 Markdown 构建
  ├── AI API、SSE 和工具循环
  ├── 历史会话与 ChatMemory
  ├── 静态任务导入和任务 Wiki
  └── 物品目录批量写入
```

通信格式是带协议版本和随机认证令牌的 localhost JSONL。游戏 JVM 只通过 `ModPediaBridge` 发起请求；Worker 独占知识库和会话数据库。

## 4. 启动链路

当前启动链路如下：

```text
FMLClientSetupEvent
  → StartupKnowledgeBootstrap.runBeforeMainMenu()
  → ModPediaBridge.startBeforeMainMenu()
  → WorkerMain
  → WorkerServer
  → knowledge.rebuild
  → ItemCatalogSyncService.syncBeforeMainMenu
```

Worker 启动、知识库重建和物品目录批量写入均在启动异步线程/Worker 中执行；物品
Tooltip 捕获只允许发生在主菜单且只进行一轮，进入世界后的 `ClientTickEvent` 不会
再次触发全量扫描。当前线程、生命周期和数据边界以
[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`KNOWLEDGE_BASE.md`](KNOWLEDGE_BASE.md)
和 [`DEVELOPMENT.md`](DEVELOPMENT.md) 为准。

维护者需要重点理解两件事：

1. `FMLClientSetupEvent.enqueueWork` 只提交启动流程；长时间等待由
   `StartupKnowledgeBootstrap` 的后台执行器承接，避免阻塞渲染 Tick。
2. Worker 失败时保留上一份物品目录并记录错误；不要把重试逻辑改回世界内的
   每 Tick Tooltip 捕获。

## 5. 数据目录

```text
config/modpedia/
├── runtime/                         # 发布整合包前删除
│   ├── conversations/
│   │   ├── conversation-*.json
│   │   └── memory.sqlite
│   ├── diagnostics/
│   ├── worker/
│   │   ├── worker.log
│   │   └── payloads/
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/
│       ├── knowledge.db*
│       ├── generated/
│       ├── cache/
│       ├── manifest.json
│       ├── keyword-index.json
│       └── state.json
└── knowledge/                       # 随整合包保留
    ├── custom/
    ├── sources/<source-id>/
    │   ├── source.json
    │   ├── documents/**/*.md
    │   └── media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json                           # 跨实例共享的用户级 AI 配置
└── installation-id                   # 无系统 UUID 时的共享回退标识
```

旧版 `config/modpedia/ai.json` 和 `config/modpedia/runtime/ai.json` 会在启动时迁移到
`~/.modpedia/ai.json`；该用户目录不属于整合包，也不随实例复制。

`runtime/knowledge/knowledge.db` 使用 Schema v7，包含手册、Wiki、静态任务定义和
`item_catalog`。运行时任务进度使用当前查询的内存快照；会话正文与知识库正文分开保存。

## 6. 开发环境

建议固定 Java 21：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

当前机器的 shell 环境曾指向不存在的 GraalVM 目录，Gradle 直接调用会被 `JAVA_HOME` 检查拦截。使用上面的 Zulu 21 路径即可复现工程基线。

## 7. 常用命令

### 编译与纯 Java 测试

```bash
./gradlew --offline test
./gradlew --offline build
git diff --check
```

### Worker 进程级测试

```bash
./gradlew --offline workerIpcSelfTest
./gradlew --offline workerGuideScannerSelfTest
```

测试覆盖：握手、认证、协议版本、取消、知识库重建、物品目录批量同步、AI 夹具、历史重启恢复、客户端类隔离，以及嵌套 `mods` 目录的手册扫描。

### 客户端测试

```bash
./gradlew runClient
```

运行日志：

```text
/Users/chenhong/Documents/modpedia/run/logs/latest.log
/Users/chenhong/Documents/modpedia/run/logs/debug.log
```

### Dedicated Server

```bash
./gradlew runServer
```

服务端验证重点是 ModPedia 客户端类和第三方客户端类保持物理隔离。

## 8. 调试快捷键和功能入口

| 操作 | 功能 |
| --- | --- |
| `K` | 打开/关闭助手 |
| `F9` | 请求知识库重建 |
| `F8` | 原版电影视角，保持原功能 |
| 设置 → 仅搜索 | 绕过 AI，直接查询 SQLite |
| 设置 → 获取模型列表 | 查询兼容 API 的模型列表 |

## 9. 维护约定

- 先查看 `git status`，只暂存当前功能相关文件。
- 优先修改最小文件集合，先补纯 Java 回归，再启动真实客户端。
- Worker 代码不得引入 Minecraft、NeoForge、ModernUI 或其他客户端 API。
- 客户端只读取 UI 所需快照，数据库和网络工作交给 Worker。
- API Key、请求头和完整请求体禁止写入日志或诊断报告。
- 真实模型测试使用配置好的低成本模型；纯 Java 测试默认使用本地夹具。
- 任何性能结论都要区分 Worker 基准和真实客户端帧时间。

## 10. 推荐入门顺序

```text
1. 阅读本文件
2. 阅读 docs/ARCHITECTURE.md
3. 阅读 docs/KNOWLEDGE_BASE.md
4. 阅读 docs/DEVELOPMENT.md 和 docs/ROADMAP.md
5. 阅读 [NEXT_DEVELOPMENT_PLAN.md](NEXT_DEVELOPMENT_PLAN.md)，了解 AI 上下文、数据库 v8 和外部百科的统一后续路线
6. 运行 ./gradlew test
7. 检查 ModPediaBridge、StartupKnowledgeBootstrap 和 ItemCatalogSyncService
8. 按 docs/DEVELOPMENT.md 与 NEXT_DEVELOPMENT_PLAN.md 的清单补充对应自测
9. 再启动 runClient 或 runServer 做人工回归
```
