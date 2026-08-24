package io.ctyx.modpedia.client;

/**
 * 物品目录捕获的轻量状态快照。
 *
 * <p>基础物品名称仍由客户端主线程按小批次执行；这里仅发布不可变计数，主菜单
 * 绘制线程读取快照时不会触发注册表、Tooltip 或 Worker 操作。</p>
 */
public final class LegacyItemCatalogProgress {
    public enum Phase {
        NOT_STARTED,
        WAITING,
        SCANNING,
        PAUSED,
        WAITING_FOR_WORKER,
        SYNCING,
        COMPLETED
    }

    private volatile Snapshot snapshot = Snapshot.initial();

    public void start(int registeredItems) {
        publish(Phase.WAITING, registeredItems, 0, 0, 0, 0);
    }

    public void waiting(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.WAITING, registeredItems, processedVariants, capturedEntries,
                failedVariants, syncedEntries);
    }

    public void scanning(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.SCANNING, registeredItems, processedVariants, capturedEntries,
                failedVariants, syncedEntries);
    }

    public void paused(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.PAUSED, registeredItems, processedVariants, capturedEntries,
                failedVariants, syncedEntries);
    }

    public void waitingForWorker(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.WAITING_FOR_WORKER, registeredItems, processedVariants,
                capturedEntries, failedVariants, syncedEntries);
    }

    public void syncing(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.SYNCING, registeredItems, processedVariants, capturedEntries,
                failedVariants, syncedEntries);
    }

    public void completed(int processedVariants, int capturedEntries,
            int failedVariants, int syncedEntries, int registeredItems) {
        publish(Phase.COMPLETED, registeredItems, processedVariants, capturedEntries,
                failedVariants, syncedEntries);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private void publish(Phase phase, int registeredItems, int processedVariants,
            int capturedEntries, int failedVariants, int syncedEntries) {
        snapshot = new Snapshot(
                phase,
                Math.max(0, registeredItems),
                Math.max(0, processedVariants),
                Math.max(0, capturedEntries),
                Math.max(0, failedVariants),
                Math.max(0, syncedEntries)
        );
    }

    public static final class Snapshot {
        private final Phase phase;
        private final int registeredItems;
        private final int processedVariants;
        private final int capturedEntries;
        private final int failedVariants;
        private final int syncedEntries;

        private Snapshot(Phase phase, int registeredItems, int processedVariants,
                int capturedEntries, int failedVariants, int syncedEntries) {
            this.phase = phase;
            this.registeredItems = registeredItems;
            this.processedVariants = processedVariants;
            this.capturedEntries = capturedEntries;
            this.failedVariants = failedVariants;
            this.syncedEntries = syncedEntries;
        }

        private static Snapshot initial() {
            return new Snapshot(Phase.NOT_STARTED, 0, 0, 0, 0, 0);
        }

        public Phase phase() {
            return phase;
        }

        public int registeredItems() {
            return registeredItems;
        }

        public int processedVariants() {
            return processedVariants;
        }

        public int capturedEntries() {
            return capturedEntries;
        }

        public int failedVariants() {
            return failedVariants;
        }

        public int syncedEntries() {
            return syncedEntries;
        }
    }
}
