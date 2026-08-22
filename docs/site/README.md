# ModPedia GitHub Pages

这是 ModPedia 项目的静态介绍与下载页，提供中文和英文两个页面，使用原生 HTML/CSS，可直接部署到 GitHub Pages，不需要 Node.js 或构建工具。

- 中文：[`index.html`](index.html)
- English: [`index.en.html`](index.en.html)

## 赞助渠道

如果 ModPedia 对你有帮助，可以通过以下已部署的赞助渠道支持后续维护：

- [爱发电](https://ifdian.net/a/Ct_yx)
- [Buy Me a Coffee](https://buymeacoffee.com/ctyx)

赞助完全自愿，不影响本地知识库和“仅搜索”模式的使用。中英文页面的“支持项目”入口均指向这两个渠道。

## 页面内容边界

`main` 是网页和发布文件的唯一维护分支。页面只展示已经发布或已经在仓库文档中确认的
功能，不把本地 JAR、知识库、会话、API Key、诊断报告或 `~/.modpedia/` 内容提交到
`docs/site/`。当前下载入口固定指向 `v1.2.0-fix`；新版本发布前需要同时更新中英文页面、
README、CHANGELOG 和安装/限制说明。

## Sponsorship channels

If ModPedia is useful to you, you can support continued maintenance through:

- [爱发电](https://ifdian.net/a/Ct_yx)
- [Buy Me a Coffee](https://buymeacoffee.com/ctyx)

Sponsorship is optional and does not affect the local knowledge base or Search-only mode. The Support links on both language pages use these same channels.

## Page content boundary

`main` is the only branch for website and release maintenance. The pages describe only
released or documented behavior; never commit local JARs, databases, conversations,
API keys, diagnostics, or `~/.modpedia/` contents to `docs/site/`. The current download
links point to `v1.2.0-fix`; a new release must update both language pages together with
the README, changelog, installation guide, and known limitations.

## 本地预览

```bash
cd docs/site
python3 -m http.server 8000
```

打开 <http://localhost:8000>。

## 部署方式

仓库内置 `.github/workflows/pages.yml`，推送 `main` 后会自动部署 `docs/site/`。

也可以在 GitHub 仓库设置中选择：

```text
Settings → Pages → Source: GitHub Actions
```

下载链接和项目链接目前指向 `ct-yx/modpedia` 的 `v1.2.0-fix` Release。发布新版本时同步修改 `index.html` 和 `index.en.html` 中的版本号、Release 地址和 JAR 文件名；合并到 `main` 后先检查 Pages 预览，再创建版本标签。

发布前检查：

```text
main 已包含代码与文档变更
中文/英文页面的版本号、下载地址和警告一致
页面没有本地绝对路径、API Key、Token、JAR 或运行时数据库
Markdown 链接、HTML 结构和下载地址通过静态检查
```

具体发布步骤见 [`docs/RELEASE_AND_PAGES.md`](../RELEASE_AND_PAGES.md)。
