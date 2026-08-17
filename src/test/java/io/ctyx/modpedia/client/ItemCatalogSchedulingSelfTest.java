package io.ctyx.modpedia.client;

/** 物品目录扫描调度回归：进入世界后不得再启动客户端 Tooltip 全量扫描。 */
public final class ItemCatalogSchedulingSelfTest {
    private ItemCatalogSchedulingSelfTest() {
    }

    public static void main(String[] args) {
        check(ItemCatalogSyncService.canCaptureInMenu(false, false),
                "主菜单阶段可以捕获注册表 Tooltip");
        check(!ItemCatalogSyncService.canCaptureInMenu(true, false),
                "世界已加载时不得捕获注册表 Tooltip");
        check(!ItemCatalogSyncService.canCaptureInMenu(false, true),
                "玩家已创建时不得捕获注册表 Tooltip");
        check(!ItemCatalogSyncService.canCaptureInMenu(true, true),
                "世界和玩家均存在时不得捕获注册表 Tooltip");
        check(ItemCatalogSyncService.shouldRecaptureForLanguage(true, "zh_cn", "en_us"),
                "主菜单切换语言后应允许重新捕获一次目录");
        check(!ItemCatalogSyncService.shouldRecaptureForLanguage(true, "zh_cn", "zh_cn"),
                "语言未变化时不得重复捕获目录");
        // 进入世界后的 tick 即使检测到语言不同也不能绕过菜单保护；语言切换
        // 只允许在主菜单重新捕获，避免大型整合包中出现第二次全量扫描。
        check(!ItemCatalogSyncService.shouldRecaptureForLanguage(false, "zh_cn", "en_us"),
                "进入世界后语言变化不得启动第二次全量扫描");
        check("IDLE".equals(ItemCatalogSyncService.stateName()),
                "纯调度 self-test 不应遗留运行中的目录任务");
        check(!ItemCatalogSyncService.hasInFlightOperation(),
                "纯调度 self-test 不应遗留共享 Future");
        System.out.println("ModPedia item catalog scheduling self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
