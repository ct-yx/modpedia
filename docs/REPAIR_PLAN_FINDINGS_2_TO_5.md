# Findings 2–5 立即修复方案

> 本文是历史修复方案。当前实现已将知识库更新收敛到 Worker 的串行队列，并移除了
> 旧的 `knowledge/KnowledgeUpdateService`；阅读当前状态时请结合
> [`MAINTAINER_HANDOFF.md`](MAINTAINER_HANDOFF.md)。

## 目的

本方案针对审查报告中的 Findings 2–5，要求执行方只按本文实施修复，不扩大范围、不修改其他功能契约：

- Finding 2：启动期间 Wiki 更新触发的知识库重建请求被丢弃。
- Finding 3：FTB Quests 快照和 SQLite 全量写入阻塞客户端 tick 线程。
- Finding 4：远程 Wiki 响应无大小上限。
- Finding 5：任务查询在应用 `limit` 前完整加载和展开所有匹配任务。

**实现任务执行方**：后续修复会话/实现者。

**验收任务负责人**：本审查会话（Claude）。实现者不负责最终验收结论；实现完成后必须保留测试输出和变更摘要，交由本审查会话按本文逐项验收。

**范围约束**：本次只修复 Findings 2–5，不处理 Findings 1、6–9，不重构无关模块，不改变公开搜索字段含义或 Wiki 文件路径。

---

## 一、Finding 2：保证 Wiki 更新最终触发知识库重建

### 问题

启动时知识库初始构建和 Wiki 同步并行排队。Wiki 同步完成后调用 `KnowledgeUpdateService.rebuildAsync()`；如果当前构建处于 `RUNNING`，请求返回 `false` 后被丢弃，导致刚写入的 Wiki 不在当前数据库中。

### 修改文件

- `src/main/java/io/ctyx/modpedia/knowledge/KnowledgeUpdateService.java`
- `src/main/java/io/ctyx/modpedia/client/TaskWikiSyncService.java`
- 对应测试文件；优先复用现有 self-test 任务，不新增外部服务依赖

### 推荐实现

在 `KnowledgeUpdateService` 增加“待重建”语义，而不是让调用方自行轮询：

1. 增加线程安全的 pending 标志，例如 `AtomicBoolean rebuildPending`。
2. `rebuildAsync()` 的行为改为：
   - 若当前没有构建运行，立即启动一次构建；
   - 若已有构建运行，将 pending 标志设为 `true`，返回表示“已合并请求”的结果；
   - 重复请求只合并，不创建无限任务。
3. 当前构建在成功、失败或取消的 finally 路径结束后：
   - 原子读取并清除 pending 标志；
   - 若此前有 pending 请求，再启动一次构建；
   - 确保启动新构建前释放旧的 RUNNING 状态，避免自我拒绝。
4. 若执行器已关闭，清理 pending 状态并记录明确日志，不抛出未处理异常阻止客户端启动。
5. `TaskWikiSyncService` 保持“文件实际变化后请求 rebuild”的职责，不在该类添加 sleep、轮询或重复线程。
6. 对失败重建采用现有服务的失败日志/状态策略；不要因为 pending 重建而递归创建无限任务。

### 必须避免

- 不使用固定延迟或 `Thread.sleep` 解决竞态。
- 不在 Wiki 同步线程中直接调用同步编译。
- 不让每次文件变化启动多个并发 compiler。
- 不改变“文件未变化不重建”的现有行为。

### 回归测试

至少增加以下可确定性测试：

1. 模拟第一次构建处于 RUNNING；调用 `rebuildAsync()`；构建结束后验证自动出现第二次构建。
2. 在 RUNNING 期间连续调用多次 `rebuildAsync()`；验证只额外执行一次 pending 构建。
3. 构建失败后验证 pending 请求仍按约定处理，且服务不会永久停留在 RUNNING。
4. Wiki 文件变化触发 rebuild 后，最终数据库中能查询到该文件的新内容。

测试必须使用可控 fake compiler/executor 或测试钩子，不依赖真实网络和机器时序。

---

## 二、Finding 3：将 FTB Quests 写库移出客户端 tick 线程

### 问题

`FtbQuestsClientAdapter` 在 `ClientTickEvent.Post` 中同步执行完整快照生成和 `TaskKnowledgeStore.syncSnapshot()`。后者包含全量删除、插入和 SQLite 事务提交，可能造成客户端卡顿或 watchdog 风险。

### 修改文件

- `src/main/java/io/ctyx/modpedia/client/FtbQuestsClientAdapter.java`
- 必要时 `src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java` 仅做线程安全/关闭支持，不改变存储契约
- 对应客户端/任务 self-test

### 推荐实现

采用“主线程捕获快照、后台顺序写入”的模型：

1. 在客户端 tick 线程只执行必要的轻量检查和 `readSnapshot(minecraft)`，得到已经不可变的 `TaskSnapshot`。
2. 增加单线程、可关闭的后台 executor（建议每个 adapter 一个 single-thread executor），所有 `syncSnapshot` 只提交到该 executor。
3. 使用提交序号或 fingerprint 实现“只写最新快照”：
   - 每次捕获快照时生成递增 generation；
   - 后台任务开始写入前检查该任务是否仍是最新 generation；
   - 写入完成后只允许最新 generation 更新 `lastFingerprint`；
   - 旧任务不能覆盖较新的任务。
4. fingerprint 去重必须在正确线程上完成：
   - 主线程可以记录已排队/已处理 fingerprint，避免每 tick 重复提交；
   - 后台写入失败时不能把失败快照标记为成功；
   - 失败应保留重试机会并限速记录日志。
5. 在客户端退出、切换世界、玩家离开或 adapter 关闭时取消/停止 executor；不得让后台任务继续使用已失效的世界对象。
6. 后台任务只使用 `TaskSnapshot` 内的值对象，不访问 `Minecraft`、玩家、世界、NeoForge 客户端 API。
7. 保持 SQLite 写入串行，禁止并发 `syncSnapshot` 互相覆盖。
8. 若项目已有统一异步执行器，优先复用；不得新建无限线程池。

### 生命周期要求

- 不在静态初始化中启动线程。
- 明确 adapter 的创建和关闭入口；若现有生命周期没有关闭回调，补充客户端退出事件处理。
- executor 线程应为 daemon 或在客户端退出前明确 shutdown，但不能以 daemon 作为唯一正确性保障。

### 回归测试

至少增加以下测试或可自动验证的测试钩子：

1. tick 调用只提交后台任务，不直接调用 `syncSnapshot`。
2. 多次提交时写入顺序保持 generation 单调，旧快照不能覆盖新快照。
3. 相同 fingerprint 不重复写入。
4. 后台写入失败后不会错误更新成功 fingerprint。
5. 关闭/切换世界后不会执行或提交使用旧世界对象的任务。
6. 使用大型人工快照验证写库在后台执行，测试不得依赖真实客户端帧时间。

### 验收重点

验收时需检查调用栈/测试钩子，确认 `ClientTickEvent.Post` 路径不再同步进入 `TaskKnowledgeStore.syncSnapshot()`；仅“增加了线程”但仍可能并发覆盖或访问 Minecraft 对象的实现不合格。

---

## 三、Finding 4：限制 Wiki 下载大小并安全保留缓存

### 问题

`HttpResponse.BodyHandlers.ofString()` 无界缓冲完整响应。Wiki URL 可配置，超大响应会造成内存压力甚至 OOM。

### 修改文件

- `src/main/java/io/ctyx/modpedia/client/TaskWikiSyncService.java`
- 对应 Wiki self-test
- 如需配置常量，只在该服务内增加明确的最大字节数常量；不要引入新的全局配置项

### 推荐实现

1. 增加硬上限常量，例如 `MAX_WIKI_BYTES`。具体值由实现者依据现有内置 Wiki 大小和项目约定确定，但必须：
   - 大于当前合法内置/远程 Wiki；
   - 是有限且可审计的值；
   - 在代码中集中定义并在日志中说明超限行为。
2. 不再使用无界 `BodyHandlers.ofString()`。
3. 优先使用 `BodyHandlers.ofByteArray()` 后检查长度；若 HTTP 客户端版本/响应行为可能继续造成大内存分配，则改为带计数上限的流式读取。
4. 在读取前检查 `Content-Length`：已知且超过上限时立即拒绝；未知长度必须在读取过程中逐块计数，超过上限立即中止。
5. 只在完整响应读取成功、HTTP 状态为 2xx、UTF-8 解码成功且内容未超限时返回 remote 内容。
6. 超限、读取失败、解码失败或非 2xx 时：
   - 返回空结果/失败结果；
   - 保留已有本地 Wiki 文件；
   - 不触发 rebuild；
   - 记录不包含响应正文的简洁日志。
7. `URI.create`、协议、重定向策略按现有契约处理；本次不扩大为代理或任意协议设计，但至少不得因为错误 URI 让启动失败。
8. 继续使用现有 10 秒请求超时，并保证超时后连接/响应资源关闭。

### 回归测试

至少覆盖：

1. 小于上限的合法 Markdown 成功下载并可触发更新。
2. `Content-Length` 超限时拒绝且不覆盖本地文件。
3. 无 `Content-Length` 但实际流超过上限时拒绝且不覆盖本地文件。
4. 非 2xx、超时和畸形 UTF-8 时保留本地文件且不触发 rebuild。
5. 内容未变化时不触发 rebuild。

测试使用本地 fake HTTP server 或可注入 HTTP client，不访问真实远程 URL。

---

## 四、Finding 5：在 SQL 层分页，避免全量物化任务

### 问题

`TaskKnowledgeStore.query()` 先读取所有匹配任务，再为每个任务查询依赖、任务目标、奖励和进度，最后才截断到 `limit`。宽泛查询会产生不受 limit 控制的内存和数据库开销。

### 修改文件

- `src/main/java/io/ctyx/modpedia/task/TaskKnowledgeStore.java`
- `src/main/java/io/ctyx/modpedia/ai/SearchKnowledgeTool.java`（如需传递 hasMore）
- `TaskKnowledgeStoreSelfTest`
- `SearchKnowledgeTool` 对应 self-test

### 推荐实现

采用 SQL `LIMIT limit + 1`，保留一条额外记录用于准确判断 `hasMore`：

1. 在进入 SQL 前规范化 limit：使用现有 API 允许范围，拒绝/钳制负数和过大值；不要因为本次修复改变对外默认值。
2. 在 `readQuests()` SQL 的 `ORDER BY sort_index, quest_id` 后追加 `LIMIT ?`，绑定 `limit + 1`。
3. 只读取 `limit + 1` 个候选 `QuestRow`；不要在 SQL 外收集全部匹配行。
4. 注意 `matchesMode()` 可能依赖 `toResult()` 的运行时状态：
   - 如果模式过滤必须在物化后进行，读取候选时使用足够的分页策略，不能简单截断后导致合法结果不足；
   - 推荐将可由 SQL 表达的过滤（visible、optional、completed、依赖等）下推到 SQL；
   - 对无法下推的模式，使用“按 SQL 页批量读取，直到收集 limit+1 个最终匹配结果或耗尽”，每页仍有硬上限，禁止一次性无界读取。
5. 将查询结果改为显式携带 `hasMore` 的内部结果类型，或让 `TaskResponse` 提供可靠的分页信息。
6. `SearchKnowledgeTool` 只消费数据层提供的 `hasMore`，不得再使用 `quests.size() >= limit` 猜测。
7. 对关联数据避免 N+1：至少不要为超过 API limit 的任务物化详情；条件允许时批量读取依赖、任务、奖励和进度。
8. 保持最终排序稳定；分页必须以 `sort_index, quest_id` 作为稳定顺序。

### 重要边界

- `limit=0` 的行为必须先由现有契约确定并写测试，不得因追加 `limit + 1` 产生意外返回一条结果。
- `limit` 最大值仍需受现有 API 上限保护。
- `NEXT`、`SEARCH`、指定 quest ID 和 collection 过滤都必须分别验证。
- 不能只把 Java `subList` 提前，而仍然先从数据库读出全部行。

### 回归测试

至少覆盖：

1. 总匹配数为 0、1、恰好 `limit`、`limit + 1` 和远大于 `limit` 时，结果数与 `hasMore` 正确。
2. `SEARCH`、`NEXT`、指定 quest ID、collection 过滤均保持稳定排序和正确结果。
3. 运行时状态过滤导致首批候选不足时，查询仍能继续读取后续 SQL 页，直到得到正确的 `limit` 条或确定耗尽。
4. 使用查询计数器验证宽泛查询不会物化全部任务；至少证明读取/转换数量受分页策略约束。
5. `SearchKnowledgeTool` 的输出和 `SearchTrace` 使用数据层的准确 `hasMore`。
6. `limit=0`、负数和超大 limit 的边界行为。

---

## 五、实施顺序

按以下顺序实施，减少相互干扰：

1. **Finding 4：Wiki 下载上限**
   - 边界明确，先隔离网络输入风险。
2. **Finding 2：pending rebuild**
   - 确保 Wiki 内容变化最终进入知识库。
3. **Finding 3：后台快照写入**
   - 明确 executor 和生命周期，避免与查询优化混杂。
4. **Finding 5：SQL 分页与 hasMore 数据契约**
   - 最后调整任务查询返回结构和 AI 工具输出。

每一步完成后先运行对应 self-test，再运行完整 `./gradlew test`。

---

## 六、最终验收清单（负责人：本审查会话）

实现者完成后，本审查会话将逐项执行以下验收；只有全部通过才认为 Findings 2–5 已修复：

- [ ] 审查 diff 确认范围没有扩大到 Findings 1、6–9 或无关功能。
- [ ] Finding 2：构建 RUNNING 时的 Wiki rebuild 请求不会丢失；构建结束后恰好补跑一次。
- [ ] Finding 2：重复请求合并、失败/关闭状态不死锁、不无限递归。
- [ ] Finding 3：tick 路径不执行同步 SQLite 写事务。
- [ ] Finding 3：后台写入串行、按最新 generation/fingerprint 生效，旧快照不能覆盖新快照。
- [ ] Finding 3：世界切换/客户端关闭时后台任务安全停止。
- [ ] Finding 4：有限响应大小对已知和未知 Content-Length 都生效。
- [ ] Finding 4：超限/错误响应不覆盖本地 Wiki、不触发 rebuild。
- [ ] Finding 5：数据库读取本身有硬分页，不是只在 Java 列表层截断。
- [ ] Finding 5：`hasMore` 对 0、恰好 limit、limit+1 和大于 limit 的数据准确。
- [ ] Finding 5：运行时过滤不会因首批候选不足而漏结果。
- [ ] 对应回归测试全部通过。
- [ ] `cd /Users/chenhong/Documents/modpedia && ./gradlew test` 全部通过。
- [ ] 报告测试输出、变更文件列表和任何已知限制，供本审查会话复核。

## 七、交付要求

实现者交付时必须提供：

1. 修改文件列表；
2. Findings 2–5 各自对应的测试名称；
3. 完整 Gradle 测试输出或失败日志；
4. 线程/lifecycle 设计说明，尤其是 Finding 3；
5. `hasMore` 数据契约说明，尤其是 Finding 5；
6. 未完成项或与本文方案有差异的地方。

没有上述信息时，不进行最终验收通过判定。
