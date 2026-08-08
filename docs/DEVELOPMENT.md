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

### 阶段四：助手界面（已完成）

- [x] 关闭时完全隐藏，不常驻 HUD。
- [x] `K` 打开/关闭，`Esc` 按输入焦点优先级关闭。
- [x] 默认居中偏右，标题栏拖动，四边和四角缩放。
- [x] `180×140` 最小值、`720×720` 固定上限和视口 `85%` 上限。
- [x] 游戏窗口缩放后自动修正尺寸和位置；客户端 JSON 保存最后状态。
- [x] 紧凑标题栏/输入区、半透明玻璃表面、正确的背景模糊层级和无模糊回退。
- [x] 高对比度/减少透明度不透明表面回退。
- [x] 消息滚动、自动定位、单行输入、发送、取消和关闭。
- [x] 欢迎、提问、加载、来源回答、无结果、错误重试和知识库状态。
- [x] 来源预览卡片，预留 Patchouli/GuideME 跳转适配器。

当前会话实现是 `MockAssistantSession`，用于确定性验证 UI 状态；它会用 `error`/“错误”触发错误状态，用 `unknown`/“不存在”触发无结果状态。

### 阶段五：AI 调用

- 实现通用 API 客户端。
- 添加本地配置。
- 增加超时和取消。
- 限制上下文长度。
- 将来源写入回答结构。

## 3. 每次修改后的检查

```bash
./gradlew build
git diff --check
```

涉及客户端界面时，再运行：

```bash
./gradlew runClient
```

手动回归顺序：

1. 进入单人世界，确认世界继续运行；按 `K` 打开，再按 `K`/`Esc` 关闭。
2. 拖动标题栏，关闭后重新打开，确认位置保留。
3. 依次拖动四边和四角，确认尺寸始终在 `180×140`、`720×720`、`85%` 视口约束内。
4. 缩放游戏窗口，确认浮窗仍完整可见；滚轮只影响消息区域。
5. 输入一行普通问题并按 `Enter` 发送；加载时点击 `×` 取消。
6. 输入 `error` 测试错误与“重试”，输入 `unknown` 测试无匹配，点击来源卡片测试预览。
7. 确认只有窗口后方的游戏画面模糊，窗口外保持清晰，标题/消息/输入文字保持清晰；修改 `config/modpedia/assistant-glass.json` 的 `themeColor`/`backgroundOpacity`/`glow` 后重新打开助手确认样式变化；打开高对比度选项，确认面板切换为不透明；关闭 ModernUI 后确认半透明回退仍可绘制。
8. 确认 `F8` 仍为原版电影视角，`F9` 仍触发知识库重建。

Dedicated Server 回归：

```bash
./gradlew runServer
```

服务端不需要 ModernUI；`ModPediaClient`、`AssistantScreen` 和第三方 UI 反射入口均通过客户端入口与 `Dist.CLIENT` 隔离。

## 4. 发布检查

- 检查 Mod ID 和版本号。
- 检查 JAR 内资源路径。
- 检查日志中没有 API key。
- 检查生成的知识缓存未被误提交。
- 检查构建产物可以被加载。
