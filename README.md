# ModPedia · 模组百科

ModPedia 是一个面向 Minecraft 整合包的本地模组知识助手：它读取已安装模组中的手册资源，转换为统一 Markdown，写入 SQLite 检索库，再以搜索结果或 AI 回答的方式呈现，并保留原手册来源跳转。

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Beta Release](https://img.shields.io/github/v/release/ct-yx/modpedia?include_prereleases&label=beta)](https://github.com/ct-yx/modpedia/releases)

## 当前版本

| 项目 | 版本/状态 |
| --- | --- |
| Mod | **v1.0.0-beta.1** |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.244**（兼容 **21.1.x**） |
| Java | **21** |
| Mod ID | **modpedia** |
| 客户端 UI 依赖 | ModernUI **3.12.0.2** |
| 作者 | **ctyx** |

首个测试版的 JAR、校验文件、安装说明和已知限制位于 [GitHub Release](https://github.com/ct-yx/modpedia/releases/tag/v1.0.0-beta.1)。

## 快速安装

### 必需环境

1. 安装 Minecraft **1.21.1**。
2. 安装 NeoForge **21.1.x**。
3. 安装 Java **21**。
4. 安装客户端 ModernUI：从 [3.12.0.4 Release](https://github.com/BloCamLimb/ModernUI-MC/releases/tag/3.12.0.4) 下载：

   ~~~text
   ModernUI-NeoForge-1.21.1-3.12.0.2-universal.jar
   ~~~

### 安装步骤

1. 下载 **modpedia-1.0.0-beta.1.jar**。
2. 将 ModPedia 和 ModernUI JAR 放入实例的 **mods/** 目录。
3. 启动游戏，进入单人世界或服务器。
4. 按 **K** 打开助手。
5. 等待首次知识库构建完成；需要立即重建时按 **F9**。

ModPedia 不捆绑 Patchouli、GuideME、Modonomicon 或内容模组。它们作为可选手册适配对象存在，实际正文来自整合包中安装的内容模组。

## 第一次使用

### 仅搜索模式

适合没有 AI API 或希望完全离线使用的情况：

1. 按 **K** 打开助手。
2. 打开“设置”。
3. 将“工作模式”切换为“仅搜索”。
4. 在输入区输入模组、机器、物品或配方关键词。

此模式直接读取本地 SQLite，返回完整 Markdown 段落、标题路径、匹配分和来源卡片，不读取 API 配置。

### AI 回答模式

在设置中选择“AI 回答”，填写：

- API 地址：兼容 Chat Completions 的接口地址；
- 模型名称；
- API Key：设置页输入，或使用环境变量 MODPEDIA_API_KEY。

模型可以调用 search_knowledge。当配方、步骤、前置条件或版本证据不足时，会继续改写查询并补充检索；最终回答只引用本轮实际搜索到的来源。

默认搜索预算：

| 档位 | 最大搜索轮数 | 每轮结果 | 上下文上限 |
| --- | ---: | ---: | ---: |
| 快速 | 1 | 4 | 8,000 字符 |
| 标准 | 3 | 8 | 16,000 字符 |
| 深入 | 5 | 12 | 28,000 字符 |

## 快捷键与界面

| 操作 | 行为 |
| --- | --- |
| **K** | 打开/关闭助手浮窗 |
| **Esc** | 输入框优先失焦，否则关闭当前页面 |
| **Enter** | 发送单行问题 |
| **Shift+Enter** | 在输入框中换行 |
| **F8** | 保留原版电影视角 |
| **F9** | 强制重建本地知识库 |
| 标题栏拖动 | 移动浮窗 |
| 四边/四角拖动 | 缩放浮窗 |

浮窗特性：

- 默认尺寸 **320×400**，最小尺寸 **160×110**；
- 最大尺寸 **720×720**，宽高分别不超过视口的 **85%**；
- 历史和设置在同一个助手窗口内打开，不创建全屏二级 Screen；
- 设置字段、历史列表和按钮随父窗口约束并使用 scissor 裁剪；
- 游戏背景保持清晰，只绘制蓝光半透明面板；
- 主题色、透明度和发光效果保存在 assistant-glass.json；
- 高对比度或减少透明度时回退到不透明表面；
- 折叠输入只保留右下角的小型入口，展开后使用单行输入。

## 手册来源：框架与内容模组

Patchouli、GuideME 和 Modonomicon 主要是手册框架或前置库，框架 JAR 本身可能没有任何正文。搜索覆盖率取决于真正提供手册资源的内容模组 JAR。

| 格式 | 扫描内容 | 来源跳转 |
| --- | --- | --- |
| Patchouli | 书籍、分类、条目、页面和常见页面节点 | 书籍/条目 |
| GuideME | Markdown 页面、语言目录和页面索引 | 书籍/页面 |
| Modonomicon | 书籍、分类、条目、页面和未知节点 | 书籍/条目/页面 |

扫描器会保留：

- 内容模组 namespace；
- sourceType、sourcePath 和版本；
- 文档 ID、标题路径和页级锚点；
- 中文、英文和 neutral 回退信息。

测试时应同时安装手册框架和实际内容模组。只安装三个框架库可以验证加载兼容性，但正文数量通常不会增加。

## 本地知识库

运行时目录：

~~~text
config/modpedia/knowledge/
├── generated/       # 自动扫描手册生成的 Markdown
├── custom/          # 玩家维护的 Markdown 源文件
├── cache/           # 构建报告、扫描缓存和索引
├── knowledge.db     # SQLite 派生搜索库
└── state.json       # JAR 与资源指纹
~~~

### 自定义文档

将人工维护的文件放入 custom/。使用稳定 id，建议声明语言：

~~~markdown
---
id: mypack:automation
language: zh_cn
title: 自动化说明
keywords: [自动化, automation]
source_type: manual_annotation
---

# 自动化说明

这里保留完整 Markdown，供搜索和 AI 读取。
~~~

每次启动会执行：

~~~text
扫描 custom/*.md
  → 读取 id、language 和 SHA-256 指纹
  → 只导入新增/修改文件
  → 删除已移除文件对应记录
  → 事务提交 SQLite
  → RetrievalService.reload()
~~~

原始 Markdown 始终保留，SQLite 只是可重建的派生搜索库。自定义文档优先于自动扫描文档；Front Matter 错误或事务失败时保留上一份有效记录。

## 开发

### 项目结构

~~~text
src/main/java/io/ctyx/modpedia/
├── ai/           # AI 客户端、工具调用、上下文和会话
├── knowledge/    # 手册扫描、转换、增量构建和自定义导入
├── search/       # SQLite、FTS 和规则检索
└── client/       # NeoForge 客户端 UI、来源预览和手册跳转

docs/
├── ARCHITECTURE.md
├── DEVELOPMENT.md
└── KNOWLEDGE_BASE.md
~~~

### 本地运行

运行客户端前，将 ModernUI 和测试用内容模组放入 run/mods/：

~~~bash
./gradlew runClient
~~~

无图形环境时，客户端窗口回归需要转移到实际桌面环境；纯 Java 测试和构建仍可执行。

### 验证命令

~~~bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
~~~

知识库规模和双语搜索基准：

~~~bash
./gradlew knowledgeBenchmark
~~~

报告输出到：

~~~text
build/reports/modpedia/knowledge-benchmark.json
build/reports/modpedia/knowledge-benchmark.md
~~~

详细的 Mod 开发清单、手动回归和发布流程见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 已知限制

- 只扫描整合包中已安装 JAR 的本地手册资源，不联网下载手册。
- 不同版本或第三方改版可能改变资源路径，影响扫描覆盖率。
- 手册框架没有正文时只作为依赖型 JAR 统计；正文覆盖率取决于内容模组。
- 具体来源跳转依赖目标手册模组的客户端公开入口；入口缺失时保留来源预览。
- AI 回答需要玩家自行配置兼容 API；仅搜索模式可以完全离线使用。
- 当前仅搜索使用规则检索，向量检索仍属于后续增强方向。
- 首次启动或 F9 重建期间，知识库尚未完成时搜索结果可能暂时为空。

完整列表见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。

## 相关文档

- [Mod 开发清单](docs/DEVELOPMENT.md)
- [架构设计](docs/ARCHITECTURE.md)
- [知识库设计](docs/KNOWLEDGE_BASE.md)
- [更新日志](CHANGELOG.md)
- [安装说明](INSTALL.md)
- [已知限制](KNOWN_LIMITATIONS.md)
- [Beta Release](https://github.com/ct-yx/modpedia/releases/tag/v1.0.0-beta.1)

## 作者与许可证

- 作者：ctyx
- Mod ID：modpedia
- 包名：io.ctyx.modpedia
- 许可证：当前元数据标记为 All Rights Reserved。
