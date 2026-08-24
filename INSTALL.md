# ModPedia v1.4.0 安装说明

## 选择正确的文件

| 文件 | 目标环境 | Java |
| --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | Minecraft 1.21.1 + NeoForge 21.1.x | 21 |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | Minecraft 1.20.1 + Forge 47.x | 21 |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | Minecraft 1.12.2 + Cleanroom 0.3+ 兼容线 | 游戏 Java 8；Worker Java 21 |

只安装与当前 Minecraft/加载器匹配的一份 JAR。三个文件不是同一个环境的替换包。

## 安装步骤

1. 安装对应的 Minecraft、加载器和 Java。
2. 从 [v1.4.0 Release](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0) 下载匹配文件。
3. 将 JAR 放入当前实例的 `mods/` 目录。
4. 首次启动时等待加载屏幕完成手册来源和当前语言物品目录预填充。
5. 进入世界后按 `K` 打开助手；按 `F9` 请求当前实例知识库重建。

ModPedia 的 UI 不依赖 ModernUI。Patchouli、GuideME、Modonomicon/APP、FTB Quests、JEI、Jade 都是可选联动；缺失时核心助手仍应加载。手册框架本身不保证包含正文，请同时安装提供实际书籍的内容模组。

## 工作模式

- **仅搜索**：设置中选择“仅搜索”，不读取 API 配置，直接从 `knowledge.db` 返回完整 Markdown 段落、标题路径、匹配分和来源按钮。
- **AI 回答**：设置中选择“AI 回答”，填写 API 格式、地址、模型和 Key。支持 Chat Completions、原生 Messages、Responses 和 Gemini `generateContent`。模型列表是可选功能，服务不提供 `/models` 时直接填写模型名称。

API Key 保存在用户目录 `~/.modpedia/ai.json` 的加密字段中，不随整合包分发。游戏启动时只解密到内存；日志、会话和诊断不会记录明文 API Key 或系统 UUID。不同游戏实例可以共享这份配置。

## 数据目录

```text
<instance>/config/modpedia/
├── runtime/                         # 玩家运行时数据，发布整合包前删除
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/knowledge.db*      # 派生知识库与扫描缓存
└── knowledge/                       # 整合包作者原始知识源，发布时保留
    ├── custom/**/*.md
    ├── sources/<source-id>/source.json
    ├── sources/<source-id>/documents/**/*.md
    ├── sources/<source-id>/media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json
├── installation-id
└── worker/lib/worker-baseline-3/
```

### 发布整合包前

删除整个 `config/modpedia/runtime/`，不要复制 `~/.modpedia/`。保留 `config/modpedia/knowledge/` 下的自定义 Markdown、Wiki 来源描述、文档、媒体元数据、书籍分类覆盖和同义词文件。

不要发布：

- API Key、`ai.json`、会话和诊断日志；
- `knowledge.db`、`knowledge.db-wal`、`knowledge.db-shm`、生成 Markdown 和缓存；
- Worker 日志、IPC 状态、临时载荷和用户级 Worker `lib/`；
- 本地构建目录或测试 JAR。

## 常用快捷键

| 按键 | 行为 |
| --- | --- |
| `K` | 打开/关闭助手；原版游戏设置和按键绑定页不会呼出 |
| `F9` | 重建当前实例知识库 |
| `Esc` | 输入框优先失焦，否则关闭当前页面 |
| `Enter` | 发送问题 |
| `Ctrl` | 显示物品 ID；默认显示本地化名称 |
| `Shift` + 点击物品 | 尝试打开 JEI 配方界面 |

## 遇到问题

先确认 JAR、Minecraft、加载器和 Java 匹配，再查看当前实例的 `config/modpedia/runtime/worker/` 日志。提交问题时请附：版本、加载器、是否安装可选联动、错误时间段和脱敏日志；不要附 API Key、`ai.json` 或整个用户目录。

更多架构、来源格式和发布说明：

- [`README.md`](README.md)
- [`docs/RELEASE_1.4.0.md`](docs/RELEASE_1.4.0.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/KNOWLEDGE_BASE.md`](docs/KNOWLEDGE_BASE.md)
- [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)
