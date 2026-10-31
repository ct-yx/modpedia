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
├── worker/latest.json        # Worker Java 17 / protocol v2 最新清单
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
4. 推送 `main`，Pages 工作流会部署 `docs/site/`。如果仓库已经有正式 Release，工作流会先从该 Release 恢复已签名的
   `latest.json`，拒绝用 `pending-release` 占位清单覆盖线上版本清单；没有正式 Release 时才允许首次部署占位清单。
5. 在 `main` 创建版本标签，例如 `v1.4.0`。
6. 标签工作流执行测试、构建、生成 `SHA256SUMS`、签名并验签 Worker 清单，先使用同一次构建的已签名
   `worker/latest.json` 部署 GitHub Pages，再发布 GitHub Release，并按配置发布 CurseForge。任一签名、产物或
   Pages 部署门禁失败都不会进入后续发布步骤。

Release 工作流只从当前标签读取 `CHANGELOG.md` 中同名的第一个 `##` 区块，因此新版本
必须先在文件顶部增加对应条目；不会把后续历史版本的更新日志带入本次发布。工作流随后分别构建
NeoForge 1.21.1、Forge 1.20.1 和 Forge 1.12.2 / Cleanroom 0.3+，并上传三份 JAR 与校验文件。

可复现源码提交统一登记在 [`.github/release-inputs.json`](../.github/release-inputs.json)：Worker Core、
共享 `client-core` 和每个 Minecraft 版本各锁定一个完整 commit SHA。当前锁定 Worker Core
`c8ebf0582253da04057eae16fdc60fd7a96d5a9d`、client-core `6f422e12a4276e68ad8672bcbf8c8de200caa6c2`，
以及通过 CI 的 1.21.1 `89b30845947487902faaed1e3dfeca5f330d9504`、1.20.1
`b3c7598123df6f08b07f9d5945edec13d0ad4f4e`、1.12.2
`990dd625f148aed09a9fe73362de42df4a7a339a`。Worker Core Build run `41238803964`、1.20.1
Build run `41241009233`、1.21.1 Build run `41240250754`、1.12.2 Build run `41240401026` 和
client-core Build run `41239779106` 均成功。`release-contract.yml` 会解析全部
锁定提交、编译并运行锁定的 Java 8 `client-core` 自测（包含 Worker challenge proof 固定向量），并检查
1.12.2 Gradle 任务。修改发布输入时，只更新该 JSON 中的 canonical refs；工作流从 JSON 读取，不另存一份
容易漂移的期望 SHA。版本分支的锁定提交必须已推送，且该提交对应的 GitHub Actions 检查通过。

client-core `39903f2` 最初的嵌套 Gradle 8.8 `check` 因 `Test.failOnNoDiscoveredTests` 属性不存在而失败；
共享 client-core 已在 `6f422e1` 做条件设置兼容修复，并通过 Gradle 8.8/9.2.1 + Java 17 和 Gradle 8.8 +
Zulu Java 8 的 `clean check build`。当前登记的三个版本已各自在自己的分支同步该修复 ref；后续版本也必须从
同一 client-core ref 或更新的交接 ref 接入。

### Worker 签名配置

Release 工作流要求两个仓库级 Secret：

- `MODPEDIA_WORKER_SIGNING_KEY`：Ed25519 私钥（PEM 或 Base64 DER）；
- `MODPEDIA_WORKER_PUBLIC_KEY`：对应的 X.509 DER 公钥（PEM 或 Base64 DER）。

签名覆盖 `client-core/WorkerManifest.signaturePayload()` 定义的 UTF-8/LF canonical payload，
不覆盖 JSON 的 `signature` 字段或裸 JAR。发布步骤缺少任一 Secret、签名或验签失败都会停止，禁止
生成临时密钥。2026-11-01 使用 `gh secret list --repo ct-yx/modpedia` 审计时只发现旧的
`MODPEDIA` Secret，尚未发现上述两个正式密钥；在注册它们之前，Release 的签名门禁保持阻塞，
Pages 只继续发布 `pending-release` 占位清单。

### Pages Worker 清单时序

- `pages.yml` 在普通 `main` push 时先读取现有 Pages 源文件。若其为 `pending-release` 且已有正式 GitHub Release，
  就从最新 Release 下载并验证 `latest.json`，用它替换待部署副本；历史 Release 没有该资产时保留 pending 占位清单，
  读取错误或已有清单未签名时立即失败。
- `release.yml` 先构建 Worker 和三个版本模组，生成 canonical manifest、使用正式密钥签名并验签，再验证全部 JAR。
  通过后复制 `docs/site/` 到 Pages artifact 根目录并用 `upload-pages-artifact` / `deploy-pages` 部署，故清单路径为
  `/worker/latest.json`。Pages 部署成功后才创建 GitHub Release 并上传同一份清单。
- Release 工作流使用 `github-pages` environment 和 `pages: write`、`id-token: write` 权限。缺少密钥、验签失败、
  产物失败或 Pages 部署失败时工作流 fail closed，不生成伪签名或发布未签名清单。

## 文件规则

- `docs/site/` 只保存静态网页源文件，不提交构建缓存、JAR、API Key 或运行时数据库。
- `build/`、`runs/`、`config/modpedia/runtime/` 和 `~/.modpedia/` 不属于发布网页或整合包
  的提交内容。
- Release 资产由 GitHub Actions 在标签构建时生成；仓库不保存本地 JAR 和校验文件。
- 页面中的版本号、Release 链接、JAR 下载链接必须同时更新中文和英文页面。
- `worker/latest.json` 正式清单只能由已校验的 Worker JAR 生成；清单必须包含 SHA-256 和签名。仓库中的 `pending-release` 文件仅供 Pages 合约校验占位，不代表已有可安装发布物。
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
python3 scripts/validate_release_inputs.py .github/release-inputs.json
python3 scripts/validate_release_artifacts.py --manifest docs/site/worker/latest.json
python3 -m py_compile scripts/*.py
git diff --check
```

`main` 是发布/Pages 仓库，不再包含 Minecraft 源码、Worker 实现或 Gradle 构建入口。所有登记的版本线和
共享 Worker Core 的编译、测试及 JAR 生成均从 `.github/release-inputs.json` 锁定的提交执行。

网页至少检查：

- 中文/英文页面均可打开；
- 下载按钮、Release 页面、README、许可证和问题反馈链接有效；
- 整合包配置清理警告与当前目录结构一致；
- 页面中没有 API Key、Token、机器用户目录或本地构建路径。
