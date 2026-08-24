package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemStackIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;
import io.ctyx.modpedia.storage.UserModPediaPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 在注册表完成后分批捕获 1.12.2 物品 ID 和基础名称，并交给 Worker 写入目录。 */
public final class LegacyItemCatalogSyncService {
    /** 基础名称也按小批次执行；绝不在启动或进入世界时枚举变体/Tooltip。 */
    private static final long CAPTURE_BATCH_BUDGET_NANOS = 1_000_000L;
    private static final int CAPTURE_BATCH_LIMIT = 32;
    private static final LegacyItemCatalogSyncService INSTANCE = new LegacyItemCatalogSyncService();
    // 客户端只暂存基础名称；运行时 Tooltip 从不进入这份启动目录缓存，避免把
    // 整份 item_catalog（尤其是长 Tooltip）复制到 Java 8 客户端堆中。
    private final Map<String, LegacyItemCatalogEntry> captureEntries =
            new HashMap<String, LegacyItemCatalogEntry>();
    private final Map<String, String> displayNames = new ConcurrentHashMap<String, String>();
    private final LegacyCatalogCaptureGate captureGate = new LegacyCatalogCaptureGate();
    private final LegacyItemCatalogProgress progress = new LegacyItemCatalogProgress();
    private Iterator<Item> items;
    private List<Item> registeredItems = Collections.emptyList();
    private boolean started;
    private boolean running;
    private boolean submitted;
    private boolean deferredLogged;
    private volatile boolean cacheProbeInFlight;
    private boolean cacheChecked;
    private boolean cacheHit;
    private String activeLanguage;
    private String registryFingerprint;
    private int expectedCatalogCount;
    private Path cacheRoot;
    private String pendingLanguage;
    private List<LegacyItemCatalogEntry> pendingEntries;
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
        start(null);
    }

    /** 在客户端生命周期初始化实例级名称缓存位置；不打开 SQLite。 */
    public void start(java.nio.file.Path configDirectory) {
        if (started) {
            return;
        }
        started = true;
        if (configDirectory != null) {
            cacheRoot = UserModPediaPaths.resolve(configDirectory).itemCatalogCacheRoot();
        }
        String language = "zh_cn";
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && minecraft.gameSettings != null) {
            language = normalizeLanguage(minecraft.gameSettings.language);
        }
        prepareLanguage(language);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.gameSettings == null) {
            return;
        }
        String language = normalizeLanguage(minecraft.gameSettings.language);
        // 语言切换只能在尚未进入世界时重建；必须先于 gate 判断，否则完成过一轮
        // 捕获后 gate 处于 CLOSED，会把菜单中的语言切换直接短路掉。
        if (minecraft.world == null && activeLanguage != null && !activeLanguage.equals(language)) {
            // 语言切换时只重建当前语言目录；不保留上一语言的内存缓存，避免
            // Ctrl/普通显示在切换后混用旧名称。
            prepareLanguage(language);
        }
        trySubmitPending();

        // 先在 Mod 内置的本地名称缓存中按注册表指纹判断；命中时不创建
        // ItemStack，也不向 Worker 发送物品 JSONL。这样复用 worker-baseline-3
        // 的既有 IPC，不需要为这个客户端优化临时增加 Worker 基线。
        if (!cacheChecked) {
            requestLocalCache(language);
            progress.waitingForWorker(processedVariants, captureEntries.size(), failedVariants,
                    0, registeredItemCount);
            return;
        }
        if (cacheHit) {
            return;
        }

        LegacyCatalogCaptureGate.Decision decision = captureGate.observe(
                minecraft.world != null, minecraft.currentScreen != null
        );
        if (decision == LegacyCatalogCaptureGate.Decision.WAIT) {
            progress.waiting(processedVariants, captureEntries.size(), failedVariants,
                    0, registeredItemCount);
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
                    0, registeredItemCount);
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
                    0, registeredItemCount);
        }
    }

    private void prepareLanguage(String language) {
        captureEntries.clear();
        displayNames.clear();
        pendingEntries = null;
        pendingLanguage = null;
        submitted = false;
        cacheProbeInFlight = false;
        cacheChecked = false;
        cacheHit = false;
        activeLanguage = language;
        registeredItems = registryItems();
        registeredItemCount = registeredItems.size();
        expectedCatalogCount = registeredItems.size();
        List<String> ids = new ArrayList<String>(registeredItems.size());
        for (Item item : registeredItems) {
            ids.add(item.getRegistryName().toString());
        }
        registryFingerprint = LegacyItemCatalogCache.registryFingerprint(language, ids);
        items = null;
        running = false;
        captureGate.reset();
        captureGate.request();
        captureStartedAtNanos = 0L;
        slowestEntryNanos = 0L;
        slowestEntry = "";
        processedVariants = 0;
        failedVariants = 0;
        deferredLogged = false;
        nextPendingRetryAtMillis = 0L;
        progress.start(registeredItemCount);
    }

    private void requestLocalCache(String language) {
        if (cacheChecked || cacheProbeInFlight) {
            return;
        }
        if (cacheRoot == null) {
            // 仅保留给旧测试/开发入口；正式客户端总是通过 start(configDirectory)
            // 配置实例缓存目录。
            cacheChecked = true;
            beginCaptureAfterCacheMiss();
            return;
        }
        cacheProbeInFlight = true;
        final String probeLanguage = language;
        final String probeFingerprint = registryFingerprint;
        final int probeCount = expectedCatalogCount;
        CompletableFuture.supplyAsync(() -> LegacyItemCatalogCache.read(
                cacheRoot, probeLanguage, probeFingerprint, probeCount
        )).whenComplete((cached, failure) -> {
            Runnable apply = new Runnable() {
                @Override
                public void run() {
                    applyLocalCache(probeLanguage, probeFingerprint, probeCount,
                            cached, failure);
                }
            };
            try {
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft != null) {
                    minecraft.addScheduledTask(apply);
                } else {
                    apply.run();
                }
            } catch (Throwable ignored) {
                apply.run();
            }
        });
    }

    private void applyLocalCache(
            String probeLanguage,
            String probeFingerprint,
            int probeCount,
            LegacyItemCatalogCache.CachedCatalog cached,
            Throwable failure
    ) {
        cacheProbeInFlight = false;
        if (!probeLanguage.equals(activeLanguage)
                || !probeFingerprint.equals(registryFingerprint)
                || probeCount != expectedCatalogCount) {
            return;
        }
        cacheChecked = true;
        if (failure == null && cached != null) {
            for (Map.Entry<String, LegacyItemCatalogEntry> entry : cached.entries().entrySet()) {
                displayNames.put(entry.getKey(), entry.getValue().getDisplayName());
            }
            cacheHit = true;
            running = false;
            captureGate.complete();
            progress.completed(expectedCatalogCount, expectedCatalogCount, 0,
                    expectedCatalogCount, registeredItemCount);
            ModPedia.LOGGER.info(
                    "1.12 物品名称缓存命中：language={} items={} fingerprint={}",
                    activeLanguage, expectedCatalogCount, registryFingerprint
            );
            return;
        }
        if (failure != null) {
            ModPedia.LOGGER.debug("1.12 物品名称缓存读取失败，执行一次完整捕获：{}",
                    failure.getMessage());
        }
        ModPedia.LOGGER.info(
                "1.12 物品名称缓存未命中，执行一次基础名称捕获：language={} items={}",
                activeLanguage, expectedCatalogCount
        );
        beginCaptureAfterCacheMiss();
    }

    private void beginCaptureAfterCacheMiss() {
        cacheHit = false;
        running = true;
        items = new ArrayList<Item>(registeredItems).iterator();
        progress.waiting(processedVariants, captureEntries.size(), failedVariants,
                0, registeredItemCount);
    }

    private List<Item> registryItems() {
        List<Item> values = new ArrayList<Item>();
        Collection<Item> registered = ForgeRegistries.ITEMS.getValuesCollection();
        for (Item item : registered) {
            if (item == null || item.getRegistryName() == null
                    || "minecraft:air".equals(item.getRegistryName().toString())) {
                continue;
            }
            values.add(item);
        }
        Collections.sort(values, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return left.getRegistryName().toString()
                        .compareTo(right.getRegistryName().toString());
            }
        });
        return values;
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
                0, registeredItemCount);
        trySubmitPending();
    }

    /** Worker 晚于注册表完成时由 ready 回调补交，不重复读取 Tooltip。 */
    public void trySubmitPending() {
        if (submitted || pendingEntries == null
                || !LegacyWorkerBridge.get().isReady()
                || System.currentTimeMillis() < nextPendingRetryAtMillis) {
            return;
        }
        submitted = true;
        final List<LegacyItemCatalogEntry> values = pendingEntries;
        progress.syncing(processedVariants, values.size(), failedVariants,
                0, registeredItemCount);
        LegacyWorkerBridge.get().syncItems(pendingLanguage, values).whenComplete((ignored, failure) -> {
            if (failure != null) {
                submitted = false;
                nextPendingRetryAtMillis = System.currentTimeMillis() + 5_000L;
                progress.waitingForWorker(processedVariants, values.size(), failedVariants,
                        0, registeredItemCount);
                ModPedia.LOGGER.debug("1.12 物品目录同步未完成：{}", failure.getMessage());
            } else {
                nextPendingRetryAtMillis = 0L;
                if (failedVariants == 0 && values.size() == expectedCatalogCount
                        && cacheRoot != null) {
                    try {
                        LegacyItemCatalogCache.write(
                                cacheRoot, pendingLanguage, registryFingerprint, values
                        );
                        ModPedia.LOGGER.info(
                                "1.12 物品名称缓存已保存：language={} items={} fingerprint={}",
                                pendingLanguage, values.size(), registryFingerprint
                        );
                    } catch (java.io.IOException exception) {
                        // SQLite 已经成功；本地名称缓存失败只会使下一次启动重新
                        // 捕获名称，不影响当前目录和按需 Tooltip。
                        ModPedia.LOGGER.warn("1.12 物品名称缓存保存失败：{}",
                                exception.getMessage());
                    }
                }
                pendingEntries = null;
                progress.completed(processedVariants, values.size(), failedVariants,
                        values.size(), registeredItemCount);
            }
        });
    }

    /**
     * 兼容旧生命周期回调。正式目录不再接收扫描中的前缀；进入世界前的准备页
     * 会继续等待最终完整快照，避免半成品覆盖上一次有效目录。
     */
    public void trySubmitCurrentSnapshot() {
        // no-op: 保留入口，避免旧客户端回调在更新过程中触发部分快照写入。
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
