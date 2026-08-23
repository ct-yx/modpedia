package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.api.RuntimeItemContext;
import io.ctyx.modpedia.compat.WorkerCompatibility;
import io.ctyx.modpedia.protocol.WorkerProtocol;

import java.util.List;

/** 运行时物品 Tooltip 的协议边界回归；不启动 Minecraft、Worker 或真实模型。 */
public final class RuntimeItemContextSelfTest {
    private RuntimeItemContextSelfTest() {
    }

    public static void main(String[] args) {
        verifyOldHandshakeStillWorks();
        verifyRequestDeduplicationAndLimit();
        verifyResponseShape();
        verifyResponseSizeFallback();
        System.out.println("ModPedia runtime item context self-test passed");
    }

    private static void verifyOldHandshakeStillWorks() {
        JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "old-client");
        WorkerCompatibility.addClientHello(hello);
        check(WorkerCompatibility.isCompatibleClient(hello), "旧客户端握手必须继续通过");
        check(!hello.has("client_optional_capabilities"),
                "未注册运行时读取器时不得虚报可选能力");
        check(!WorkerCompatibility.supportsOptionalCapability(
                hello, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY),
                "旧客户端不应声明 runtime_item_context");

        WorkerCompatibility.addClientOptionalCapability(
                hello, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY);
        check(WorkerCompatibility.supportsOptionalCapability(
                hello, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY),
                "注册适配器后应能声明 runtime_item_context");
    }

    private static void verifyRequestDeduplicationAndLimit() {
        ModPediaBridge.RuntimeItemContextRequest request =
                new ModPediaBridge.RuntimeItemContextRequest(
                        "request-id",
                        "chat-id",
                        "conversation-id",
                        "ZH-CN",
                        3,
                        List.of("Example:One", "example:one", "example:two", "example:three", "example:four")
                );
        check(request.itemIds().equals(List.of("example:one", "example:two", "example:three")),
                "请求应规范化、去重并限制为最多三个物品");
        check("zh-cn".equals(request.language()), "请求语言应规范化为小写");
    }

    private static void verifyResponseShape() {
        RuntimeItemContext context = new RuntimeItemContext(
                "example:item",
                "zh_cn",
                "示例物品",
                "- 当前世界 Tooltip",
                true,
                1710000000000L
        );
        JsonObject response = ModPediaBridge.runtimeItemContextResponse(
                "item-request",
                List.of(context)
        );
        check(WorkerProtocol.RUNTIME_CONTEXT_RESPONSE.equals(
                        WorkerProtocol.string(response, "type")),
                "响应类型必须是 runtime_context_response");
        check(WorkerProtocol.RUNTIME_ITEM_CONTEXT_KIND.equals(
                        WorkerProtocol.string(response, "request_kind")),
                "响应必须保留 request_kind=item_tooltip");
        JsonArray values = response.getAsJsonArray("runtime_item_context");
        check(values.size() == 1, "响应应包含一个运行时物品上下文");
        check("示例物品".equals(values.get(0).getAsJsonObject().get("display_name").getAsString()),
                "响应必须保留 display_name");
        check("- 当前世界 Tooltip".equals(
                        values.get(0).getAsJsonObject().get("tooltip_markdown").getAsString()),
                "响应必须保留 tooltip_markdown");
    }

    private static void verifyResponseSizeFallback() {
        String huge = "字".repeat(12_000);
        RuntimeItemContext first = new RuntimeItemContext(
                "example:first", "zh_cn", "第一个", huge, true, 1L);
        RuntimeItemContext second = new RuntimeItemContext(
                "example:second", "zh_cn", "第二个", huge, true, 1L);
        RuntimeItemContext third = new RuntimeItemContext(
                "example:third", "zh_cn", "第三个", huge, true, 1L);
        JsonObject response = ModPediaBridge.runtimeItemContextResponse(
                "large-request",
                List.of(first, second, third)
        );
        check(WorkerProtocol.utf8Length(response.toString())
                        <= WorkerProtocol.MAX_RUNTIME_ITEM_CONTEXT_BYTES,
                "运行时 Tooltip 响应不得超过 64 KiB");
        check(WorkerProtocol.bool(response, "runtime_item_context_truncated", false),
                "超限响应必须标记降级");
        check(response.getAsJsonArray("runtime_item_context").isEmpty(),
                "超限时应返回空数组，让 Worker 使用静态目录");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
