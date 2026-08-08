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

默认仅在以下情况触发增量更新：

- 新增模组。
- 删除模组。
- 模组版本变化。
- 手册资源发生变化。
- 玩家手动点击“重新建立索引”。

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
