# ModPedia 架构设计

## 1. 总体流程

```text
本地模组资源
    │
    ▼
KnowledgeSourceScanner
    │
    ▼
KnowledgeCompiler
    │
    ├── Markdown 文档
    ├── manifest.json
    └── keyword-index.json
    │
    ▼
KnowledgeRepository
    │
    ▼
RetrievalService
    │
    ▼
ContextAssembler
    │
    ▼
AiClient
    │
    ▼
AssistantScreen
```

## 2. 模块职责

### `knowledge`

负责从本地模组资源中发现手册文件，解析不同格式，并输出统一 Markdown。转换器不直接调用 AI。

### `search`

第一版使用模组名、页面标题、物品 ID、标签、分类和同义词进行关键词检索。接口预留向量检索实现，但基础运行不依赖向量模型。

### `ai`

负责 API 请求、会话、超时、取消、上下文长度和来源引用。网络请求在后台线程执行，界面线程只接收状态更新。

### `client`

负责助手窗口、输入框、消息列表、加载状态、错误提示和来源跳转。

## 3. 首次启动策略

首次启动不联网下载资料，而是读取当前实例中已安装模组的本地资源。这样可以保证知识内容与玩家实际使用的模组版本一致。

联网资料导入后续作为独立的构建工具，不放入运行时首启流程。

## 4. 更新策略

使用以下信息计算资源指纹：

```text
模组 ID + 模组版本 + 手册资源路径 + 资源内容哈希
```

指纹没有变化时直接加载缓存；指纹变化时只重新转换变化的来源。

## 5. 数据流约束

- 生成 Markdown 与玩家自定义 Markdown 分离。
- AI 只接收检索到的文档片段，不接收整本手册。
- 每次回答记录来源文档 ID。
- API key 不进入日志、知识库和提交记录。

## 6. 当前类结构

```text
io.ctyx.modpedia.knowledge/
├── LocalGuideScanner
├── ScannedResource
├── MarkdownDocumentConverter
├── JsonGuideDocumentConverter
├── KeywordExtractor
├── KnowledgeCompiler
└── KnowledgeUpdateService
```

`KnowledgeUpdateService` 只在客户端初始化后启动后台任务；公共 Mod 入口不直接引用客户端类。
