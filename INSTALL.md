# ModPedia v1.1.0 安装说明

## 运行环境

- Minecraft `1.21.1`
- NeoForge `21.1.244` 或兼容的 `21.1.x`
- Java `21`
- 客户端 UI 使用 NeoForge 原生 GUI API，ModPedia 没有外部 UI 模组依赖

## 安装

1. 安装目标整合包对应的 NeoForge 客户端。
2. 将 `modpedia-1.1.0.jar` 放入该实例的 `mods/` 目录。
3. 启动游戏并进入世界；ModPedia 会在后台扫描已安装模组的本地手册。
4. 按 `K` 打开助手，按 `F9` 手动重建知识库。

ModPedia 不捆绑 Patchouli、GuideME、Modonomicon 或内容模组。手册框架是可选适配对象，实际手册正文来自对应内容模组的 JAR。

FTB Quests、JEI、Jade 也是可选联动，不影响 ModPedia 基础加载：分别提供任务快照/任务 Wiki、物品配方跳转和视线目标插入。

## 工作模式

- **仅搜索**：打开设置，选择“仅搜索”，无需填写 API 配置即可使用本地 SQLite 知识库。
- **AI 回答**：选择“AI 回答”，填写兼容 Chat Completions 的 API 地址和模型名称；API Key 可填写在设置中，也可通过 `MODPEDIA_API_KEY` 提供。

## 校验

在下载目录执行：

```bash
shasum -a 256 modpedia-1.1.0.jar
```

将结果与 `SHA256SUMS` 对比。
