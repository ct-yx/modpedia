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
AssistantScreen（非暂停客户端 Screen）
    │
    ├── FloatingAssistantWindow
    ├── MessageList / MessageBubble
    ├── AssistantInput
    └── SourceCard / SourceNavigator
```

## 2. 模块职责

### `knowledge`

负责从本地模组资源中发现手册文件，解析不同格式，并输出统一 Markdown。转换器不直接调用 AI。

### `search`

第一版使用模组名、页面标题、物品 ID、标签、分类和同义词进行关键词检索。接口预留向量检索实现，但基础运行不依赖向量模型。

### `ai`

负责 API 请求、会话、超时、取消、上下文长度和来源引用。网络请求在后台线程执行，界面线程只接收状态更新。

### `client`

只在物理客户端加载，负责助手窗口、输入框、消息列表、加载状态、错误提示和来源预览。

阶段四的职责边界如下：

```text
ModPediaClient
  ├── K        → Minecraft.setScreen(AssistantScreen)
  ├── F9       → KnowledgeUpdateService.rebuildAsync()
  └── ClientTickEvent.Post

AssistantScreen
  ├── WindowBounds          → 尺寸、视口比例、边距和拖拽缩放
  ├── AssistantWindowConfig → 客户端 JSON 持久化
  ├── AssistantSession      → 线程安全的会话状态接口
  ├── KnowledgeUpdateService.status() → 顶部只读状态快照
  └── ModernUiBridge        → 可选运行时背景模糊入口
```

界面层不直接读取手册 JAR，也不把知识库构建线程的对象暴露给渲染线程；渲染只读取 `KnowledgeStatus` 和 `AssistantUiState` 快照。

### 客户端浮窗约束

`WindowBounds.clampTo(viewportWidth, viewportHeight)` 同时约束：

```java
min = 180×140;
max = min(720×720, viewport×85%);
safeArea = viewport - 12px;
```

缩放以鼠标按下时的窗口快照为基准，四边和四角分别改变对应边；每次拖动、游戏窗口缩放、关闭和移除 Screen 都会进行约束或保存。

### 局部玻璃模糊与半透明回退

`ModernUiBridge` 通过反射调用 ModernUI 1.21.1 的 `icyllis.modernui.mc.BlurHandler.INSTANCE.blur(Screen)`。助手每帧先把主帧缓冲复制到 `TextureTarget`，再让 ModernUI 处理模糊，最后用四个裁剪区域把清晰副本恢复到窗口外；窗口区域保留模糊结果，再叠加 `AssistantGlassConfig` 生成的蓝色半透明玻璃。标题、消息、输入框和边框在最后绘制，保持清晰。ModernUI 入口不可用时只使用可调色半透明表面，高对比度或减少透明度模式使用不透明调色板。客户端源码不把第三方类加载到公共或 Dedicated Server 路径。

玻璃配置文件示例：

```json
{
  "themeColor": "#4D9CFF",
  "backgroundOpacity": 0.70,
  "glow": 0.78
}
```

## 3. 首次启动策略

首次启动不联网下载资料，而是读取当前实例中已安装模组的本地资源。这样可以保证知识内容与玩家实际使用的模组版本一致。

联网资料导入后续作为独立的构建工具，不放入运行时首启流程。

## 4. 更新策略

使用以下信息计算资源指纹：

```text
模组 ID + 模组版本 + 手册资源路径 + 资源内容哈希
```

启动时读取 `config/modpedia/knowledge/state.json`：

- 指纹未变化且生成文件存在：复用现有 Markdown。
- 新增来源或指纹变化：重新转换该来源。
- 当前扫描中不存在的旧来源：删除对应生成文件。
- `manifest.json`、`keyword-index.json` 和扫描报告每次都会重新生成。

按键 `F9` 触发强制完整转换和索引重建；后台任务正在运行时，重复请求会被忽略。

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

`KnowledgeUpdateService.status()` 以原子引用发布 `KnowledgeStatus`，浮窗顶部读取来源数、文档数、更新时间及更新/错误状态，不等待后台构建线程。
