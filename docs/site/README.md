# ModPedia GitHub Pages

这是 ModPedia 的静态介绍与下载页，提供中文和英文页面，使用原生 HTML/CSS，可直接部署到 GitHub Pages。

- 中文：[`index.html`](index.html)
- English：[`index.en.html`](index.en.html)
- 当前版本：[`v1.4.0`](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0)

## 页面内容

下载区包含三个发布文件和纵向功能矩阵：

- Minecraft 1.21.1 + NeoForge 21.1.x；
- Minecraft 1.20.1 + Forge 47.x；
- Minecraft 1.12.2 + Forge 14.23.5.2847 / Cleanroom 0.3+ 兼容线（同一 JAR，CurseForge 文件按 Forge 1.12.2 标记）。

三条版本线均已完成当前 v1.4.0 发布测试。

页面说明手册框架与内容模组的区别，并列出 Patchouli、GuideME/Guide-API、Modonomicon/APP、1.12.2 Mantle/匠魂手册、自定义 Markdown、Wiki、FTBQ、JEI、Jade、物品目录、AI、仅搜索和 Worker 隔离的版本支持情况。

## 赞助渠道

如果 ModPedia 对你有帮助，可以通过以下已部署渠道支持维护：

- [爱发电](https://ifdian.net/a/Ct_yx)
- [Buy Me a Coffee](https://buymeacoffee.com/ctyx)

赞助完全自愿，不影响本地知识库和“仅搜索”模式。

## 内容边界

`main` 是网页和发布文件的唯一维护分支。页面只展示已构建或已在仓库文档确认的功能，不提交本地 JAR、知识库、会话、API Key、诊断报告、用户目录或构建缓存。

整合包发布警告明确区分：

- 发布前删除 `config/modpedia/runtime/` 及其 `knowledge.db*`、会话、诊断、Worker 日志和临时载荷；
- 保留 `config/modpedia/knowledge/custom/**/*.md`、`sources/<source-id>/source.json`、`documents/**/*.md`、`media.json`、`source-overrides.json` 和可选同义词；
- 不复制用户级 `~/.modpedia/ai.json`、`installation-id` 和 `worker/lib/worker-baseline-3/`。

## 本地预览

```bash
cd docs/site
python3 -m http.server 8000
```

打开 <http://localhost:8000>，检查中英文页面、下载链接、表格横向滚动、赞助链接和整合包警告。

## 部署方式

仓库内置 `.github/workflows/pages.yml`，推送 `main` 后自动部署 `docs/site/`。也可以在 GitHub 仓库设置中选择：

```text
Settings → Pages → Source: GitHub Actions
```

发布新版本时同步修改 `index.html`、`index.en.html`、README、CHANGELOG、INSTALL 和已知限制；下载链接必须使用 Release 中实际生成的三份 JAR 文件名。

发布前检查：

```text
main 已包含文档、网页和发布工作流变更
中文/英文页面版本号、下载地址、功能矩阵一致
页面没有 API Key、Token、本地绝对路径或运行时数据库
Markdown 链接、HTML 结构和下载链接通过静态检查
```

具体发布步骤见 [`../RELEASE_AND_PAGES.md`](../RELEASE_AND_PAGES.md)。

---

# ModPedia GitHub Pages (English)

The site is a static Chinese/English landing and download page built with plain HTML/CSS.

- Chinese: [`index.html`](index.html)
- English: [`index.en.html`](index.en.html)
- Current release: [`v1.4.0`](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0)

The download section contains three JARs and a vertical feature matrix for NeoForge 1.21.1,
Forge 1.20.1, and the Forge 1.12.2 / Cleanroom 0.3+ compatibility line. The same 1.12.2 JAR
supports both targets; its CurseForge file uses the Forge 1.12.2 platform marker because Cleanroom
is Forge-API compatible. The 1.12.2 line also supports Mantle/Tinkers' Construct manuals.

The site must not contain local JARs, databases, conversations, API keys, diagnostics, user paths,
or build caches. Before publishing a modpack, remove `config/modpedia/runtime/` and keep only the
reusable `config/modpedia/knowledge/` source files. User-level `~/.modpedia/ai.json`,
`installation-id`, and `worker/lib/worker-baseline-3/` never belong in a modpack.
