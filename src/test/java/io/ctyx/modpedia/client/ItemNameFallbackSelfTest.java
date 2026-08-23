package io.ctyx.modpedia.client;

import java.util.Map;

/** Forge 1.20.1 物品名称回退回归，禁止把缺少翻译的物品写成原始 ID。 */
public final class ItemNameFallbackSelfTest {
    private ItemNameFallbackSelfTest() {
    }

    public static void main(String[] args) {
        check("示例物品".equals(ItemNameResolver.localizedName(
                "item.example.sample",
                "example:sample",
                Map.of("item.example.sample", "示例物品"),
                Map.of()
        ).orElse("")), "当前语言名称应优先使用");
        check("Example Item".equals(ItemNameResolver.localizedName(
                "item.example.sample",
                "example:sample",
                Map.of(),
                Map.of("item.example.sample", "Example Item")
        ).orElse("")), "当前语言缺失时应使用 en_us 名称");
        check("Example · Sample".equals(
                ItemNameResolver.readableFallbackName("example:sample")),
                "双语翻译均缺失时应使用可读名称");
        check("Silentgems · Gem Ruby".equals(
                ItemNameResolver.readableFallbackName("silentgems:gem_ruby")),
                "可读名称应拆分 namespace 和路径");
        check(!ItemNameResolver.isDisplayNameUsable(
                        "example:sample",
                        "example:sample"),
                "原始 ID 不应被视为可用显示名称");
        check(ItemNameResolver.isDisplayNameUsable(
                        "Example Item",
                        "example:sample"),
                "正常本地化名称应被视为可用");
        System.out.println("ModPedia item name fallback self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
