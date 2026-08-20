package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        check(ItemCatalogSyncService.canCaptureInMenu(false, false, true),
                "主菜单屏幕允许捕获注册表 Tooltip");
        check(!ItemCatalogSyncService.canCaptureInMenu(false, false, false),
                "没有屏幕时不得捕获 Tooltip");
        check(ItemCatalogSyncService.isMenuCandidate(false, false, true),
                "第三方替换的主菜单也应打开安全门");
        check(!ItemCatalogSyncService.isMenuCandidate(false, false, false),
                "没有屏幕的加载阶段不能打开安全门");
        check(ItemCatalogSyncService.isConfigurationUnavailable(
                        new IllegalStateException("Cannot get config value before config is loaded.")),
                "配置未加载异常必须被识别为 Tooltip 全局故障");
        check(ItemCatalogSyncService.isConfigurationUnavailable(
                        new RuntimeException("wrapper", new IllegalStateException(
                                "Cannot get config value before config is loaded."))),
                "被包装的配置未加载异常必须被识别");
        check(!ItemCatalogSyncService.isConfigurationUnavailable(
                        new IllegalStateException("unrelated tooltip failure")),
                "其它异常不得误判为配置未加载");
        // FMLLoadComplete 本身不是第三方配置已就绪的证明；生产入口还必须等
        // TitleScreen 打开后才允许广播全量 Tooltip 事件。
        check(!ItemCatalogSyncService.isMainMenuReadyForTest(),
                "纯调度测试默认不应把主菜单安全门置为已就绪");
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

        List<String> startupOrder = new ArrayList<>();
        CompletableFuture<Boolean> rebuild = StartupKnowledgeBootstrap.scheduleCatalogBeforeRebuild(
                () -> startupOrder.add("item_catalog"),
                () -> {
                    startupOrder.add("knowledge_rebuild");
                    return CompletableFuture.completedFuture(true);
                }
        );
        check(rebuild.join(), "知识库重建调度应返回实际 Future");
        check(startupOrder.equals(List.of("item_catalog", "knowledge_rebuild")),
                "Worker ready 后必须先安排物品目录，再提交知识库重建：" + startupOrder);
        System.out.println("ModPedia item catalog scheduling self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
