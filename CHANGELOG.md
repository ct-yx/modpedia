# 更新日志

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
