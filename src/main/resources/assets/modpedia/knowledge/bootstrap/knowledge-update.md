---
id: modpedia:bootstrap/knowledge-update
source_mod: modpedia
source_type: builtin
title: 知识库更新说明
category: bootstrap
keywords: ['知识库', '更新', '手册', 'Markdown']
source_version: '0.2.0'
source_path: 'assets/modpedia/knowledge/bootstrap/knowledge-update.md'
---

# 知识库更新说明

首次启动时，ModPedia 会读取当前实例中已安装模组的本地手册资源，并生成 Markdown 文件、清单、关键词索引和统一的 `knowledge.db`。

自动生成内容位于 `config/modpedia/knowledge/generated/`，玩家手工补充内容位于 `config/modpedia/knowledge/custom/`，可扩展 Wiki 位于 `sources/<source-id>/`。启动时会按资源指纹增量更新；按 `F9` 可强制重建文本知识库。

模组手册、Wiki 和任务运行数据共用 `knowledge.db`，但分别使用 `content_kind=mod_manual`、`wiki` 和 `task_runtime`。FTB Quests、JEI、Jade 均为可选联动；没有安装它们时，基础手册搜索仍可使用。
