# ModPedia 开发流程

## 1. 分支与提交

推荐分支命名：

```text
codex/<feature-name>
```

提交信息保持简短并说明实际变化，例如：

```text
feat: add local guide scanner
docs: describe knowledge cache
fix: preserve custom knowledge files
```

## 2. 开发顺序

### 阶段一：工程改名

- 使用 `modpedia` 作为 Mod ID。
- 使用 `io.ctyx.modpedia` 作为基础包名。
- 移除模板示例内容。
- 保留可构建的最小 Mod。

### 阶段二：知识库扫描（已完成基础版本）

- [x] 实现 JSON 手册扫描器。
- [x] 实现 Markdown 手册扫描器。
- [x] 解析 `zh_cn`/`en_us` 语言 key。
- [x] 输出统一文档格式。
- [x] 生成 manifest 和关键词索引。
- [x] 合并 `custom/` 手工知识。
- [ ] 接入更多页面类型的专用渲染。

### 阶段三：增量更新（已完成）

- [x] 保存资源指纹，并兼容阶段二的旧版 `state.json`。
- [x] 只重新转换新增或指纹变化的来源。
- [x] 清理已经移除的来源文件。
- [x] 生成更新、复用、删除数量和警告报告。
- [x] 提供 `F9` 手动完整重建入口。

实现约束：每次构建都会重建 `manifest.json`、`keyword-index.json` 和 `state.json`；
`cache/build-report.json` 保存本次构建报告，`state.json` 使用 schema version 2。

### 阶段四：助手界面

- 完成静态消息列表。
- 添加输入框和发送按钮。
- 添加加载、错误和空结果状态。
- 添加来源卡片与百科跳转。

### 阶段五：AI 调用

- 实现通用 API 客户端。
- 添加本地配置。
- 增加超时和取消。
- 限制上下文长度。
- 将来源写入回答结构。

## 3. 每次修改后的检查

```bash
./gradlew build
```

涉及客户端界面时，再运行：

```bash
./gradlew runClient
```

## 4. 发布检查

- 检查 Mod ID 和版本号。
- 检查 JAR 内资源路径。
- 检查日志中没有 API key。
- 检查生成的知识缓存未被误提交。
- 检查构建产物可以被加载。
