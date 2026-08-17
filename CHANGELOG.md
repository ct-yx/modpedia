# 更新日志

## v1.0.1

首个包含本地密钥保护和 Mod 列表图标的正式维护版本。

- `config/modpedia/runtime/ai.json` 只保存系统标识派生的 AES-GCM API Key 密文，Worker 启动时解密并复用内存缓存。
- 系统标识变化、密文损坏和旧版明文配置均有对应处理；日志不记录 API Key 或系统 UUID。
- 为 Mod 元数据增加 Mod 列表图标和更完整的功能介绍，图标随 JAR 一起发布。
- 更新 README、安装说明、开发路线、已知限制和 GitHub Pages 下载链接。
- 通过 `./gradlew test`、`./gradlew build` 和 `git diff --check` 验证。

## v1.0.0-fix

面向 `v1.0.0` 的修复预发布版本，用于大型整合包和真实客户端回归。

- `config/modpedia/runtime/ai.json` 不再保存 API Key 明文，改为使用系统标识派生的 AES-GCM 密文。
- Worker 启动时完成一次设置解密，后续 AI 请求复用内存缓存；系统标识变化或密文损坏时清除密钥字段。
- 旧版明文配置自动迁移；系统标识读取失败时使用运行目录中的安装级回退标识。
- 增加 API Key 密文、旧配置迁移、系统标识变化、密钥清除和内存缓存回归测试。
- 同步更新 README、安装说明、开发文档和 GitHub Pages 下载链接。

## v1.0.0

首个正式稳定版本，基于 `v0.3.0` 的大型整合包测试结果完成发布整理。

- 保持 Minecraft `1.21.1`、NeoForge `21.1.x`、Java `21` 和 `modpedia` 技术标识稳定。
- 保留统一 Schema v7 `knowledge.db`、独立 Worker、FTS5 检索、物品目录、FTBQ 静态任务定义和可选联动。
- 修复 Worker 本地 FTBQ 运行时文件性能自测在 CI 机器负载下的随机失败；默认验证读取正确性并记录延迟，严格 p95 门禁可显式开启。
- 完善整合包发布时的 `config/modpedia/` 清理和作者 Wiki 源文件保留说明，避免发布 API 配置、历史会话和派生数据库。
- 更新 README、安装说明、路线图、项目入门、GitHub Pages 下载链接和内置知识库版本信息。

自动化测试、构建和严格性能自测通过；大型整合包人工回归记录及当前已知限制见
[`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)。

## v0.3.0

本版本完成统一知识库、独立 Worker、物品目录和可选联动的整合，并通过自动化与大型整合包回归。

- 统一使用 Schema v7 的 `knowledge.db` 保存模组手册、Wiki、FTBQ 静态任务定义和物品目录；旧结构检测后删除派生库并从原始来源重建。
- FTS5 改为 external-content，完整 Markdown 从 `segments` 事实表读取；加入 `PRAGMA optimize`、大批量 optimize/merge、`rank` 排序和冷/热双语性能基准。
- 增加 `knowledge_sources` 来源注册表、`sources/<source-id>/` Wiki 目录和 APP/Modonomicon 书籍的 `content_kind` 分类覆盖。
- 增加 FTB Quests 静态任务快照、依赖、要求和随机奖励响应；内置任务 Wiki 在后台尝试更新，网络失败时保留本地副本。
- 修正 FTB Quests 读取链路：移除客户端 Tick 全量扫描；单机优先由 Worker 直接读取当前存档的轻量 SNBT 进度文件，多人或本地文件不可用时回退 TeamData，随后再从 SQLite 抽取静态任务定义；局部进度只在 TaskRuntimeSnapshot 内存覆盖，同一 AI 请求只执行一次。
- 增加 JEI 配方跳转、Jade 视线目标插入和物品 ID/名称渲染协议；FTB Quests、JEI、Jade 均保持可选。
- 更正客户端 UI 依赖声明：助手界面由 `AssistantScreen` 和 `FloatingAssistantWindow` 基于 NeoForge 原生 GUI API 自绘，不再要求外部 UI 模组。
- 更新 README、架构、知识库、开发清单、路线和已知限制，明确手册框架与内容模组的边界。

## v0.2.0

完成 M0 稳定性收尾的大部分自动化与链路维护工作。

- 修复 AI 来源引用被全局移除的问题：来源标注现在保留在对应正文位置，点击可直接跳转 Patchouli、GuideME 或 Modonomicon 页面，失败时回退来源预览。
- 仅搜索模式、模拟会话和旧历史会话统一使用正文内来源标注，不再在回答底部重复堆叠来源按钮。
- 更新 Markdown 渲染、来源标注布局、点击命中区域和历史引用迁移测试。
- 固定 Minecraft 1.21.1、NeoForge 21.1.244 和 Java 21 的发布基线。
- 发布流程同时支持正式版和预发布标签，构建产物自动生成 SHA-256 校验文件。
- 统一发布显示名为 `ModPedia · 模组百科 <version>`。

真实客户端 GUI Scale 4、三种手册跳转和大型整合包回归仍需在目标实例中继续验证，详见 [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)。

### 历史版本命名说明

`v1.0.0-beta.1` 和 `v1.0.0-beta.2` 是早期版本序列形成时创建的历史技术标签，为保持已有下载链接不变继续保留；GitHub Release 显示名称已统一为 `ModPedia · 模组百科` 前缀。从本版本起使用 `v0.2.0` 版本序列。

## v1.0.0-beta.2

首个完成 AI 持久化上下文维护的公开测试版。

- 使用 LangChain4j Community SQL `SQLChatMemoryStore` 持久化 AI 上下文，保留 SQLite 本地部署方式。
- 旧版会话中的 LangChain4j memory JSON 自动迁移到 `conversations/memory.sqlite`。
- 保留完整工具调用消息顺序和原始 `tool_call_id`，修复重试后工具结果链路丢失问题。
- 保留 LangChain4j `TokenWindowChatMemory`、搜索工具循环、流式响应和仅搜索模式。
- 补充 AI 模型兼容性自测、上下文恢复测试、Markdown 渲染测试和社区依赖说明。

这是测试版，真实大型整合包和不同模型的客户端回归仍请通过 Issues 反馈。

## v1.0.0-beta.1

首个公开测试版。

- 支持 Patchouli、GuideME 和 Modonomicon 手册资源扫描、转换、SQLite 检索及来源跳转。
- 支持中文、英文和 `neutral` 文档回退。
- 助手窗口支持移动、缩放、半透明蓝光主题、历史和设置二级页面。
- 设置支持 AI 回答与仅搜索两种模式。
- AI 模式使用 LangChain4j 管理工具调用、上下文窗口、流式响应、取消和历史会话。
- 仅搜索模式不需要 API 地址、模型或 API Key，直接返回完整 Markdown 段落和来源卡片。
- 自定义 Markdown 在启动时按稳定 ID 和 SHA-256 指纹增量导入 SQLite。
- 修复小窗口设置页的二次缩放导致的文字/输入框错位，并将折叠输入改为紧凑入口。

## 已知限制

详见 [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)。
