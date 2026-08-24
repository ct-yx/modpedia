# Worker 运行时物品上下文：客户端适配 Prompt

把下面的内容发送给需要合入本次 Worker 改动的具体游戏版本开发对话。该改动不递增
`worker-baseline`，也不改变 `WorkerProtocol.VERSION`；它是可选能力，旧客户端仍使用
静态 `item_catalog`。

```text
[WORKER_ADAPTER_UPDATE]
目标版本：Minecraft 1.21.1 / NeoForge / Java 21
Worker 基线：worker-baseline-3
Worker API level：1
Worker 版本引用：待 Worker 分支提交后填写；当前工作区实现已完成

变更摘要：
Worker 的 search_knowledge 在问题中确认了物品 ID、且静态 item_catalog 的简介为空或
缺失时，可以按需向客户端请求最多 5 个物品的运行时 Tooltip。运行时 Tooltip 进入
当前 AI 工具结果，并由 Worker 在独立知识库线程异步缓存到 knowledge.db 的
item_catalog；不写入会话文件或日志。世界未就绪、
能力缺失、超时、响应超过 64 KiB 或单个物品读取失败时，Worker 继续使用静态目录。

协议更新：
WorkerProtocol.VERSION 仍为 1。必选 client_capabilities 不变；只有完成本客户端适配后
才在 hello 中增加：
  client_optional_capabilities: ["runtime_item_context"]
Worker 返回 hello_ack 时会声明：
  worker_optional_capabilities: ["runtime_item_context"]
旧客户端不携带 client_optional_capabilities 时必须仍能完成握手。

Worker → 客户端请求：
  type: "runtime_context_request"
  request_kind: "item_tooltip"
  request_id: <独立子请求 ID>
  chat_request_id: <当前聊天请求 ID>
  conversation_id: <会话 ID>
  language: "zh_cn" | "en_us" | "neutral"
  max_items: 1..5
  item_ids: ["namespace:path", ...]

客户端 → Worker 响应：
  type: "runtime_context_response"
  request_kind: "item_tooltip"
  request_id: <原请求 ID>
  runtime_item_context: [
    {
      item_id: "namespace:path",
      language: "zh_cn",
      display_name: "当前语言名称",
      tooltip_markdown: "- 当前世界/玩家条件下的 Tooltip 后续行",
      world_ready: true,
      captured_at: 1710000000000
    }
  ]

客户端需要做：
1. 在 hello 中仅在实际实现下面的读取器后声明 runtime_item_context；未实现时不要虚报。
2. 扩展现有 runtime context 分发：request_kind=item_tooltip 时不要解析 task query，
   转交新的 RuntimeItemContextHandler；没有 request_kind 或 request_kind=task 时保持
   原有任务快照链路。
3. 通过 Minecraft 客户端线程读取 BuiltInRegistries.ITEM 对应的 ItemStack，并使用当前
   世界、玩家、服务器和模组状态生成 Tooltip。读取前检查 Minecraft.level、player 和
   客户端连接状态；任一条件未满足时返回空数组或 world_ready=false。
4. 每次最多处理请求中的 max_items 个已确认 ID（Worker 将其限制为 5）；未知 ID、单个读取异常只跳过该项，不影响其他项。
5. 每次请求最多处理 5 个已确认 ID。第一行 Component#getString() 作为 display_name，后续 Tooltip 行转换为 Markdown 无序
   列表；不要把 ItemStack、Level、玩家对象或第三方模组对象传给 Worker。
6. 在客户端发送前限制整个 JSON 响应不超过 64 KiB；客户端不要把 Tooltip 原文写入日志、
   会话文件或诊断文件，也不要直接写入 knowledge.db/item_catalog；成功结果由 Worker
   的知识库线程异步缓存到 item_catalog。
7. 使用现有 runtime context coordinator/有界队列；客户端线程只执行少量 ItemStack
   Tooltip 读取，不执行 AI、SQLite、知识库扫描或网络请求。请求过期、切换世界、退出
   服务器时完成 waiter 并丢弃结果。
8. 响应必须回传原 request_id，并保留 request_kind=item_tooltip；任务响应不得混入
   runtime_item_context。

客户端不应做：
- 不复制 Worker 的静态目录查询、AI 编排或会话持久化逻辑；
- 不在启动全量扫描动态 Tooltip；
- 不由客户端直接打开或写入 item_catalog；缓存由 Worker 的知识库线程完成；
- 不把 client_optional_capabilities 加入必选 client_capabilities；
- 不因为缺少该能力阻止游戏启动或阻止 Dedicated Server 启动。

降级行为：
- 缺少 runtime_item_context：Worker 只返回静态 item_context；
- 世界未就绪/超时/读取异常：Worker 只返回静态 item_context；
- Worker 旧版本或 response 缺少 request_kind：任务运行时上下文按原协议处理；
- JEI、Jade、FTBQ 或其他可选模组缺失：本读取器返回不可用，不影响进入游戏。

客户端测试要求：
- hello 不声明可选能力时仍可握手；声明后能收到 item_tooltip 请求；
- 当前世界就绪时返回一个完整 Tooltip，display_name 和后续 Markdown 正确；
- 世界未就绪、未知 ID、单项异常和客户端超时均能返回降级响应；
- 请求超过 5 个 ID 时最多读取 5 个，重复 ID 只读取一次；
- 响应总字节数超过 64 KiB 时截断/拒绝并让 Worker 使用静态目录；
- Tooltip 不进入会话文件、日志和 API 诊断；成功结果允许由 Worker 缓存到 item_catalog；
- 任务 runtime_context_request、hello/hello_ack、旧 Worker 和旧能力回归不受影响；
- Dedicated Server 不加载或解析客户端 ItemStack/Tooltip 适配类。

验证命令：
./gradlew test
./gradlew build
git diff --check
并在真实客户端中验证：确认物品 ID → 按问题触发 search_knowledge → 收到 item_tooltip
请求 → 运行时 Tooltip 进入当前 AI 工具结果 → 请求结束后不会出现在会话和日志。
[/WORKER_ADAPTER_UPDATE]
```
