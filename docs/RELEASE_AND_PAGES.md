# 发布与 GitHub Pages 维护说明

`main` 固定用于整理发布文件、维护 GitHub Pages 和准备版本发布；功能开发仍在对应的
开发分支完成，不在 `main` 直接堆积临时代码。

Worker 协议、依赖和核心业务不在本文件中修改；相关变更先按
[WORKER_CHANGE_PROTOCOL.md](WORKER_CHANGE_PROTOCOL.md) 交给 Worker 仓库，再由 `main`
接收具体版本的适配摘要。

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

1. 在功能或版本开发分支完成代码和测试。
2. 将经过验证的改动合并到 `main`，只在 `main` 整理发布说明、网页版本号、下载链接和工作流配置。
3. 在 `docs/site/` 本地预览中文和英文页面。
4. 推送 `main`，Pages 工作流会部署 `docs/site/`，先检查页面链接和下载地址。
5. 在 `main` 创建版本标签，例如 `v1.2.1` 或 `v1.2.1-fix`。
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

## 给其他开发分支的清理 Prompt

将下面内容发送给具体版本或 Worker 对话，要求它们只维护自身代码，不重复维护发布系统：

```text
请按仓库主分支的统一发布策略整理当前开发分支：

1. 当前分支不负责 GitHub Pages、GitHub Release 或 CurseForge 发布，不修改发布网页和发布工作流。
2. 删除当前分支中重复的发布管理文件：
   - .github/workflows/pages.yml
   - .github/workflows/release.yml
   - .github/workflows/publish-curseforge.yml
   - docs/site/**
   - docs/RELEASE_AND_PAGES.md
   只删除确认属于发布管理的重复文件；保留 build.yml、许可证、必要的开发文档和测试说明。
3. README.md/README.en.md 与 docs/ 以 main 为唯一发布事实源。当前分支只保留代码开发所必需的 README 或 docs，
   不复制版本号、下载链接、Release 说明和 Pages 内容；需要修改这些内容时，生成摘要和补丁交给 main 对话处理。
4. 不修改 main 的发布版本号、CHANGELOG、网页下载地址或 GitHub Actions Secret 配置。
5. 完成后执行本分支适用的测试和 git diff --check，并报告被删除的重复文件、保留的开发文档和仍需由 main 处理的内容。
```

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
