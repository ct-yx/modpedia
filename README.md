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
- 增量更新本地知识库
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

## 配置原则

API 地址、模型名称和 API key 只保存在玩家本地配置中。示例配置和文档只使用占位符。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [开发流程](docs/DEVELOPMENT.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)

## 作者

`ctyx`
