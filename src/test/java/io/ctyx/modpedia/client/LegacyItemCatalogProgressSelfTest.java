package io.ctyx.modpedia.client;

/** 物品目录进度快照的纯 Java 回归测试。 */
public final class LegacyItemCatalogProgressSelfTest {
    private LegacyItemCatalogProgressSelfTest() {
    }

    public static void main(String[] args) {
        LegacyItemCatalogProgress progress = new LegacyItemCatalogProgress();
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.NOT_STARTED,
                "初始状态应为 NOT_STARTED");

        progress.start(512);
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.WAITING,
                "启动后应等待首个捕获批次");
        check(progress.snapshot().registeredItems() == 512,
                "应保留注册物品数量");

        progress.scanning(128, 120, 2, 0, 512);
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.SCANNING,
                "批次执行时应显示扫描中");
        check(progress.snapshot().processedVariants() == 128
                        && progress.snapshot().capturedEntries() == 120,
                "扫描计数应保持一致");

        progress.paused(128, 120, 2, 0, 512);
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.PAUSED,
                "进入世界后应显示已暂停");

        progress.waitingForWorker(128, 120, 2, 0, 512);
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.WAITING_FOR_WORKER,
                "Worker 未就绪时应显示等待写入");

        progress.syncing(128, 120, 2, 120, 512);
        check(progress.snapshot().phase() == LegacyItemCatalogProgress.Phase.SYNCING,
                "提交快照时应显示同步中");

        progress.completed(512, 500, 2, 500, 512);
        LegacyItemCatalogProgress.Snapshot completed = progress.snapshot();
        check(completed.phase() == LegacyItemCatalogProgress.Phase.COMPLETED,
                "完成后应显示已完成");
        check(completed.syncedEntries() == 500,
                "完成状态应保留已同步条目数");
        System.out.println("LegacyItemCatalogProgressSelfTest: OK");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
