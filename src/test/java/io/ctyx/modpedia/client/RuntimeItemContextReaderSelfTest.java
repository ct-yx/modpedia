package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.RuntimeItemContext;

import java.util.List;

/** 运行时物品上下文的纯本地边界测试，不启动 Minecraft 世界、不访问模型或外网。 */
public final class RuntimeItemContextReaderSelfTest {
    private RuntimeItemContextReaderSelfTest() {
    }

    public static void main(String[] args) {
        List<String> normalized = RuntimeItemContextReader.normalizeItemIds(
                List.of("Example:One", "example:one", " example:two ", "example:three", "example:four"),
                4
        );
        check(normalized.equals(List.of("example:one", "example:two", "example:three")),
                "运行时请求应对 ID 去重并限制最多三个");

        ModPediaBridge.RuntimeItemContextRequest request =
                new ModPediaBridge.RuntimeItemContextRequest(
                        "request", "chat", "conversation", "ZH_CN", 4,
                        List.of("Example:One", "example:one", "example:two", "example:three")
                );
        check(request.maxItems() == 3, "协议请求的 max_items 必须限制为三个");
        check(request.itemIds().equals(List.of("example:one", "example:two", "example:three")),
                "协议请求应保留去重后的确认 ID");
        check("zh_cn".equals(request.language()), "语言字段应规范化为小写");

        check("- Lore 第一行\n- Lore 第二行".equals(
                        RuntimeItemContextReader.tooltipMarkdownText(
                                List.of("示例物品", "Lore 第一行", "Lore 第二行"))),
                "Tooltip 第一行应作为名称，后续行应转换为 Markdown 无序列表");

        RuntimeItemContext context = new RuntimeItemContext(
                "example:one", "zh_cn", "示例物品", "- 当前 Tooltip", true, 1L);
        var response = ModPediaBridge.runtimeItemContextResponse(
                "request", List.of(context));
        check("item_tooltip".equals(response.get("request_kind").getAsString()),
                "响应必须保留 item_tooltip request_kind");
        check(response.getAsJsonArray("runtime_item_context").size() == 1,
                "正常 Tooltip 应进入响应数组");

        RuntimeItemContext oversized = new RuntimeItemContext(
                "example:large", "zh_cn", "大物品", "x".repeat(70_000), true, 1L);
        var bounded = ModPediaBridge.runtimeItemContextResponse(
                "request-large", List.of(oversized));
        check(bounded.getAsJsonArray("runtime_item_context").isEmpty()
                        && bounded.get("runtime_item_context_truncated").getAsBoolean(),
                "超过 64 KiB 的运行时 Tooltip 应降级为空数组");

        System.out.println("ModPedia runtime item context reader self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
