# 更新日志

## v0.2.0

完成 M0 稳定性收尾的大部分自动化与链路维护工作。

- 修复 AI 来源引用被全局移除的问题：来源标注现在保留在对应正文位置，点击可直接跳转 Patchouli、GuideME 或 Modonomicon 页面，失败时回退来源预览。
- 仅搜索模式、模拟会话和旧历史会话统一使用正文内来源标注，不再在回答底部重复堆叠来源按钮。
- 更新 Markdown 渲染、来源标注布局、点击命中区域和历史引用迁移测试。
- 固定 Minecraft 1.21.1、NeoForge 21.1.244、Java 21 和 ModernUI 3.12.0.2 的发布基线。
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
