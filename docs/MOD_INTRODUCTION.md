# ModPedia · 模组百科

> 面向 Minecraft 整合包的本地知识助手：把手册、整合包 Wiki、任务定义和物品 Tooltip 变成可检索、可追溯的游戏内帮助。

## ModPedia 是什么

大型整合包的问题通常不是“没有资料”，而是资料分散在不同模组的手册、任务树、配方界面和整合包作者文档中。ModPedia 在本地建立知识库，把这些内容统一成完整 Markdown，并保留来源、标题路径和原始页面跳转。

你可以直接搜索，也可以让可选的 AI 根据本地事实组织答案。AI 不可用时，核心的“仅搜索”模式仍然可以工作。

## 核心体验

- **本地优先**：手册、Wiki、物品 Tooltip 和任务定义进入当前实例的 `knowledge.db`，使用 SQLite/FTS5 检索。
- **完整来源**：结果保留文档标题、章节路径、原始来源和正文内跳转，不把搜索结果压缩成无法核对的摘要。
- **仅搜索模式**：不需要 API 地址、模型或 API Key，直接返回完整段落、匹配分、物品信息和来源按钮。
- **AI 模式**：AI 可以调用本地搜索、任务、配方和计算工具；证据不足时继续补搜，而不是只依赖模型记忆。
- **独立 Worker**：扫描、数据库、AI 请求和会话存储在独立 Worker JVM 中处理，避免把重型任务放到游戏渲染线程。
- **多版本发布**：提供 Minecraft 1.21.1 NeoForge、1.20.1 Forge，以及同时兼容 Forge 1.12.2 和 Cleanroom 0.3+ 的兼容线。

## 支持的内容来源

手册框架是前置性组件，框架本身不一定包含书籍正文；需要实际提供书籍的内容模组或整合包作者来源。

| 来源 | 支持内容 |
| --- | --- |
| Patchouli | 书籍、分类、条目、页面和常见页面节点；支持 `zh_cn → en_us → neutral` 回退 |
| GuideME / Guide-API | Markdown、文本、语言目录、页面索引和 1.12.2 兼容适配 |
| Mantle / Tinkers' Construct（1.12.2） | 地幔/匠魂书籍、章节和页面来源，支持正文检索与跳转 |
| Modonomicon / APP JSON | 书籍层级、条目、页面、配方/物品/链接节点和未知节点 Markdown 降级 |
| 自定义 Markdown | `config/modpedia/knowledge/custom/**/*.md` |
| 整合包作者 Wiki | `sources/<source-id>/source.json`、Markdown 文档、媒体元数据和来源覆盖 |
| FTB Quests Wiki | 任务说明作为 Wiki 内容，和运行时任务进度、静态任务定义分开处理 |

APP/Modonomicon 书籍可以声明自己属于整合包 Wiki，而不是模组手册：

```json
{
  "knowledge": {
    "content_kind": "wiki",
    "source_id": "pack-guide",
    "collection_id": "example-pack",
    "title": "整合包指南"
  }
}
```

因此，手册格式和内容归属是两个独立概念；普通模组手册、整合包指南和社区文档可以共存于同一个可扩展知识库。

## 可选联动

FTB Quests、JEI、Jade 以及各类手册模组都不是必需依赖。缺少它们时，ModPedia 的核心助手、仅搜索、本地知识库和 Worker 仍应正常加载。

- **FTB Quests**：导入静态任务定义、依赖、要求、奖励和 Wiki；询问任务时按需读取当前玩家进度，返回候选下一步、阻塞原因和时间线。运行时进度不会写进全局知识库。
- **JEI**：不复制整套配方到数据库。工作台、熔炉和其他处理方式按需查询；熔炉结果包含处理时间，其他机器的不同等级会合并展示。回答中的物品名称支持 Shift+点击尝试打开 JEI。
- **Jade 与准星目标**：按 `K` 时冻结当前目标，打开助手后通过界面中的“插入”按钮确认写入，避免底层界面变化导致物品错位。
- **物品目录**：启动阶段按当前语言导入注册物品的 ID、名称和完整 Tooltip。普通状态显示本地化名称，按住 Ctrl 显示 ID；AI 可以把 Tooltip 作为本地事实再检索手册。

ModPedia UI 使用目标加载器的原生 GUI API 自绘，不依赖 ModernUI。

## AI 与隐私

- 支持 Chat Completions、原生 Messages、Responses 和 Gemini `generateContent` 四种 API 形态。
- API Key 保存于用户级 `~/.modpedia/ai.json` 的加密字段中，不随整合包分发；不同游戏实例可以共享配置。
- 启动时只将配置解密到内存；日志、会话和诊断文件不记录明文 API Key 或系统 UUID。
- “仅搜索”模式完全绕过 AI 请求，不读取 API 配置也不需要网络。
- 计算工具使用 `BigDecimal` 处理数量、比例、取整和多步算术，减少模型心算错误。

## 安装与使用

1. 从 [v1.4.0 Release](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0) 下载与 Minecraft/加载器匹配的 JAR。
2. 将 JAR 放入当前实例的 `mods/` 目录；不要混用不同 Minecraft 版本的文件。
3. 启动游戏并等待加载阶段完成手册和物品目录预填充。
4. 按 `K` 打开助手；在设置中选择“仅搜索”或“AI 回答”。
5. 需要重建当前实例知识库时按 `F9`。

| 文件 | 目标环境 | Java |
| --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | Minecraft 1.21.1 + NeoForge 21.1.x | 21 |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | Minecraft 1.20.1 + Forge 47.x | 21 |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | Minecraft 1.12.2 + Forge 14.23.5.2847 / Cleanroom 0.3+ | 游戏 Java 8；Worker Java 21 |

## 整合包作者须知

发布整合包时删除当前实例的 `config/modpedia/runtime/`，其中包含派生数据库、缓存、会话、诊断和 Worker 临时状态；保留 `config/modpedia/knowledge/` 下的自定义 Markdown、Wiki 来源、媒体元数据和来源覆盖文件。

不要把玩家的 `~/.modpedia/ai.json`、API Key、用户级 Worker 库、会话数据库或本地 `knowledge.db` 打包进整合包。完整目录规则见 [`INSTALL.md`](../INSTALL.md) 和 [`README.md`](../README.md)。

## 项目与许可证

- 项目主页：[github.com/ct-yx/modpedia](https://github.com/ct-yx/modpedia)
- 当前版本：[v1.4.0](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0)
- 技术标识：`modpedia` / `io.ctyx.modpedia`
- 许可证：[Apache License 2.0](../LICENSE)

第三方修改或再发布时请保留原作者 `ctyx`、版权和许可证声明，并明确标注修改内容；不得以原作者名义宣传修改版。将未修改的 Mod 收录进整合包不属于第三方修改。
