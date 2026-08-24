# ModPedia · 模组百科

ModPedia 是面向 Minecraft 整合包的本地模组知识助手：读取已安装内容模组的手册、整合包作者 Wiki、任务定义和物品 Tooltip，转换为可追溯的 Markdown，写入本地 SQLite/FTS5 知识库，再通过“仅搜索”或可选 AI 回答呈现。

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ct-yx/modpedia?label=release)](https://github.com/ct-yx/modpedia/releases)

English: [README.en.md](README.en.md) · 网站：[GitHub Pages](https://ct-yx.github.io/modpedia/)

## v1.4.0 发布基线

| 版本资产 | Minecraft / 加载器 | Java | Worker |
| --- | --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | 1.21.1 / NeoForge 21.1.x | 21 | `worker-baseline-3` |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | 1.20.1 / Forge 47.x | 21 | `worker-baseline-3` |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | 1.12.2 / Cleanroom 0.3+ 兼容线 | 游戏 Java 8；Worker Java 21 | `worker-baseline-3` |

三个版本共用同一产品协议和 Worker 设计，但客户端适配层分别针对对应加载器编译。完整的
`v1.1.0 → v1.4.0` 对比见 [`docs/RELEASE_1.4.0.md`](docs/RELEASE_1.4.0.md)，下载矩阵见网站的
[功能对照表](https://ct-yx.github.io/modpedia/#feature-matrix)。

技术标识保持稳定：`mod_id=modpedia`、包名 `io.ctyx.modpedia`、作者 `ctyx`、许可证
[Apache License 2.0](LICENSE)。

## 核心功能

- **本地优先检索**：统一 `knowledge.db`，使用 SQLite FTS5；完整 Markdown 从事实表读取，来源保留文档 ID、标题路径、原始路径和页级跳转。
- **两种工作模式**：仅搜索模式不需要 API Key，直接返回完整段落、标题路径、匹配分、物品信息和正文内来源按钮；AI 模式可按证据继续调用本地工具补搜。
- **独立 Worker JVM**：游戏 JVM 只做 UI、注册表/Tooltip 和可选模组适配；Worker 负责扫描、SQLite/FTS5、AI、会话、任务静态导入和批量写入，不把网络或重型数据库工作放到渲染线程。
- **多种 AI API**：Chat Completions、原生 Messages、Responses、Gemini `generateContent`；支持模型列表、连接测试、普通/流式请求、工具调用续接和错误回退。
- **本地计算工具**：复杂的数量、比例、取整和多步算术使用 `BigDecimal` 计算，不依赖模型心算。
- **任务、配方和物品上下文**：FTB Quests、JEI、Jade 均为可选联动；运行时任务进度按查询读取，不写入全局知识库；JEI 配方按需查询，不复制整套配方；物品目录保存当前语言的 ID、名称和 Tooltip。

### 手册框架与内容模组

手册框架是前置性的，**框架本身不一定包含正文**。Patchouli、GuideME、Modonomicon/APP
缺失时 ModPedia 仍可进入游戏；要获得正文，需要安装真正提供书籍内容的内容模组或整合包作者来源。

| 来源 | 当前支持内容 | 归属 |
| --- | --- | --- |
| Patchouli | 书籍、分类、条目、页面和常见页面节点；`zh_cn → en_us → neutral` 回退 | `mod_manual` 或按覆盖归入 `wiki` |
| GuideME / Guide-API | Markdown、文本、语言目录和页面索引；含 1.12.2 兼容适配 | `mod_manual` |
| Modonomicon / APP JSON | 书籍、分类、条目、页面、配方/物品/链接节点和未知节点 Markdown 降级 | 默认 `mod_manual`，可声明为 `wiki` |
| 自定义 Markdown | `custom/**/*.md` 和 `sources/<source-id>/documents/**/*.md` | 默认 `wiki` |
| FTB Quests Wiki | 内置或本地 Wiki Markdown，和任务静态定义分开检索 | `wiki` |

APP/Modonomicon 书籍的根 JSON 可以声明：

```json
{
  "knowledge": {
    "content_kind": "wiki",
    "source_id": "pack-guide",
    "collection_id": "example-pack",
    "title": "整合包指南",
    "priority": 60
  }
}
```

也可以在 `config/modpedia/knowledge/source-overrides.json` 中按 `namespace/book_id` 覆盖分类。这样同一种 JSON 格式不会被错误地当成模组手册，整合包作者的指南可以进入 Wiki 集合。

### 可选联动

除 ModernUI 之外，以下内容都不是必需依赖；ModernUI 也不是 ModPedia UI 的运行时依赖，界面使用目标加载器的原生 GUI API 自绘。

- **FTB Quests**：静态任务定义、依赖、要求、奖励和任务 Wiki 导入数据库；玩家当前完成/进行中进度在询问任务时按需读取，多个可行下一步以候选列表返回，随机奖励标记为候选而非确定奖励。
- **JEI**：不导入配方库。模型先选择工作台、熔炉或其他处理方式；工作台/熔炉直接查询，熔炉附带处理时间，其他方式先列方法再查询详情，并合并同机器不同等级。Shift+左键回答中的物品名称可尝试打开 JEI。
- **Jade 与准星目标**：悬浮信息或视线目标在按 `K` 时冻结；进入助手后必须点击“插入”，不会因为底层 UI 仍在变化而插入错误物品。
- **物品目录**：加载屏幕阶段按当前语言分批捕获注册物品名称和完整 Tooltip。普通显示区域使用名称，按住 Ctrl 显示原始 ID；AI 可以把物品目录作为事实上下文，再搜索手册。

缺少任意联动模组时，核心助手、仅搜索、本地手册扫描、数据库和 AI Worker 仍可正常加载；Dedicated Server 不解析客户端 UI 和第三方客户端反射类。

## 安装与首次使用

1. 从 [v1.4.0 Release](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0) 下载与加载器对应的 JAR。
2. 将 JAR 放入当前游戏实例的 `mods/` 目录；不要同时放入其他 Minecraft 版本的 JAR。
3. 使用对应 Java 启动游戏，等待加载屏幕完成手册和物品目录预填充后进入世界。
4. 按 `K` 打开助手；需要重建当前实例知识库时按 `F9`。
5. 在设置中选择“仅搜索”或“AI 回答”。AI 模式下配置地址、模型、API 格式和密钥；“获取模型列表”不是所有服务都提供，失败时可直接填写模型名。

完整安装和目录清理规则见 [`INSTALL.md`](INSTALL.md)。API Key 使用系统标识派生的 AES-GCM 密钥保存密文，启动时只在内存中解密；日志、会话和诊断文件不记录明文 Key 或系统 UUID。

## 文件布局与整合包发布规则

运行时状态、派生数据库、用户配置和整合包作者事实源分开：

```text
<instance>/config/modpedia/
├── runtime/                         # 玩家运行时目录；发布整合包前删除
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/                      # 日志、IPC 状态、临时 payload
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/knowledge.db*      # 派生库、缓存、扫描报告
└── knowledge/                       # 整合包作者随包保留的原始事实源
    ├── custom/**/*.md
    ├── sources/<source-id>/source.json
    ├── sources/<source-id>/documents/**/*.md
    ├── sources/<source-id>/media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json                           # 跨实例共享；只存密文设置
├── installation-id                   # 系统标识不可用时的回退标识
└── worker/lib/worker-baseline-3/     # 同一 Worker 基线共享
```

发布整合包前：

- 删除整个 `<instance>/config/modpedia/runtime/`，包括 `knowledge.db*`、生成 Markdown、扫描缓存、会话、诊断、Worker 日志和临时载荷；这些文件会在玩家首次启动或按 `F9` 重建。
- 保留 `config/modpedia/knowledge/custom/**/*.md`、`sources/<source-id>/source.json`、`documents/**/*.md`、`media.json`、`source-overrides.json` 和需要的 `search-synonyms.json`。
- 不复制用户级 `~/.modpedia/ai.json`、`installation-id` 或 `worker/lib/`；它们属于玩家本机并由多个游戏实例共享。
- 不把本地 JAR、API Key、会话、`knowledge.db`、诊断日志或临时构建目录提交到整合包。

## 开发与发布

- 发布/Pages 主分支：`main`；三条代码分支只维护自身加载器代码和测试。
- NeoForge 1.21.1：分支 `mainline-1.21.1`。
- Forge 1.20.1：分支 `migration/forge-1.20.1`。
- Cleanroom 0.3+：分支 `migration/1.12.2-common`。
- Worker 依赖或协议变化先按 [`docs/WORKER_CHANGE_PROTOCOL.md`](docs/WORKER_CHANGE_PROTOCOL.md) 生成摘要，在 Worker 对话完成修改，再同步各客户端适配层。
- 设计、架构、知识库和后续路线见 [`docs/`](docs/)。

本地验证：

```bash
./gradlew test
./gradlew build
git diff --check
```

三版本对比、删除项、修复项和测试记录见 [`docs/RELEASE_1.4.0.md`](docs/RELEASE_1.4.0.md)。

## 许可证与再发布

ModPedia 使用 Apache License 2.0。第三方修改或再发布时请保留原作者 `ctyx`、版权和许可证声明，并明确标注修改内容；不得以原作者名义宣传修改版。整合包正常收录本 Mod，不需要另行声明为修改版。

---

作者：`ctyx` · 技术标识：`modpedia` / `io.ctyx.modpedia`
