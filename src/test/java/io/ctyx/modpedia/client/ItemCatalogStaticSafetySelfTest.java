package io.ctyx.modpedia.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 启动前物品目录必须保持静态读取，防止动态 Tooltip 回到全量扫描。 */
public final class ItemCatalogStaticSafetySelfTest {
    private ItemCatalogStaticSafetySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/io/ctyx/modpedia/client/ItemCatalogSyncService.java");
        String text = Files.readString(source);
        check(!text.matches("(?s).*\\.getHoverName\\s*\\(.*"),
                "启动目录不应调用动态显示名称");
        check(!text.matches("(?s).*\\.getTooltipLines\\s*\\(.*"),
                "启动目录不应调用完整 Tooltip API");
        check(!text.matches("(?s).*\\.appendHoverText\\s*\\(.*"),
                "启动目录不应调用物品 Tooltip 回调");
        check(text.contains("staticLocalizedName"),
                "启动目录应通过静态翻译键解析名称");
        check(text.contains("DataComponents.LORE"),
                "启动目录应读取静态 LORE 数据组件");
        check(text.contains("static_name_successes")
                        && text.contains("static_name_fallbacks")
                        && text.contains("static_description_successes")
                        && text.contains("item_failures"),
                "日志应使用聚合静态目录指标");
        check(text.contains("readCachedCatalog") && text.contains("registryFingerprint"),
                "启动目录应优先复用注册表指纹缓存");
        check(text.contains("CAPTURE_EXECUTOR") && text.contains("capture_mode=background"),
                "名称和静态简介生成必须运行在独立后台线程");
        check(!text.contains("continueCaptureOnClientThread"),
                "启动目录不得再按帧把完整捕获排队到客户端线程");
        check("示例物品".equals(ItemNameResolver.staticLocalizedName(
                        "item.example.sample",
                        "example:sample",
                        Map.of("item.example.sample", "示例物品"),
                        Map.of()
                ).orElse("")),
                "后台静态语言快照应能解析物品名称");
        check("Example Item".equals(ItemNameResolver.staticLocalizedName(
                        "item.example.sample",
                        "example:sample",
                        Map.of(),
                        Map.of("item.example.sample", "Example Item")
                ).orElse("")),
                "当前语言缺失时应使用 en_us 名称");
        check("Example · Sample".equals(
                        ItemNameResolver.readableFallbackName("example:sample")),
                "当前语言和英文均缺失时应使用可读名称，而不是原始 ID");
        check("Silentgems · Gem Ruby".equals(
                        ItemNameResolver.readableFallbackName("silentgems:gem_ruby")),
                "缺少翻译时应生成可读名称，而不是把物品 ID 原样写入显示名");
        System.out.println("ModPedia static item catalog safety self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
