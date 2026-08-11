---
id: 'wiki:ftbquests-wiki/ftb-quests'
source_type: 'wiki_markdown'
content_kind: 'wiki'
source_id: 'ftbquests-wiki'
collection_id: 'ftbquests-wiki'
origin_type: 'remote'
language: 'neutral'
title: '任务模组使用说明'
category: 'overview'
priority: 60
keywords: ['任务', '任务树', '进度', '奖励', '依赖', 'quest', 'task', 'progress', 'reward']
---

# 任务模组使用说明

本地助手会在检测到可选任务模组后，把任务定义和当前玩家进度分别保存到统一的 `knowledge.db` 中。任务定义包括任务标题、说明、依赖、要求和奖励；实时数据会标注为当前世界的运行快照。

## 查询任务

可以询问当前可行的下一步、某个任务的要求、任务为什么被阻塞，以及任务奖励。多个任务同时满足条件时，助手会返回候选列表，不把其中一个猜成唯一主线。

## 随机奖励

奖励箱或随机奖励只表示候选列表。助手会将其标记为 `is_random=true`、`guaranteed=false`，候选物品不能当作必定获得的奖励。

## 数据来源

这份文档是离线启动时的内置说明；客户端会在后台尝试从配置的远程来源更新它。网络不可用时继续使用当前本地副本，原始 Markdown 不会被删除。
