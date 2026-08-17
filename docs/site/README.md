# ModPedia GitHub Pages

这是 ModPedia 项目的静态介绍与下载页，使用原生 HTML/CSS，可直接部署到 GitHub Pages，不需要 Node.js 或构建工具。

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

下载链接和项目链接目前指向 `ct-yx/modpedia` 的 `v1.0.0-fix` Release。发布新版本时同步修改 `index.html` 中的版本号、Release 地址和 JAR 文件名。
