package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.search.SearchLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 在客户端注册表冻结后，把当前语言的注册物品 Tooltip 同步到 knowledge.db。 */
public final class ItemCatalogSyncService {
    private static final int CAPTURE_BATCH_SIZE = 64;
    /** 给第三方配置和 Tooltip 注册器一个稳定窗口，避免在 FML load complete 的
     * 同一时刻捕获到“配置尚未加载”的临时状态。 */
    private static final long INITIAL_CAPTURE_DELAY_MS = 3_000L;
    /** 全量 Tooltip 仅用于补充简介；配置尚未完成时直接关闭，避免第三方
     * Tooltip 监听器在每个物品上重复抛错并把启动日志放大到数 GB。名称和 ID
     * 仍然通过本地化注册表捕获，不受该降级影响。 */
    private static final String CONFIG_NOT_LOADED_MESSAGE = "config value before config is loaded";
    private static final Object STATE_LOCK = new Object();
    private static final ExecutorService PERSIST_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modpedia-item-catalog-persist");
        thread.setDaemon(true);
        return thread;
    });
    /** 在客户端线程批量捕获之间让出一帧，避免 60k 个 Tooltip 连续占满渲染线程。 */
    private static final ScheduledExecutorService CAPTURE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "modpedia-item-catalog-capture-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile boolean shutdown;
    private static volatile String syncedLanguage = "";
    private static volatile long retryAtMillis;
    private static volatile boolean clientLoadComplete;
    /** FMLLoadComplete 早于部分整合包的最终配置初始化；只有真正打开主菜单
     * 后才开始触发会广播 Tooltip 事件的捕获。 */
    private static volatile boolean mainMenuReady;
    private static CompletableFuture<Boolean> inFlight;
    private static CapturedCatalog pendingCatalog;
    private static SyncState state = SyncState.IDLE;

    private ItemCatalogSyncService() {
    }

    /**
     * 在首轮知识库构建完成后调用。物品 Tooltip 必须在客户端线程读取，但整个
     * 注册表只允许在进入世界前一次性完成；这里不再把扫描分摊到 ClientTick。
     */
    public static void startAsync() {
        syncBeforeMainMenuAsync(ModPediaBridge.get());
    }

    /**
     * FMLLoadCompleteEvent 之后才允许触发 Tooltip 捕获。此时模组配置和客户端
     * 注册流程已经完成，避免在 FMLClientSetup 阶段调用第三方 Tooltip 处理器。
     */
    public static void markClientLoadComplete() {
        clientLoadComplete = true;
        if (mainMenuReady) {
            syncBeforeMainMenuAsync(ModPediaBridge.get());
        }
    }

    /** 由 TitleScreen 打开事件调用，作为安全的全量 Tooltip 捕获起点。 */
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
     * 触发，也不会把 Tooltip 全量捕获推迟到游戏内。
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
     * 把一次性 Tooltip 捕获安排到客户端线程，并让启动流程等待其完成。
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
     * 在主菜单前一次性完成当前注册表的 Tooltip 导入。
     *
     * <p>这个入口故意不要求 {@code minecraft.player}：注册表和本地化名称在
     * 客户端加载阶段已经可用，个别必须依赖玩家/世界的 Tooltip 会由 capture()
     * 单项降级为无简介，不会把整批工作推迟到进入世界后。</p>
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
            // 这个兼容入口过去会在调用线程同步捕获全部 Tooltip；一旦被误用在
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
     * ItemStack#getTooltipLines，导致进入大型存档后继续占用游戏线程。
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        // 语言切换只允许在主菜单阶段触发一次新的捕获；进入世界后绝不启动
        // Tooltip 扫描，因此不会把语言检查变成游戏内持续工作。
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
            CaptureJob job = new CaptureJob(
                    minecraft,
                    languageCode(minecraft),
                    registryItems()
            );
            ItemNameResolver.beginLanguageIndex();
            continueCaptureOnClientThread(job, bridge, result);
        } catch (Throwable failure) {
            ModPedia.LOGGER.warn("Pre-menu item catalog capture failed; previous catalog retained", failure);
            complete(result, false, failure);
        }
    }

    private static void continueCaptureOnClientThread(
            CaptureJob job,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        try {
            if (shutdown || !canCaptureInMenu(job.minecraft)
                    || bridge == null || !bridge.isReady()) {
                ItemNameResolver.abortLanguageIndex();
                complete(result, false, null);
                return;
            }
            int end = Math.min(job.items.size(), job.nextIndex + CAPTURE_BATCH_SIZE);
            for (int index = job.nextIndex; index < end; index++) {
                CaptureResult captured = capture(
                        job.minecraft,
                        job.language,
                        job.items.get(index),
                        !job.tooltipCaptureDisabled
                );
                job.entries.set(index, captured.entry());
                if (!captured.tooltipAvailable()) {
                    job.tooltipFallbacks++;
                }
                if (captured.tooltipFailure()) {
                    job.tooltipFailures++;
                    // ItemStack#getTooltipLines 会广播第三方 Tooltip 事件；某个
                    // 监听器一旦抛错，继续对后续物品调用就会让 NeoForge 为每个
                    // 物品重复打印完整堆栈。Tooltip 只是简介增强，ID 和名称仍
                    // 然可以完整导入，因此对本次扫描立即熔断。
                    job.tooltipCaptureDisabled = true;
                } else if (!captured.entry().descriptionMarkdown().isBlank()) {
                    job.tooltipSuccesses++;
                }
                // “config value before config is loaded” 是整合包启动阶段的
                // 全局状态，不是单个物品坏了。保留这个字段用于诊断；实际
                // 熔断条件覆盖所有第三方 Tooltip 异常，避免其它异常类型也
                // 在大型注册表中造成重复日志。
                if (captured.configurationUnavailable()) {
                    job.tooltipCaptureDisabled = true;
                }
            }
            job.nextIndex = end;
            if (end < job.items.size()) {
                scheduleNextCapture(job, bridge, result);
                return;
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
                    "Item catalog pre-menu capture completed: language={}, items={}, tooltip_successes={}, tooltip_fallbacks={}, tooltip_failures={}, tooltip_capture_disabled={}, capture_ms={}",
                    job.language,
                    completedEntries.size(),
                    job.tooltipSuccesses,
                    job.tooltipFallbacks,
                    job.tooltipFailures,
                    job.tooltipCaptureDisabled,
                    elapsedMillis(job.startedNanos)
            );
            CapturedCatalog captured = new CapturedCatalog(job.language, List.copyOf(completedEntries));
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

    private static void scheduleNextCapture(
            CaptureJob job,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result
    ) {
        scheduleNextCapture(job, bridge, result, 1L);
    }

    private static void scheduleNextCapture(
            CaptureJob job,
            ModPediaBridge bridge,
            CompletableFuture<Boolean> result,
            long delayMillis
    ) {
        try {
            CAPTURE_SCHEDULER.schedule(() -> {
                if (shutdown) {
                    ItemNameResolver.abortLanguageIndex();
                    complete(result, false, null);
                    return;
                }
                try {
                    job.minecraft.execute(() -> continueCaptureOnClientThread(job, bridge, result));
                } catch (Throwable failure) {
                    ItemNameResolver.abortLanguageIndex();
                    complete(result, false, failure);
                }
            }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            ItemNameResolver.abortLanguageIndex();
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
                    boolean success = persist(captured.language(), captured.entries(), bridge);
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

    private static List<RegistryItem> registryItems() {
        return BuiltInRegistries.ITEM.entrySet().stream()
                .filter(entry -> entry.getValue() != Items.AIR)
                .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                .map(entry -> new RegistryItem(entry.getKey().location(), entry.getValue()))
                .toList();
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

    private static CaptureResult capture(
            Minecraft minecraft,
            String language,
            RegistryItem registryItem,
            boolean includeTooltip
    ) {
        ItemStack stack;
        try {
            stack = new ItemStack(registryItem.item());
        } catch (Throwable exception) {
            return fallbackCapture(language, registryItem);
        }
        TooltipCapture tooltip = includeTooltip
                ? captureTooltip(minecraft, registryItem.item())
                : TooltipCapture.disabled();
        String displayName;
        try {
            displayName = ItemNameResolver.localizedName(
                    stack,
                    registryItem.item(),
                    registryItem.id().toString()
            ).orElse("");
        } catch (Throwable exception) {
            displayName = "";
        }
        if (displayName.isBlank()) {
            displayName = registryItem.id().toString();
        } else {
            ItemNameResolver.remember(registryItem.id().toString(), displayName);
        }

        String descriptionMarkdown = tooltip.markdown();
        String itemId = registryItem.id().toString();
        String sourceMod = registryItem.id().getNamespace();
        String fingerprint = fingerprint(itemId, language, displayName, descriptionMarkdown, sourceMod);
        return new CaptureResult(new ItemCatalogEntry(
                    itemId,
                    language,
                    displayName,
                descriptionMarkdown,
                sourceMod,
                fingerprint
        ), tooltip.available(), tooltip.failure(), tooltip.configurationUnavailable());
    }

    private static TooltipCapture captureTooltip(Minecraft minecraft, Item item) {
        try {
            ItemStack stack = new ItemStack(item);
            List<Component> lines = stack.getTooltipLines(
                    Item.TooltipContext.EMPTY,
                    minecraft == null ? null : minecraft.player,
                    TooltipFlag.NORMAL
            );
            StringBuilder markdown = new StringBuilder();
            // 第一行是显示名称，名称已经由 localizedName() 单独保存；Tooltip
            // 简介从第二行开始，保持原顺序并转换为完整 Markdown 列表。
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index).getString().strip();
                if (!line.isBlank()) {
                    markdown.append("- ").append(line).append('\n');
                }
            }
            return new TooltipCapture(markdown.toString().strip(), true, false, false);
        } catch (Throwable exception) {
            // 第三方 Tooltip 监听器可能在整合包配置完成前抛出异常。名称仍然
            // 已经可以保存；对“配置未加载”直接结束本次 Tooltip 捕获，避免把
            // 同一个异常放大到整个注册表。
            boolean configurationUnavailable = isConfigurationUnavailable(exception);
            return new TooltipCapture("", false, true, configurationUnavailable);
        }
    }

    /** NeoForge 事件总线有时把第三方异常包在多个 Completion/Invocation cause 中。 */
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

    private static CaptureResult fallbackCapture(String language, RegistryItem registryItem) {
        String itemId = registryItem.id().toString();
        String sourceMod = registryItem.id().getNamespace();
        String fingerprint = fingerprint(itemId, language, itemId, "", sourceMod);
        return new CaptureResult(new ItemCatalogEntry(
                itemId,
                language,
                itemId,
                "",
                sourceMod,
                fingerprint
        ), false, false, false);
    }

    private static boolean persist(String language, List<ItemCatalogEntry> entries, ModPediaBridge bridge) {
        long started = System.nanoTime();
        try {
            if (bridge == null || !bridge.syncItems(language, entries)) {
                synchronized (STATE_LOCK) {
                    // 失败后保留本次已捕获的目录，等待 Worker 恢复时只重试
                    // SQLite/IPC 写入，不再次回到注册表执行 Tooltip 全量捕获。
                    pendingCatalog = new CapturedCatalog(language, List.copyOf(entries));
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
                pendingCatalog = new CapturedCatalog(language, List.copyOf(entries));
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

    private record RegistryItem(ResourceLocation id, Item item) {
    }

    private record CaptureResult(
            ItemCatalogEntry entry,
            boolean tooltipAvailable,
            boolean tooltipFailure,
            boolean configurationUnavailable
    ) {
    }

    private record TooltipCapture(
            String markdown,
            boolean available,
            boolean failure,
            boolean configurationUnavailable
    ) {
        private static TooltipCapture disabled() {
            return new TooltipCapture("", false, false, false);
        }
    }

    private record CapturedCatalog(String language, List<ItemCatalogEntry> entries) {
    }

    private static final class CaptureJob {
        private final Minecraft minecraft;
        private final String language;
        private final List<RegistryItem> items;
        private final List<ItemCatalogEntry> entries;
        private final long startedNanos = System.nanoTime();
        private int nextIndex;
        private int tooltipFallbacks;
        private int tooltipFailures;
        private int tooltipSuccesses;
        private boolean tooltipCaptureDisabled;

        private CaptureJob(Minecraft minecraft, String language, List<RegistryItem> items) {
            this.minecraft = minecraft;
            this.language = language;
            this.items = items;
            this.entries = new ArrayList<>(java.util.Collections.nCopies(items.size(), null));
        }
    }

    private enum SyncState {
        IDLE,
        CAPTURING,
        PERSISTING,
        WAITING_WORKER,
        READY,
        FAILED,
        SHUTDOWN
    }

}
