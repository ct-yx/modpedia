# ModPedia GitHub Pages

这是 ModPedia 项目的静态介绍与下载页，提供中文和英文两个页面，使用原生 HTML/CSS，可直接部署到 GitHub Pages，不需要 Node.js 或构建工具。

- 中文：[`index.html`](index.html)
- English: [`index.en.html`](index.en.html)

## 赞助渠道

如果 ModPedia 对你有帮助，可以通过以下已部署的赞助渠道支持后续维护：

- [爱发电](https://ifdian.net/a/Ct_yx)
- [Buy Me a Coffee](https://buymeacoffee.com/ctyx)

赞助完全自愿，不影响本地知识库和“仅搜索”模式的使用。中英文页面的“支持项目”入口均指向这两个渠道。

## Sponsorship channels

If ModPedia is useful to you, you can support continued maintenance through:

- [爱发电](https://ifdian.net/a/Ct_yx)
- [Buy Me a Coffee](https://buymeacoffee.com/ctyx)

Sponsorship is optional and does not affect the local knowledge base or Search-only mode. The Support links on both language pages use these same channels.

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

下载链接和项目链接目前指向 `ct-yx/modpedia` 的 `v1.2.0-fix` Release。发布新版本时同步修改 `index.html` 和 `index.en.html` 中的版本号、Release 地址和 JAR 文件名。
