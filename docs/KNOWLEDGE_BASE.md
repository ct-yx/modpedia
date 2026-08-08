# ModPedia 知识库设计

## 1. 设计原则

主 Mod 只内置以下内容：

- 系统提示词模板
- 回答格式模板
- Markdown schema
- 最小使用说明
- 示例文档

具体模组知识在第一次启动时从本地安装资源生成。

## 2. 运行时目录

```text
config/modpedia/knowledge/
├── generated/
├── custom/
├── cache/
└── state.json
```

### `generated/`

由扫描器生成。重新构建时允许覆盖。

### `custom/`

玩家手工补充或修正的内容。优先级最高，始终保留。

### `cache/`

保存来源清单、关键词索引和扫描报告。

## 3. 统一 Markdown

```markdown
---
id: example:guide/basic
source_mod: example
source_type: local_guide
title: 基础说明
category: guide
keywords:
  - 基础
  - 入门
source_version: 1.0.0
---

# 基础说明

正文内容。
```

## 4. 首次启动

```text
读取模组列表
  ↓
扫描本地手册资源
  ↓
解析语言 key 和结构化数据
  ↓
转换为 Markdown
  ↓
合并 custom/
  ↓
生成 manifest 和 keyword-index
  ↓
保存 state.json
```

扫描过程必须在后台执行，并向界面报告进度。

## 5. 更新方式

启动时读取 `state.json` 中的来源指纹：

- 新增模组或手册来源：转换并写入新的 Markdown。
- 模组版本或手册内容变化：只重新转换变化的来源。
- 已删除的来源：删除对应的 `generated/` 文件。
- 没有变化的来源：复用已有 Markdown，不重复执行格式转换。
- 玩家按 `F9`：强制重新转换所有来源并重建索引。

每次构建都会更新 `manifest.json`、`keyword-index.json`、`state.json` 和 `cache/build-report.json`。报告包含 `updatedCount`、`reusedCount`、`removedCount` 和警告列表。

## 6. 检索规则

第一版按照以下字段建立关键词索引：

- 模组 ID 和显示名
- 页面标题
- 物品和方块 ID
- 分类
- 标签
- 同义词
- 页面正文中的重点词

单次回答只选取少量相关文档片段，并在上下文中保留文档 ID、标题和来源模组。

## 7. 向量索引

向量索引作为整合包制作阶段的可选输出：

```text
Markdown → 分块 → 嵌入 → 向量索引 → 可选重排
```

普通运行时保留关键词检索，确保没有额外模型时仍可工作。

## 8. 当前实现

第二阶段已经接入以下运行时组件：

```text
LocalGuideScanner
  ↓
MarkdownDocumentConverter / JsonGuideDocumentConverter
  ↓
KnowledgeCompiler
  ↓
config/modpedia/knowledge/
```

扫描器读取已安装模组 JAR 内的资源，不在首次启动时联网下载资料。支持：

- `data/<namespace>/patchouli_books/**/*.json`
- `assets/<namespace>/patchouli_books/**/*.json`
- `assets/<namespace>/guides/**/*.md`
- `assets/<namespace>/ae2guide/**/*.md`
- `assets/<namespace>/guideme_guides/**/*.json`
- `assets/<namespace>/lang/zh_cn.json`
- `assets/<namespace>/lang/en_us.json`

`generated/` 每次重新构建时由扫描结果生成；`custom/` 的 Markdown 作为高优先级覆盖内容合并进 manifest 和关键词索引。

Patchouli 书籍页面按每本书独立选择语言：存在 `zh_cn` 时只读取中文页面，否则回退到 `en_us`；其他语言页面不会重复进入知识库。GuideME Markdown 同时识别标准 `guides/`、`guideme_guides/` 和 AE2 使用的 `ae2guide/` 目录。

### 本地样本验证

使用本地提供的两组手册库与代表模组验证：

- Patchouli + PneumaticCraft：237 个 Patchouli JSON 来源。
- GuideME + Applied Energistics 2：125 个 `ae2guide` Markdown 来源。
- ModPedia 自带示例与 `custom/`：2 个生成文档、1 个自定义文档。
- 总计：364 个来源、365 个文档、0 个扫描警告；文档 ID 无重复。

每次构建都会写入：

```text
manifest.json
keyword-index.json
state.json
cache/build-report.json
```
