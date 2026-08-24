package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemStackIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在注册表完成后分批捕获 1.12.2 物品 ID 和基础名称，并交给 Worker 写入目录。 */
public final class LegacyItemCatalogSyncService {
    /** 基础名称也按小批次执行；绝不在启动或进入世界时枚举变体/Tooltip。 */
    private static final long CAPTURE_BATCH_BUDGET_NANOS = 1_000_000L;
    private static final int CAPTURE_BATCH_LIMIT = 32;
    private static final int CHECKPOINT_INTERVAL = 1_024;
    private static final LegacyItemCatalogSyncService INSTANCE = new LegacyItemCatalogSyncService();
    // 客户端只暂存基础名称；运行时 Tooltip 从不进入这份启动目录缓存，避免把
    // 整份 item_catalog（尤其是长 Tooltip）复制到 Java 8 客户端堆中。
    private final Map<String, LegacyItemCatalogEntry> captureEntries =
            new HashMap<String, LegacyItemCatalogEntry>();
    private final Map<String, String> displayNames = new HashMap<String, String>();
    private final LegacyCatalogCaptureGate captureGate = new LegacyCatalogCaptureGate();
    private final LegacyItemCatalogProgress progress = new LegacyItemCatalogProgress();
    private Iterator<Item> items;
    private boolean started;
    private boolean running;
    private boolean submitted;
    private boolean deferredLogged;
    private String activeLanguage;
    private String pendingLanguage;
    private List<LegacyItemCatalogEntry> pendingEntries;
    private volatile boolean checkpointInFlight;
    private int lastCheckpointEntries;
    private long captureStartedAtNanos;
    private long slowestEntryNanos;
    private String slowestEntry = "";
    private volatile long nextPendingRetryAtMillis;
    private volatile int processedVariants;
    private volatile int failedVariants;
    private volatile int registeredItemCount;

    private LegacyItemCatalogSyncService() {
    }

    public static LegacyItemCatalogSyncService get() {
        return INSTANCE;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        captureGate.request();
        Collection<Item> values = ForgeRegistries.ITEMS.getValuesCollection();
        registeredItemCount = values.size();
        items = new ArrayList<Item>(values).iterator();
        running = true;
        captureStartedAtNanos = 0L;
        slowestEntryNanos = 0L;
        slowestEntry = "";
        processedVariants = 0;
        failedVariants = 0;
        checkpointInFlight = false;
        lastCheckpointEntries = 0;
        nextPendingRetryAtMillis = 0L;
        progress.start(registeredItemCount);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        String language = normalizeLanguage(minecraft.gameSettings.language);
        // 语言切换只能在尚未进入世界时重建；必须先于 gate 判断，否则完成过一轮
        // 捕获后 gate 处于 CLOSED，会把菜单中的语言切换直接短路掉。
        if (minecraft.world == null && activeLanguage != null && !activeLanguage.equals(language)) {
            // 语言切换时只重建当前语言目录；不保留上一语言的内存缓存，避免
            // Ctrl/普通显示在切换后混用旧名称。
            captureEntries.clear();
            displayNames.clear();
            pendingEntries = null;
            pendingLanguage = null;
            submitted = false;
            activeLanguage = language;
            Collection<Item> values = ForgeRegistries.ITEMS.getValuesCollection();
            registeredItemCount = values.size();
            items = new ArrayList<Item>(values).iterator();
            running = true;
            captureGate.reset();
            captureGate.request();
            captureStartedAtNanos = 0L;
            slowestEntryNanos = 0L;
            slowestEntry = "";
            processedVariants = 0;
            failedVariants = 0;
            deferredLogged = false;
            checkpointInFlight = false;
            lastCheckpointEntries = 0;
            nextPendingRetryAtMillis = 0L;
            progress.start(registeredItemCount);
        }
        if (activeLanguage == null) {
            activeLanguage = language;
        }
        trySubmitPending();
        LegacyCatalogCaptureGate.Decision decision = captureGate.observe(
                minecraft.world != null, minecraft.currentScreen != null
        );
        if (decision == LegacyCatalogCaptureGate.Decision.WAIT) {
            progress.waiting(processedVariants, captureEntries.size(), failedVariants,
                    lastCheckpointEntries, registeredItemCount);
            return;
        }
        if (decision == LegacyCatalogCaptureGate.Decision.PAUSE) {
            if (running && !deferredLogged) {
                deferredLogged = true;
                ModPedia.LOGGER.info(
                        "1.12 物品目录捕获因进入世界暂时暂停：items={}；返回主菜单后继续",
                        processedVariants
                );
            }
            progress.paused(processedVariants, captureEntries.size(), failedVariants,
                    lastCheckpointEntries, registeredItemCount);
            // 进入世界前如果 Worker 尚未完成握手，先把当前已捕获前缀交给
            // Worker；后续返回主菜单会继续扫描并用完整快照替换它。
            trySubmitCurrentSnapshot();
            return;
        }
        if (decision == LegacyCatalogCaptureGate.Decision.CLOSED) {
            return;
        }
        if (deferredLogged) {
            deferredLogged = false;
            ModPedia.LOGGER.info("1.12 物品目录捕获已返回主菜单，继续剩余基础物品：items={}", processedVariants);
        }
        if (!running) {
            return;
        }
        if (captureStartedAtNanos == 0L) {
            captureStartedAtNanos = System.nanoTime();
        }

        // 这里只读取每个注册物品 metadata=0 的基础显示名称。变体和 Tooltip
        // 都交给 RuntimeItemContextReader 按需读取，避免大型整合包在主线程卡死。
        long batchStartedAt = System.nanoTime();
        int budget = CAPTURE_BATCH_LIMIT;
        while (budget-- > 0 && (processedVariants == 0
                || System.nanoTime() - batchStartedAt < CAPTURE_BATCH_BUDGET_NANOS)) {
            if (!items.hasNext()) {
                complete(language);
                return;
            }
            collect(items.next(), language);
            processedVariants++;
        }
        if (!items.hasNext()) {
            complete(language);
        } else {
            progress.scanning(processedVariants, captureEntries.size(), failedVariants,
                    lastCheckpointEntries, registeredItemCount);
            trySubmitCheckpoint(language);
        }
    }

    private void complete(String language) {
        running = false;
        pendingLanguage = language;
        pendingEntries = new ArrayList<LegacyItemCatalogEntry>(captureEntries.values());
        captureEntries.clear();
        captureGate.complete();
        long elapsedNanos = captureStartedAtNanos == 0L
                ? 0L : System.nanoTime() - captureStartedAtNanos;
        ModPedia.LOGGER.info(
                "1.12 物品目录捕获完成：language={} items={} entries={} failed={} elapsed_ms={} slowest={} slowest_ms={}",
                language, processedVariants, pendingEntries.size(), failedVariants,
                elapsedNanos / 1_000_000L, slowestEntry,
                slowestEntryNanos / 1_000_000L
        );
        progress.waitingForWorker(processedVariants, pendingEntries.size(), failedVariants,
                lastCheckpointEntries, registeredItemCount);
        trySubmitPending();
    }

    /**
     * 发送已经捕获的完整前缀快照。Worker 的同步接口是“当前语言全量替换”，
     * 因此这里每次发送从第一条到当前条目的累计集合，而不是发送单独小块；
     * 这样即使玩家在扫描完成前进入世界，数据库也不会保持空目录或只剩最后一块。
     */
    private void trySubmitCheckpoint(final String language) {
        if (!running || checkpointInFlight || !LegacyWorkerBridge.get().isReady()
                || captureEntries.size() < lastCheckpointEntries + CHECKPOINT_INTERVAL) {
            return;
        }
        final List<LegacyItemCatalogEntry> snapshot =
                new ArrayList<LegacyItemCatalogEntry>(captureEntries.values());
        checkpointInFlight = true;
        progress.syncing(processedVariants, captureEntries.size(), failedVariants,
                lastCheckpointEntries, registeredItemCount);
        LegacyWorkerBridge.get().syncItems(language, snapshot).whenComplete((ignored, failure) -> {
            checkpointInFlight = false;
            if (failure == null) {
                lastCheckpointEntries = snapshot.size();
                ModPedia.LOGGER.debug("1.12 物品目录捕获检查点已同步：entries={}", snapshot.size());
            } else {
                ModPedia.LOGGER.debug("1.12 物品目录捕获检查点同步未完成：{}", failure.getMessage());
            }
            // 如果检查点完成时扫描已经结束，继续发送最终完整快照；最终快照
            // 必须晚于检查点，避免一个旧的前缀覆盖完整目录。
            if (!running && pendingEntries != null) {
                trySubmitPending();
            }
        });
    }

    /** Worker 晚于注册表完成时由 ready 回调补交，不重复读取 Tooltip。 */
    public void trySubmitPending() {
        if (submitted || checkpointInFlight || pendingEntries == null
                || !LegacyWorkerBridge.get().isReady()
                || System.currentTimeMillis() < nextPendingRetryAtMillis) {
            return;
        }
        submitted = true;
        final List<LegacyItemCatalogEntry> values = pendingEntries;
        progress.syncing(processedVariants, values.size(), failedVariants,
                lastCheckpointEntries, registeredItemCount);
        LegacyWorkerBridge.get().syncItems(pendingLanguage, values).whenComplete((ignored, failure) -> {
            if (failure != null) {
                submitted = false;
                nextPendingRetryAtMillis = System.currentTimeMillis() + 5_000L;
                progress.waitingForWorker(processedVariants, values.size(), failedVariants,
                        lastCheckpointEntries, registeredItemCount);
                ModPedia.LOGGER.debug("1.12 物品目录同步未完成：{}", failure.getMessage());
            } else {
                nextPendingRetryAtMillis = 0L;
                pendingEntries = null;
                progress.completed(processedVariants, values.size(), failedVariants,
                        values.size(), registeredItemCount);
            }
        });
    }

    /** Worker 晚于扫描启动、且扫描又因进入世界暂停时，补交当前非空前缀。 */
    public void trySubmitCurrentSnapshot() {
        if (!running || submitted || checkpointInFlight || pendingEntries != null
                || activeLanguage == null || captureEntries.isEmpty()
                || !LegacyWorkerBridge.get().isReady()
                || captureEntries.size() <= lastCheckpointEntries) {
            return;
        }
        final List<LegacyItemCatalogEntry> snapshot =
                new ArrayList<LegacyItemCatalogEntry>(captureEntries.values());
        checkpointInFlight = true;
        progress.syncing(processedVariants, snapshot.size(), failedVariants,
                lastCheckpointEntries, registeredItemCount);
        LegacyWorkerBridge.get().syncItems(activeLanguage, snapshot).whenComplete((ignored, failure) -> {
            checkpointInFlight = false;
            if (failure == null) {
                lastCheckpointEntries = Math.max(lastCheckpointEntries, snapshot.size());
                ModPedia.LOGGER.debug("1.12 物品目录捕获当前快照已同步：entries={}", snapshot.size());
            } else {
                ModPedia.LOGGER.debug("1.12 物品目录捕获当前快照同步未完成：{}", failure.getMessage());
            }
            if (!running && pendingEntries != null) {
                trySubmitPending();
            }
        });
    }

    public LegacyItemCatalogProgress.Snapshot progress() {
        return progress.snapshot();
    }

    public String displayName(String itemValue, boolean showId) {
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(itemValue);
            String displayName = displayNames.get(identity.canonicalKey());
            // 模型经常只返回 namespace:item；1.12.2 的目录按 metadata 保存，
            // 因此无显式变体时优先使用默认 metadata=0 的名称，而不是退回显示
            // 原始 ID。回答令牌显式带 meta 时仍然严格匹配对应变体。
            if ((displayName == null || displayName.isEmpty())
                    && identity.getMetadata() == LegacyItemIdentity.UNSPECIFIED_METADATA) {
                displayName = displayNames.get(identity.getItemId() + "@0");
            }
            if (showId || displayName == null || displayName.isEmpty()) {
                return identity.canonicalKey();
            }
            return displayName;
        } catch (RuntimeException exception) {
            return itemValue == null ? "" : itemValue;
        }
    }

    private void collect(Item item, String language) {
        if (item == null || item.getRegistryName() == null) {
            return;
        }
        if ("minecraft:air".equals(item.getRegistryName().toString())) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            // 只构造默认 metadata=0 的 Stack 读取本地化名称；不枚举变体，
            // 也不调用运行时说明生成器或任何第三方 Tooltip 事件。
            ItemStack stack = new ItemStack(item, 1, 0);
            LegacyItemIdentity identity = LegacyItemStackIdentity.from(stack);
            String key = identity.canonicalKey();
            String display = stack.getDisplayName();
            String description = "";
            String sourceMod = item.getRegistryName().getResourceDomain();
            String fingerprint = sha256(key + "|" + language + "|" + display + "|" + description);
            LegacyItemCatalogEntry entry = new LegacyItemCatalogEntry(
                    key, language, display, description, sourceMod, fingerprint
            );
            captureEntries.put(key, entry);
            displayNames.put(key, display);
        } catch (Throwable ignored) {
            // 单个异常物品不影响其余注册物品完成目录同步。
            failedVariants++;
        } finally {
            long elapsed = System.nanoTime() - startedAt;
            if (elapsed > slowestEntryNanos) {
                slowestEntryNanos = elapsed;
                slowestEntry = item.getRegistryName() == null
                        ? "unknown" : item.getRegistryName().toString();
            }
        }
    }

    private String normalizeLanguage(String value) {
        String language = value == null ? "zh_cn" : value.toLowerCase(Locale.ROOT);
        return language.replace('-', '_');
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[bytes.length * 2];
            int offset = 0;
            for (byte item : bytes) {
                int unsigned = item & 0xff;
                result[offset++] = hex[unsigned >>> 4];
                result[offset++] = hex[unsigned & 0x0f];
            }
            return new String(result);
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
