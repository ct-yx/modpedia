# ModPedia · 模组百科

`ModPedia` 是一个面向整合包的 AI 模组知识助手。

它在本地读取整合包中已安装模组的手册资源，将 JSON/Markdown 内容转换为统一格式，建立关键词索引，再把相关资料交给 AI API 生成带来源的回答。

## 当前定位

项目处于基础工程阶段，第一版目标是跑通：

```text
玩家提问 → 本地手册扫描 → Markdown 知识库 → 关键词检索 → AI 回答 → 来源跳转
```

主工程不预置几百个模组的完整百科，只内置转换格式、提示词模板和最小示例。首次启动时，ModPedia 从本地已安装模组的资源中生成知识库。

## 计划功能

- 读取 JSON 手册页面
- 读取 Markdown 手册页面
- 解析语言 key、物品、方块、配方和标签
- 转换为统一 Markdown
- 生成 `manifest.json` 和关键词索引
- 保留玩家手工编辑的知识文件
- 使用 AI API 回答模组问题
- 显示答案引用并跳转到对应百科页面
- 将向量检索作为整合包制作阶段的可选增强

## 开发环境

- 游戏版本：`1.21.1`
- Java：`21`
- 模组加载器：`21.1.244`
- Gradle Wrapper：项目内置

## 本地运行

```bash
./gradlew runClient
```

## 构建

```bash
./gradlew build
```

构建产物位于：

```text
build/libs/
```

## 知识库目录

运行时知识库位于：

```text
config/modpedia/knowledge/
├── generated/   # 自动转换内容
├── custom/      # 玩家手工补充内容
├── cache/       # 索引和扫描缓存
└── state.json   # 当前资源指纹
```

`custom/` 的内容优先级高于自动生成内容，并且不会被重新扫描覆盖。

## 项目目录

```text
src/main/java/io/ctyx/modpedia/
├── ai/           # API、会话和上下文组装
├── knowledge/    # 资源扫描、转换和缓存
├── search/       # 关键词检索与后续向量检索
└── client/       # 客户端界面与手册跳转

docs/
├── ARCHITECTURE.md
├── DEVELOPMENT.md
└── KNOWLEDGE_BASE.md
```

## 第二阶段：本地手册知识库（已完成基础版本）

- `LocalGuideScanner` 扫描已安装模组 JAR 内的本地资源。
- 支持 JSON 手册、Markdown 手册和 `zh_cn`/`en_us` 语言文件。
- `KnowledgeCompiler` 生成统一 Markdown、`manifest.json`、`keyword-index.json`、`state.json` 和扫描报告。
- 自动生成内容与 `custom/` 手工内容分开保存。
- 客户端初始化后在后台执行知识库构建。

当前识别的资源路径：

```text
data/<namespace>/patchouli_books/**/*.json
assets/<namespace>/patchouli_books/**/*.json
assets/<namespace>/guides/**/*.md
assets/<namespace>/ae2guide/**/*.md
assets/<namespace>/guideme_guides/**/*.json
```

## 第三阶段：知识库增量更新（已完成）

- 使用 `模组 ID + 版本 + 资源路径 + 内容哈希` 生成来源指纹。
- 启动时只重新转换新增或指纹变化的来源，未变化来源直接复用已有 Markdown。
- 自动清理已经移除来源对应的生成文件。
- 每次构建重建 `manifest.json`、`keyword-index.json` 和 `state.json`。
- 在 `cache/build-report.json` 中记录更新、复用、删除数量及警告。
- 客户端按 `F9` 可强制完整转换并重建索引；构建期间重复请求会被忽略。

阶段三验证覆盖首次生成、未变化来源复用、强制重建、指纹变化、玩家自定义文档合并和来源删除清理。

## 配置原则

API 地址、模型名称和 API key 只保存在玩家本地配置中。示例配置和文档只使用占位符。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [开发流程](docs/DEVELOPMENT.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)

## 作者

`ctyx`
