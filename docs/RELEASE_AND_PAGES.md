# 发布与 GitHub Pages 维护说明

本分支固定为 `release/publish-web`，用于整理发布文件、维护 GitHub Pages 和准备
版本发布；功能开发仍在对应的开发分支完成，不在本分支直接堆积临时代码。

## 目录职责

```text
.github/workflows/
├── build.yml                 # push/PR 的测试与构建
├── pages.yml                 # GitHub Pages 部署
├── publish-curseforge.yml    # 版本标签触发 CurseForge 发布
└── release.yml               # 版本标签触发 GitHub Release

docs/site/
├── index.html                # 中文发布页
├── index.en.html             # English 发布页
├── styles.css                # 页面样式
└── README.md                 # Pages 本地预览和发布说明

CHANGELOG.md                  # Release 说明的唯一事实源
README.md / README.en.md      # 项目与安装说明
INSTALL.md                    # 安装说明
KNOWN_LIMITATIONS.md          # 已知限制
SHA256SUMS                    # 由 Release 工作流临时生成，不提交仓库
```

## 发布流程

1. 从 `main` 创建或更新 `release/publish-web`。
2. 只在本分支整理发布说明、网页版本号、下载链接和工作流配置。
3. 在 `docs/site/` 本地预览中文和英文页面。
4. 推送本分支，Pages 工作流会部署 `docs/site/`，先检查页面链接和下载地址。
5. 合并到 `main` 后再创建版本标签，例如 `v1.2.1` 或 `v1.2.1-fix`。
6. 标签工作流执行测试、构建、生成 `SHA256SUMS`、发布 GitHub Release，并按配置发布
   CurseForge。

Release 工作流只从当前标签读取 `CHANGELOG.md` 中同名的第一个 `##` 区块，因此新版本
必须先在文件顶部增加对应条目；不会把后续历史版本的更新日志带入本次发布。

## 文件规则

- `docs/site/` 只保存静态网页源文件，不提交构建缓存、JAR、API Key 或运行时数据库。
- `build/`、`runs/`、`config/modpedia/runtime/` 和 `~/.modpedia/` 不属于发布网页或整合包
  的提交内容。
- Release 资产由 GitHub Actions 在标签构建时生成；仓库不保存本地 JAR 和校验文件。
- 页面中的版本号、Release 链接、JAR 下载链接必须同时更新中文和英文页面。
- 发布配置只引用 GitHub Actions Variables/Secrets，不把 CurseForge Token 或其他密钥写入文件。

## 验收

```bash
./gradlew test
./gradlew build
git diff --check
```

网页至少检查：

- 中文/英文页面均可打开；
- 下载按钮、Release 页面、README、许可证和问题反馈链接有效；
- 整合包配置清理警告与当前目录结构一致；
- 页面中没有 API Key、Token、机器用户目录或本地构建路径。
