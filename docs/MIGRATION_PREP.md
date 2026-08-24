# 1.12.2 双运行时迁移准备

## 当前目标

| 项目 | 决策 |
| --- | --- |
| Minecraft | `1.12.2` |
| 编译/API 基线 | Forge `14.23.5.2847` |
| 运行时目标 | 目标加载器 `0.3+` |
| Java 客户端 | Java 8 |
| Worker | `worker-baseline-3`，独立 Java 21 JVM |
| 当前阶段 | 第一版兼容层已完成，等待真实 Cleanroom 客户端回归 |

真实大型整合包的手册与知识来源扫描见
[`GREEDYCRAFT_CLEANROOM_MANUAL_CATALOG.md`](GREEDYCRAFT_CLEANROOM_MANUAL_CATALOG.md)。
该清单是只读扫描结果；本分支已经按清单接入静态来源转换入口，具体运行时页面
仍以实际客户端注册结果为准。

选择 Forge 1.12.2 API 作为公共编译基线，目的是让同一份 1.12.2 业务代码保持
Forge 兼容，并把目标加载器 0.3+ 当作运行时验证目标。源码不使用目标加载器专用
API，也不使用 0.6+ 模板中的 Java 25 或现代生命周期。最终是否能在目标加载器
0.3+ 上运行，仍需目标运行时的客户端/服务端证据确认。

## 第一版已实现内容

```text
build.gradle
gradle.properties
settings.gradle
src/main/java/io/ctyx/modpedia/ModPedia.java
src/main/resources/mcmod.info
src/main/resources/pack.mcmeta
```

当前分支的第一版已经完成从来源发现到 Worker 查询的公共链路，避免把
现代客户端实现直接复制到 Java 8 客户端：

- `ManualCatalogScanner` 扫描实例目录中的外部 Patchouli 书籍和 `mods/**/*.jar`；
- 识别 `assets/` 与 `data/` 下的 1.12.2 Patchouli 书籍；
- 识别 Mantle、Forestry、EnderIO、Guide-API、Thaumcraft Research、Logistics Pipes
  和文本 Wiki 来源，并排除框架本体与 `patchouli_books_disabled`；
- 根据真实整合包资源识别 `assets/chisel_guide/**`、`assets/bloodmagicguide/**`、
  `assets/bloodarsenalguide/**`、`assets/bloodmagic/books/**` 与
  `assets/logisticspipes/book/**`，不会把这些旧格式误当作 Patchouli；
- 外部 `greedycraft_guide_book`、`the_elysia_project` 归类为 `wiki`；
- 客户端预初始化阶段用守护后台线程生成
  `config/modpedia/knowledge/manifest.json`、Markdown 来源和旧版任务派生文件；
- `LegacyItemIdentity` 保留 `namespace:item + metadata + NBT 指纹`，不使用扁平化
  物品 ID 假设。

已接入的功能边界：

- `LegacyMarkdownCompiler` 将 Patchouli、Mantle、Forestry、EnderIO、Guide-API、
  Thaumcraft Research 和文本来源转换为带来源元数据的完整 Markdown；未知 JSON/XML
  节点保留原始代码块；
- `LegacyFtbQuestNormalizer` 将 1.12.2 的“一文件一任务”转换为 Worker 可导入的
  静态章节，原始 FTBQ 文件不修改；
- `LegacyFtbQuestRuntimeReader` 支持
  `data/ftb_lib/teams/<team>/ftbquests.dat`，本地单机仍只在任务查询时读取当前玩家的团队
  NBT，只把 `Tasks` 作为临时进度发送，不写知识库；两次读取之间生成内存时间线。
  服务端安装本 Mod 且存在 FTBQ 时，进入世界/换维度/重生捕获一次快照，任务完成事件只
  增量追加已完成任务和时间线，再通过客户端包同步；快照仍只保存在两侧 JVM 内存；
- `LegacyWorkerBridge` 通过 JSONL 启动固定 `worker-baseline-3` 的 Java 21 Worker；
  Markdown、SQLite/FTS、AI、会话和任务静态导入都在 Worker JVM 执行；
- `LegacyAssistantScreen` 提供单一 Screen、输入/发送/插入/关闭、取消、重试、超时
  状态、流式事件和本地 Markdown 回退；设置页支持 AI/仅搜索、API 地址/模型/密钥、
  四种 API 格式、模型列表、连接测试和搜索强度；历史页支持新建、切换、清空和删除
  会话；关闭后恢复进入助手前的界面；设置/视频/按键/语言界面不会被 K 键覆盖；
- 物品目录在注册表完成后按小时间片分批生成基础 ID、metadata=0 和显示名称，交给
  Worker 写入 item catalog；不枚举变体，不在启动阶段生成 Tooltip。若玩家在目录完成
  前点击原版选择/创建世界，客户端会暂存进入动作并显示准备页，写入完成后再继续；
  其它非原版入口进入世界后仍会暂停扫描。AI 确认物品后，每次最多按需读取 5 个 Tooltip，
  查询次数由搜索强度限制，成功结果由 Worker 异步缓存到 `knowledge.db`；语言切换只
  重建基础名称目录；捕获完成时只输出一次聚合耗时、数量和最慢物品诊断；
- Shift+左键回答中的 `[[item:...]]` 令牌通过反射调用可选 JEI/同 API 实现的配方
  界面；Worker 请求配方时回传方法、机器去重、输入、输出和附加信息；
- 准星、原版 Tooltip 以及其它可选覆盖层的 ItemStack 都统一转换为旧版
  `注册名 + metadata + NBT 指纹`。按 K 时先冻结目标，打开助手后不再被 UI 覆盖层改写，
  只有点击“插入”才写入物品令牌；当前实例没有目标识别 Mod 时仍可用准星/Tooltip。

构建配置使用 ForgeGradle 2.3 和 Forge 1.12.2；Gradle 的默认运行目录固定在
`build/forge-run`，本分支不调用运行任务，因此不会创建项目根目录的 `run/`、
`run-client/`、`run-server/` 或测试存档目录。

## Worker 边界

1.12.2 客户端使用 Java 8，而当前 Worker 基线使用 Java 21。后续适配层必须通过
独立的 Java 21 可执行文件启动 Worker，不能把 Worker Java 21 类加载进客户端 Java 8
进程。IPC、JSONL 协议、`worker-baseline-3` 和用户级共享 lib保持不变；只有协议或
Worker 依赖发生不兼容变化时才递增基线。

`worker-baseline-3` 的发布 JAR 同时可能包含
`META-INF/jarjar/*.jar` 与 `META-INF/modpedia-worker/*.jar`。1.12.2 适配层会在
发布 Mod JAR 内嵌 Worker 包，启动 Worker 前会将 Worker 包及两类隔离依赖提取到用户级
基线目录；后者用于 Worker 专用 Gson，保持在 Worker 类路径中。

当前共享 Worker 构建来自 `modpedia-worker` 提交 `3e77dd0`。该提交修复了独立
Worker 的 SLF4J 隔离；协议版本、API level 保持原值，基线更新为 `worker-baseline-3`。

## 当前启动顺序

1. Forge/目标加载器完成公共生命周期初始化；Dedicated Server 只加载 `CommonProxy`。
2. 守护线程扫描手册并完成 Markdown 转换、FTBQ 静态归一化。
3. 客户端注册表完成后，在主菜单阶段按小时间片分批捕获物品目录；进入世界时 gate
   关闭未完成捕获，避免旧版客户端主线程在游戏内做全量 Tooltip 扫描。
4. Worker 进程启动并握手成功后，再触发 knowledge.db 重建；不会在来源尚未转换时
   提前构建空库。
5. 玩家按 K 后助手冻结目标；远程任务快照由服务端事件同步，Worker 仍只在模型确实请求
   运行时任务/配方时通过 IPC 读取；客户端不读取远程服务器磁盘。

## 第一版仍需真实回归

- 目标加载器 `0.3+` 的真实客户端/服务端启动；当前只保证公共 Forge 1.12.2 API
  编译和无专用 API 设计。
- 用户级 `~/.modpedia/worker/lib/worker-baseline-3/` 中实际放置 Java 21 Worker
  后的握手、SQLite/FTS、AI 请求、会话和重建耗时。
- 真实大型整合包中的来源跳转、旧页型完整度、物品 Tooltip 数量和配方布局。
- 多人服务器：1.12.2 客户端不能读取远程服务器磁盘；服务端安装本 Mod 时，登录、换维度、
  重生会同步一次当前玩家快照，FTBQ 任务完成事件会增量同步 `completed_quest_ids` 和
  `timeline`。服务端未安装本 Mod 或未安装 FTBQ 时，运行时进度保持不可用；本地单机仍
  直接读取 FTB Library 团队文件。

本分支不创建项目根目录的 `/run` 测试环境；Gradle 的内部 `runDir` 固定为
`build/forge-run`，也不会把 Worker Java 21 类打入 Java 8 Mod JAR。
