# 更新日志

## v1.0.0-beta.1

首个公开测试版。

- 支持 Patchouli、GuideME 和 Modonomicon 手册资源扫描、转换、SQLite 检索及来源跳转。
- 支持中文、英文和 `neutral` 文档回退。
- 助手窗口支持移动、缩放、半透明蓝光主题、历史和设置二级页面。
- 设置支持 AI 回答与仅搜索两种模式。
- AI 模式使用 LangChain4j 管理工具调用、上下文窗口、流式响应、取消和历史会话。
- 仅搜索模式不需要 API 地址、模型或 API Key，直接返回完整 Markdown 段落和来源卡片。
- 自定义 Markdown 在启动时按稳定 ID 和 SHA-256 指纹增量导入 SQLite。

## 已知限制

详见 [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md)。
