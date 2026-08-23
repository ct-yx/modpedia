package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.search.SearchLanguage;
import io.ctyx.modpedia.storage.ModPediaPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 在客户端注册表冻结后，把当前语言的静态物品目录同步到 knowledge.db。 */
public final class ItemCatalogSyncService {
    /** 后台批处理大小；批次只用于检查关闭状态，不再按帧占用客户端线程。 */
    private static final int CAPTURE_BATCH_SIZE = 512;
    /** v2 强制淘汰曾把缺少翻译的物品保存为原始 ID 的旧目录缓存。 */
    private static final int ITEM_CATALOG_CACHE_VERSION = 2;
    /** 给第三方配置和语言资源一个稳定窗口，避免在 FML load complete 的同一时刻
     * 捕获到“配置尚未加载”的临时状态。 */
    private static final long INITIAL_CAPTURE_DELAY_MS = 3_000L;
    /** 启动阶段只读取静态注册表/语言资源；真实 Tooltip 延迟到玩家确认物品后读取。 */
    private static final String CONFIG_NOT_LOADED_MESSAGE = "config value before config is loaded";
    private static final Object STATE_LOCK = new Object();
    private static final ExecutorService PERSIST_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-item-catalog-persist");
        thread.setDaemon(true);
        return thread;
    });
    /** 在客户端线程批量读取静态目录之间让出一帧，避免大型注册表连续占用渲染线程。 */
    private static final ScheduledExecutorService CAPTURE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "modpedia-item-catalog-capture-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    /** 名称/静态简介生成只使用不可变快照，独立于 Minecraft 客户端线程执行。 */
    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-item-catalog-capture");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean shutdown;
    private static volatile String syncedLanguage = "";
    private static volatile long retryAtMillis;
    private static volatile boolean clientLoadComplete;
    /** FMLLoadComplete 早于部分整合包的最终配置初始化；只有真正打开主菜单
     * 后才开始读取静态目录。动态 Tooltip 延迟到玩家确认物品后按需读取。 */
    private static volatile boolean mainMenuReady;
    private static CompletableFuture<Boolean> inFlight;
    private static CapturedCatalog pendingCatalog;
    private static SyncState state = SyncState.IDLE;

    private ItemCatalogSyncService() {
    }

    /**
     * 在首轮知识库构建完成后调用。物品静态目录必须在客户端线程读取，但整个
     * 注册表只允许在进入世界前一次性完成；这里不再把扫描分摊到 ClientTick。
     */
    public static void startAsync() {
        syncBeforeMainMenuAsync(ModPediaBridge.get());
    }

    /**
     * FMLLoadCompleteEvent 之后才允许读取静态目录。此时模组配置和客户端注册流程
     * 已经完成，避免在 FMLClientSetup 阶段读取不稳定的资源。
     */
    public static void markClientLoadComplete() {
        clientLoadComplete = true;
        if (mainMenuReady) {
            syncBeforeMainMenuAsync(ModPediaBridge.get());
        }
    }

    /** 由 TitleScreen 打开事件调用，作为安全的静态目录导入起点。 */
    public static void markMainMenuReady() {
        boolean firstMenuObservation = !mainMenuReady;
        mainMenuReady = true;
        // observeMenuState() 在每个客户端 tick 都会经过这里；只允许第一次
        // 观察打开启动任务，避免物品目录同步完成后每帧重新扫描注册表。
        if (firstMenuObservation && clientLoadComplete) {
            syncBeforeMainMenuAsync(ModPediaBridge.get());
        }
    }

    /**
     * FancyMenu 等客户端菜单会替换原生 {@link TitleScreen}，因此不能只依赖
     * {@code ScreenEvent.Opening(TitleScreen)}。这个观察入口只在尚未创建世界和
     * 玩家、且当前确实存在一个屏幕时打开同一个菜单安全门；进入世界后不会再次
     * 触发，也不会把静态目录导入推迟到游戏内。
     */
    public static void observeMenuState(Minecraft minecraft) {
        if (shutdown || !clientLoadComplete || minecraft == null) {
            return;
        }
        if (isMenuCandidate(
                minecraft.level != null,
                minecraft.player != null,
                minecraft.screen != null
        )) {
            markMainMenuReady();
        }
    }

    /** 仅供生命周期回归测试和诊断读取当前调度状态。 */
    static String stateName() {
        synchronized (STATE_LOCK) {
            return state.name();
        }
    }

    static boolean hasInFlightOperation() {
        synchronized (STATE_LOCK) {
            return inFlight != null && !inFlight.isDone();
        }
    }

    static boolean isMainMenuReadyForTest() {
        return mainMenuReady;
    }

    /**
     * 把一次性静态目录读取安排到客户端线程，并让启动流程等待其完成。
     * 这样目录不会在进入世界后“补扫”，同时 SQLite 写入仍由 Worker 执行。
     */
    public static CompletableFuture<Boolean> syncBeforeMainMenuAsync(ModPediaBridge bridge) {
        Minecraft minecraft = Minecraft.getInstance();
        if (shutdown || minecraft == null || !clientLoadComplete || !mainMenuReady) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result;
        CapturedCatalog reusable;
        String language = languageCode(minecraft);
        synchronized (STATE_LOCK) {
            if (state == SyncState.READY && language.equals(syncedLanguage)) {
                return CompletableFuture.completedFuture(true);
            }
            // ClientTick、启动回调和语言检查可能在同一时间触发。所有调用方
            // 必须等待同一个 Future，不能把“已经运行”误报成 false。
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }
            if (!canCaptureInMenu(minecraft) || bridge == null || !bridge.isReady()) {
                state = SyncState.WAITING_WORKER;
                retryAtMillis = System.currentTimeMillis() + 5_000L;
                return CompletableFuture.completedFuture(false);
            }
            result = new CompletableFuture<>();
            inFlight = result;
            reusable = pendingCatalog != null && pendingCatalog.language().equals(language)
                    ? pendingCatalog : null;
            if (reusable == null) {
                pendingCatalog = null;
                state = SyncState.CAPTURING;
            } else {
                state = SyncState.PERSISTING;
            }
        }

        if (reusable != null) {
            schedulePersist(reusable, bridge, result);
            return result;
        }

        scheduleCaptureStart(minecraft, bridge, result);
        return result;
    }

    private static void scheduleCaptureStart(
            Minecraft minecraft,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            CAPTURE_SCHEDULER.schedule(() -> {
                if (shutdown) {
                    complete(result, false, null);
                    return;
                }
                try {
                    minecraft.execute(() -> beginCaptureOnClientThread(minecraft, bridge, result));
                } catch (Throwable failure) {
                    complete(result, false, failure);
                }
            }, INITIAL_CAPTURE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            complete(result, false, failure);
        }
    }

    /**
     * 在主菜单前一次性完成当前注册表的静态目录导入。
     *
     * <p>这个入口故意不要求 {@code minecraft.player}：注册表和本地化名称在
     * 客户端加载阶段已经可用；依赖世界、玩家或第三方状态的动态 Tooltip 不在
     * 这里读取，避免启动期间执行模组逻辑。</p>
     */
    public static boolean syncBeforeMainMenu() {
        return syncBeforeMainMenu(ModPediaBridge.get());
    }

    /**
     * 在主菜单前捕获注册表数据，并把结果交给 Worker；游戏 JVM 不打开 knowledge.db。
     */
    public static boolean syncBeforeMainMenu(ModPediaBridge bridge) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.isSameThread()) {
            // 这个兼容入口过去会在调用线程同步读取全部动态信息；一旦被误用在
            // Render thread，就会把大型整合包卡在 0 FPS。生产代码统一走异步
            // 批处理入口，主线程只排队工作，不等待 Worker/SQLite。
            syncBeforeMainMenuAsync(bridge);
            return false;
        }
        CompletableFuture<Boolean> result = syncBeforeMainMenuAsync(bridge);
        try {
            return result.get(130L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            return false;
        }
    }

    /**
     * 保留兼容入口，但不再执行任何扫描。历史实现曾在这里按 Tick 调用
     * 动态 Tooltip，导致进入大型存档后继续占用游戏线程。
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        // 语言切换只允许在主菜单阶段触发一次新的捕获；进入世界后绝不启动
        // 静态目录扫描，因此不会把语言检查变成游戏内持续工作。
        if (shutdown || !clientLoadComplete || !mainMenuReady || !canCaptureInMenu(minecraft)
                || !ModPediaBridge.get().isReady()
                || System.currentTimeMillis() < retryAtMillis) {
            return;
        }
        String language = languageCode(minecraft);
        if (shouldRecaptureForLanguage(true, language, syncedLanguage)) {
            syncBeforeMainMenuAsync(ModPediaBridge.get());
        }
    }

    /** 游戏退出时取消任何后续启动回调；不会再向已经关闭的 Worker 发目录请求。 */
    public static void shutdown() {
        CompletableFuture<Boolean> active;
        synchronized (STATE_LOCK) {
            shutdown = true;
            clientLoadComplete = false;
            mainMenuReady = false;
            state = SyncState.SHUTDOWN;
            active = inFlight;
            inFlight = null;
            pendingCatalog = null;
        }
        if (active != null && !active.isDone()) {
            active.complete(false);
        }
        PERSIST_EXECUTOR.shutdownNow();
        CAPTURE_SCHEDULER.shutdownNow();
        CAPTURE_EXECUTOR.shutdownNow();
    }

    private static void beginCaptureOnClientThread(
            Minecraft minecraft,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            if (shutdown || !canCaptureInMenu(minecraft) || bridge == null || !bridge.isReady()) {
                complete(result, false, null);
                return;
            }
            String language = languageCode(minecraft);
            RegistrySnapshot snapshot = registrySnapshot(language);
            // 第二次启动不再重新构造数万条 Component。先用注册表 ID/描述键的
            // 轻量指纹判断持久化 JSONL 缓存，命中后只在后台读缓存并重新同步
            // Worker；数据库仍由 Worker 校验，缓存失效时才进入完整捕获。
            scheduleCachedCatalogLookup(
                    minecraft,
                    bridge,
                    result,
                    snapshot
            );
        } catch (Throwable failure) {
            ModPedia.LOGGER.warn("Pre-menu item catalog capture failed; previous catalog retained", failure);
            complete(result, false, failure);
        }
    }

    private static void scheduleCachedCatalogLookup(
            Minecraft minecraft,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result,
            RegistrySnapshot snapshot
    ) {
        String language = snapshot.language();
        String registryFingerprint = snapshot.registryFingerprint();
        synchronized (STATE_LOCK) {
            state = SyncState.REUSING_CACHE;
        }
        try {
            PERSIST_EXECUTOR.execute(() -> {
                CapturedCatalog cached = readCachedCatalog(language, registryFingerprint);
                try {
                    if (shutdown || bridge == null || !bridge.isReady()) {
                        complete(result, false, null);
                        return;
                    }
                    if (cached != null) {
                        // 名称反向索引只由不可变目录数据构建，不需要回到客户端线程。
                        // 旧实现把 6 万条 remember() 排队到 Render thread，缓存命中时
                        // 仍会造成一次明显卡顿。
                        ItemNameResolver.replaceLanguageIndex(cached.entries());
                        synchronized (STATE_LOCK) {
                            pendingCatalog = cached;
                            state = SyncState.PERSISTING;
                        }
                        ModPedia.LOGGER.info(
                                "Item catalog cache reused: language={}, items={}, registry_fingerprint={}",
                                language,
                                cached.entries().size(),
                                registryFingerprint
                        );
                        schedulePersist(cached, bridge, result);
                        return;
                    }
                    beginFullCapture(
                            bridge,
                            result,
                            snapshot
                    );
                } catch (Throwable failure) {
                    complete(result, false, failure);
                }
            });
        } catch (Throwable failure) {
            complete(result, false, failure);
        }
    }

    private static void beginFullCapture(
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result,
            RegistrySnapshot snapshot
    ) {
        ItemNameResolver.beginLanguageIndex();
        CaptureJob job = new CaptureJob(
                snapshot
        );
        try {
            CAPTURE_EXECUTOR.execute(() -> captureInBackground(job, bridge, result));
        } catch (Throwable failure) {
            ItemNameResolver.abortLanguageIndex();
            complete(result, false, failure);
        }
    }

    private static void captureInBackground(
            CaptureJob job,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            if (shutdown || bridge == null || !bridge.isReady()) {
                ItemNameResolver.abortLanguageIndex();
                complete(result, false, null);
                return;
            }
            while (job.nextIndex < job.items.size()) {
                int end = Math.min(job.items.size(), job.nextIndex + CAPTURE_BATCH_SIZE);
                for (int index = job.nextIndex; index < end; index++) {
                    CaptureResult captured = capture(job, job.items.get(index));
                    job.entries.set(index, captured.entry());
                    if (captured.nameFallback()) {
                        job.staticNameFallbacks++;
                    } else {
                        job.staticNameSuccesses++;
                    }
                    if (captured.descriptionAvailable()) {
                        job.staticDescriptionSuccesses++;
                    }
                    if (captured.itemFailure()) {
                        job.itemFailures++;
                    }
                }
                job.nextIndex = end;
                if (shutdown) {
                    ItemNameResolver.abortLanguageIndex();
                    complete(result, false, null);
                    return;
                }
            }
            finishCapture(job, bridge, result);
        } catch (Throwable failure) {
            ItemNameResolver.abortLanguageIndex();
            ModPedia.LOGGER.warn("Pre-menu item catalog capture failed; previous catalog retained", failure);
            complete(result, false, failure);
        }
    }

    private static void finishCapture(
            CaptureJob job,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            ItemNameResolver.finishLanguageIndex();
            List<ItemCatalogEntry> completedEntries = new ArrayList<>(job.entries.size());
            for (int index = 0; index < job.entries.size(); index++) {
                ItemCatalogEntry entry = job.entries.get(index);
                completedEntries.add(entry == null
                        ? fallbackCapture(job.language, job.items.get(index)).entry()
                        : entry);
            }
            ModPedia.LOGGER.info(
                    "Item catalog pre-menu capture completed: language={}, items={}, static_name_successes={}, static_name_fallbacks={}, static_description_successes={}, item_failures={}, capture_mode=background, capture_ms={}",
                    job.language,
                    completedEntries.size(),
                    job.staticNameSuccesses,
                    job.staticNameFallbacks,
                    job.staticDescriptionSuccesses,
                    job.itemFailures,
                    elapsedMillis(job.startedNanos)
            );
            CapturedCatalog captured = new CapturedCatalog(
                    job.language,
                    job.registryFingerprint,
                    List.copyOf(completedEntries)
            );
            synchronized (STATE_LOCK) {
                pendingCatalog = captured;
                state = SyncState.PERSISTING;
            }
            schedulePersist(captured, bridge, result);
        } catch (Throwable failure) {
            ItemNameResolver.abortLanguageIndex();
            ModPedia.LOGGER.warn("Pre-menu item catalog capture failed; previous catalog retained", failure);
            complete(result, false, failure);
        }
    }

    private static void schedulePersist(
            CapturedCatalog captured,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            PERSIST_EXECUTOR.execute(() -> {
                try {
                    boolean success = persist(captured, bridge);
                    complete(result, success, null);
                } catch (Throwable failure) {
                    complete(result, false, failure);
                }
            });
        } catch (Throwable failure) {
            complete(result, false, failure);
        }
    }

    private static void complete(
            CompletableFuture<Boolean> result,
            boolean success,
            Throwable failure
    ) {
        synchronized (STATE_LOCK) {
            if (inFlight == result) {
                inFlight = null;
                state = success ? SyncState.READY : SyncState.FAILED;
                if (!success) {
                    retryAtMillis = System.currentTimeMillis() + 5_000L;
                }
                if (success) {
                    pendingCatalog = null;
                }
            }
        }
        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            result.complete(success);
        }
    }

    private static RegistrySnapshot registrySnapshot(String language) {
        Map<String, String> languageData = ItemNameResolver.languageDataSnapshot();
        Map<String, String> englishLanguageData = ItemNameResolver.englishLanguageDataSnapshot();
        List<RegistryItem> items = BuiltInRegistries.ITEM.entrySet().stream()
                .filter(entry -> entry.getValue() != Items.AIR)
                .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                .map(entry -> registryItem(entry.getKey().location(), entry.getValue()))
                .toList();
        return new RegistrySnapshot(
                language,
                registryFingerprint(items, language, languageData, englishLanguageData),
                languageData,
                englishLanguageData,
                items
        );
    }

    private static RegistryItem registryItem(ResourceLocation id, Item item) {
        String descriptionId;
        try {
            descriptionId = item.getDescriptionId();
        } catch (Throwable ignored) {
            descriptionId = "";
        }
        List<String> loreLines = new ArrayList<>();
        try {
            ItemLore lore = item.components().get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    addDescriptionLine(loreLines, line == null ? "" : line.getString());
                }
            }
        } catch (Throwable ignored) {
            // 单个物品的数据组件异常只影响它自己的静态简介。
        }
        return new RegistryItem(id, descriptionId, List.copyOf(loreLines));
    }

    static boolean canCaptureInMenu(boolean hasLevel, boolean hasPlayer) {
        return !hasLevel && !hasPlayer;
    }

    /** 纯调度判定，供启动生命周期回归测试复用。 */
    static boolean shouldRecaptureForLanguage(
            boolean inMainMenu,
            String currentLanguage,
            String synchronizedLanguage
    ) {
        if (!inMainMenu) {
            return false;
        }
        String current = currentLanguage == null ? "" : currentLanguage.strip();
        String synchronizedValue = synchronizedLanguage == null ? "" : synchronizedLanguage.strip();
        return !current.isBlank() && !current.equals(synchronizedValue);
    }

    private static boolean canCaptureInMenu(Minecraft minecraft) {
        return minecraft != null
                && minecraft.options != null
                && mainMenuReady
                && isMenuCandidate(
                        minecraft.level != null,
                        minecraft.player != null,
                        minecraft.screen != null
                );
    }

    /** 过渡屏幕也可能暂时没有 level/player，必须与真正的主菜单区分。 */
    static boolean canCaptureInMenu(boolean hasLevel, boolean hasPlayer, boolean mainMenuScreen) {
        return !hasLevel && !hasPlayer && mainMenuScreen;
    }

    /** 只要客户端还没有进入世界，第三方菜单替换原生 TitleScreen 也算菜单阶段。 */
    static boolean isMenuCandidate(boolean hasLevel, boolean hasPlayer, boolean hasScreen) {
        return !hasLevel && !hasPlayer && hasScreen;
    }

    private static CaptureResult capture(CaptureJob job, RegistryItem registryItem) {
        String language = job.language;
        String itemId = registryItem.id().toString();
        String sourceMod = registryItem.id().getNamespace();
        try {
            java.util.Optional<String> localizedName = ItemNameResolver.staticLocalizedName(
                    registryItem.descriptionId(),
                    itemId,
                    job.languageData,
                    job.englishLanguageData
            );
            String displayName = localizedName
                    .filter(name -> ItemNameResolver.isDisplayNameUsable(name, itemId))
                    .orElseGet(
                            () -> ItemNameResolver.readableFallbackName(itemId)
                    );
            boolean nameFallback = localizedName.isEmpty()
                    || !ItemNameResolver.isDisplayNameUsable(localizedName.orElse(""), itemId);
            ItemNameResolver.remember(itemId, displayName);
            StaticDescription description = staticDescription(
                    registryItem.descriptionId(),
                    registryItem.loreLines(),
                    job.languageData
            );
            String fingerprint = fingerprint(itemId, language, displayName,
                    description.markdown(), sourceMod);
            return new CaptureResult(new ItemCatalogEntry(
                    itemId,
                    language,
                    displayName,
                    description.markdown(),
                    sourceMod,
                    fingerprint
            ), nameFallback, description.available(), false);
        } catch (Throwable ignored) {
            // 单个注册物品的实现异常只降级为 ID；其余物品继续完成本批导入。
            return fallbackCapture(language, registryItem, true);
        }
    }

    /**
     * 只读取不会执行物品逻辑的数据：默认 LORE 组件和已加载语言资源中的静态
     * 描述键。这里刻意不创建 ItemStack，也不调用动态 Tooltip 方法或任何 NeoForge
     * Tooltip 事件。
     */
    private static StaticDescription staticDescription(
            String descriptionId,
            List<String> loreLines,
            Map<String, String> languageData
    ) {
        List<String> lines = new ArrayList<>(loreLines == null ? List.of() : loreLines);
        try {
            for (String suffix : List.of(".tooltip", ".description", ".desc")) {
                String key = descriptionId + suffix;
                if (languageData != null && languageData.containsKey(key)) {
                    addDescriptionLine(lines, languageData.get(key));
                }
            }
        } catch (Throwable ignored) {
            // 语言资源异常不影响名称和 ID 导入。
        }
        String markdown = tooltipMarkdown(lines);
        return new StaticDescription(markdown, !markdown.isBlank());
    }

    private static void addDescriptionLine(List<String> lines, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String line : value.replace('\r', '\n').split("\\n")) {
            String normalized = line.strip();
            if (!normalized.isBlank() && !lines.contains(normalized)) {
                lines.add(normalized);
            }
        }
    }

    private static CaptureResult fallbackCapture(
            String language,
            RegistryItem registryItem,
            boolean itemFailure
    ) {
        String itemId = registryItem.id().toString();
        String sourceMod = registryItem.id().getNamespace();
        String displayName = ItemNameResolver.readableFallbackName(itemId);
        ItemNameResolver.remember(itemId, displayName);
        String fingerprint = fingerprint(itemId, language, displayName, "", sourceMod);
        return new CaptureResult(new ItemCatalogEntry(
                itemId,
                language,
                displayName,
                "",
                sourceMod,
                fingerprint
        ), true, false, itemFailure);
    }

    /** 动态 Tooltip 已移至 P1：玩家确认具体物品后按需读取。 */
    private static CaptureResult fallbackCapture(String language, RegistryItem registryItem) {
        return fallbackCapture(language, registryItem, false);
    }

    /** 兼容旧的启动诊断测试；当前静态扫描路径不再调用动态 Tooltip。 */
    static boolean isConfigurationUnavailable(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 8) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(java.util.Locale.ROOT)
                    .contains(CONFIG_NOT_LOADED_MESSAGE)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean persist(CapturedCatalog captured, ModPediaBridge bridge) {
        String language = captured.language();
        List<ItemCatalogEntry> entries = captured.entries();
        long started = System.nanoTime();
        try {
            saveCachedCatalog(captured);
            if (bridge == null || !bridge.syncItems(language, entries)) {
                synchronized (STATE_LOCK) {
                    // 失败后保留本次已捕获的目录，等待 Worker 恢复时只重试
                    // SQLite/IPC 写入，不再次回到注册表执行静态目录全量读取。
                    pendingCatalog = captured;
                    state = SyncState.WAITING_WORKER;
                    retryAtMillis = System.currentTimeMillis() + 5_000L;
                }
                ModPedia.LOGGER.warn(
                        "Item catalog Worker unavailable after {} ms; previous catalog retained",
                        elapsedMillis(started)
                );
                return false;
            }
            syncedLanguage = language;
            retryAtMillis = 0L;
            ModPedia.LOGGER.info(
                    "Item catalog sync request completed: language={}, items={}, worker_ms={}",
                    language,
                    entries.size(),
                    elapsedMillis(started)
            );
            return true;
        } catch (Exception exception) {
            synchronized (STATE_LOCK) {
                pendingCatalog = captured;
                state = SyncState.WAITING_WORKER;
                retryAtMillis = System.currentTimeMillis() + 5_000L;
            }
            ModPedia.LOGGER.warn(
                    "Item catalog sync failed after {} ms; previous catalog retained",
                    elapsedMillis(started),
                    exception
            );
            return false;
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String registryFingerprint(
            List<RegistryItem> items,
            String language,
            Map<String, String> languageData,
            Map<String, String> englishLanguageData
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((language == null ? "" : language).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            // LanguageData 是已经加载好的静态翻译表；只计算其稳定摘要，不逐项
            // 创建 Component。这样资源包/模组翻译变化会使启动缓存失效，而不会
            // 把第二次启动重新变成数万次 Tooltip 读取。
            digest.update(Integer.toString(languageData == null ? 0 : languageData.hashCode())
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(Integer.toString(
                            englishLanguageData == null ? 0 : englishLanguageData.hashCode()
                    )
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            for (RegistryItem registryItem : items) {
                digest.update(registryItem.id().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(registryItem.descriptionId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                for (String loreLine : registryItem.loreLines()) {
                    digest.update(loreLine.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static CapturedCatalog readCachedCatalog(String language, String registryFingerprint) {
        ModPediaPaths paths = ModPediaPaths.forConfig(FMLPaths.CONFIGDIR.get());
        Path statePath = paths.itemCatalogCacheState();
        Path cachePath = paths.itemCatalogCache();
        if (!Files.isRegularFile(statePath) || !Files.isRegularFile(cachePath)) {
            return null;
        }
        try {
            JsonObject state = JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            if (state.get("version").getAsInt() != ITEM_CATALOG_CACHE_VERSION
                    || !language.equals(state.get("language").getAsString())
                    || !registryFingerprint.equals(state.get("registry_fingerprint").getAsString())) {
                return null;
            }
            int expectedCount = state.get("item_count").getAsInt();
            if (expectedCount < 0) {
                return null;
            }
            List<ItemCatalogEntry> entries = new ArrayList<>(expectedCount);
            try (BufferedReader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    ItemCatalogEntry entry = WorkerPayloadCodec.item(
                            JsonParser.parseString(line).getAsJsonObject()
                    );
                    if (!language.equals(entry.language())) {
                        return null;
                    }
                    if (!ItemNameResolver.isDisplayNameUsable(entry.displayName(), entry.itemId())) {
                        return null;
                    }
                    entries.add(entry);
                }
            }
            return entries.size() == expectedCount
                    ? new CapturedCatalog(language, registryFingerprint, List.copyOf(entries))
                    : null;
        } catch (Exception exception) {
            ModPedia.LOGGER.debug("Item catalog cache invalid; falling back to static capture: {}",
                    exception.getMessage());
            return null;
        }
    }

    private static void saveCachedCatalog(CapturedCatalog captured) {
        ModPediaPaths paths = ModPediaPaths.forConfig(FMLPaths.CONFIGDIR.get());
        Path cachePath = paths.itemCatalogCache();
        Path statePath = paths.itemCatalogCacheState();
        Path cacheTemporary = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
        Path stateTemporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try {
            Files.createDirectories(cachePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(cacheTemporary, StandardCharsets.UTF_8)) {
                for (ItemCatalogEntry entry : captured.entries()) {
                    writer.write(WorkerPayloadCodec.item(entry).toString());
                    writer.newLine();
                }
            }
            JsonObject state = new JsonObject();
            state.addProperty("version", ITEM_CATALOG_CACHE_VERSION);
            state.addProperty("language", captured.language());
            state.addProperty("registry_fingerprint", captured.registryFingerprint());
            state.addProperty("item_count", captured.entries().size());
            Files.writeString(stateTemporary, state.toString(), StandardCharsets.UTF_8);
            atomicReplace(cacheTemporary, cachePath);
            atomicReplace(stateTemporary, statePath);
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(cacheTemporary);
            deleteQuietly(stateTemporary);
            ModPedia.LOGGER.warn("Item catalog cache write failed; next startup will recapture", exception);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String languageCode(Minecraft minecraft) {
        return SearchLanguage.fromMinecraft(minecraft.options.languageCode).code();
    }

    static String tooltipMarkdown(List<String> lines) {
        StringBuilder markdown = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    markdown.append("- ").append(line.strip()).append('\n');
                }
            }
        }
        return markdown.toString().strip();
    }

    static String fingerprint(
            String itemId,
            String language,
            String displayName,
            String descriptionMarkdown,
            String sourceMod
    ) {
        String value = String.join("\n", itemId, language, displayName, descriptionMarkdown, sourceMod);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record RegistryItem(
            ResourceLocation id,
            String descriptionId,
            List<String> loreLines
    ) {
    }

    private record RegistrySnapshot(
            String language,
            String registryFingerprint,
            Map<String, String> languageData,
            Map<String, String> englishLanguageData,
            List<RegistryItem> items
    ) {
    }

    private record CaptureResult(
            ItemCatalogEntry entry,
            boolean nameFallback,
            boolean descriptionAvailable,
            boolean itemFailure
    ) {
    }

    private record StaticDescription(String markdown, boolean available) {
    }

    private record CapturedCatalog(
            String language,
            String registryFingerprint,
            List<ItemCatalogEntry> entries
    ) {
    }

    private static final class CaptureJob {
        private final String language;
        private final Map<String, String> languageData;
        private final Map<String, String> englishLanguageData;
        private final List<RegistryItem> items;
        private final String registryFingerprint;
        private final List<ItemCatalogEntry> entries;
        private final long startedNanos = System.nanoTime();
        private int nextIndex;
        private int staticNameSuccesses;
        private int staticNameFallbacks;
        private int staticDescriptionSuccesses;
        private int itemFailures;

        private CaptureJob(RegistrySnapshot snapshot) {
            this.language = snapshot.language();
            this.languageData = snapshot.languageData();
            this.englishLanguageData = snapshot.englishLanguageData();
            this.items = snapshot.items();
            this.registryFingerprint = snapshot.registryFingerprint();
            this.entries = new ArrayList<>(java.util.Collections.nCopies(items.size(), null));
        }
    }

    private enum SyncState {
        IDLE,
        CAPTURING,
        REUSING_CACHE,
        PERSISTING,
        WAITING_WORKER,
        READY,
        FAILED,
        SHUTDOWN
    }

}
