package io.ctyx.modpedia;

import com.mojang.blaze3d.platform.InputConstants;
import io.ctyx.modpedia.client.AssistantScreen;
import io.ctyx.modpedia.client.AssistantSession;
import io.ctyx.modpedia.client.AssistantInputPolicy;
import io.ctyx.modpedia.client.FtbQuestsClientAdapter;
import io.ctyx.modpedia.client.JadeClientBridge;
import io.ctyx.modpedia.client.JadeTargetStore;
import io.ctyx.modpedia.client.ItemCatalogSyncService;
import io.ctyx.modpedia.client.JeiRecipeNavigator;
import io.ctyx.modpedia.client.ManualSourceNavigator;
import io.ctyx.modpedia.client.MockAssistantSession;
import io.ctyx.modpedia.client.FloatingAssistantWindow;
import io.ctyx.modpedia.client.ModPediaBridge;
import io.ctyx.modpedia.client.PonderJsCompatibility;
import io.ctyx.modpedia.client.StartupKnowledgeBootstrap;
import io.ctyx.modpedia.client.TaskWikiSyncService;
import io.ctyx.modpedia.client.WorkerAssistantSession;
import io.ctyx.modpedia.task.TaskRuntimeReadResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;

/** 客户端入口，负责启动知识库构建和注册手动重建入口。 */
public final class ModPediaClient {
    static final KeyMapping OPEN_ASSISTANT = new KeyMapping(
            "key.modpedia.open_assistant",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            "key.categories.modpedia"
    );
    static final KeyMapping REBUILD_KNOWLEDGE = new KeyMapping(
            "key.modpedia.rebuild_knowledge",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F9,
            "key.categories.modpedia"
    );
    static final AssistantSession ASSISTANT_SESSION = Boolean.getBoolean("modpedia.ai.mock")
            ? new MockAssistantSession()
            : new WorkerAssistantSession();
    static final FtbQuestsClientAdapter FTB_QUESTS = new FtbQuestsClientAdapter();

    /** 供真实 AI 会话注入可选任务运行时读取器；缺少 FTB Quests 时仍返回惰性适配器。 */
    public static FtbQuestsClientAdapter taskAdapter() {
        return FTB_QUESTS;
    }

    public ModPediaClient(IEventBus modEventBus, ModContainer modContainer) {
        ModPedia.LOGGER.info("Loading ModPedia client components");
        modEventBus.addListener(ModPediaClient::onRegisterKeyMappings);
        modEventBus.addListener(ModPediaClient::onClientSetup);
        modEventBus.addListener(ModPediaClient::onLoadComplete);
        MinecraftForge.EVENT_BUS.register(ModPediaClientEvents.class);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_ASSISTANT);
        event.register(REBUILD_KNOWLEDGE);
    }

    static void onClientSetup(FMLClientSetupEvent event) {
        // Worker 只负责等待这份临时快照；单机优先只发送存档路径描述，让 Worker
        // 直接读取极小的 FTBQ SNBT 文件。多人或本地文件不可用时，游戏 JVM 才
        // 读取 TeamData。Worker 收到当前进度后才能查询 knowledge.db 的静态任务定义。
        ModPediaBridge bridge = ModPediaBridge.get();
        bridge.setRuntimeContextHandler(request -> {
            var localFile = FTB_QUESTS.localFileDescriptor(request.query());
            var completedSnapshot = FTB_QUESTS.completedSnapshot(request.query());
            if (localFile.isPresent()) {
                return ModPediaBridge.RuntimeContextResult.file(localFile.get(), completedSnapshot);
            }
            TaskRuntimeReadResult result = FTB_QUESTS.readForQuery(
                    request.query(),
                    request.requestId()
            );
            return ModPediaBridge.RuntimeContextResult.snapshot(result);
        });
        bridge.setRecipeQueryHandler(JeiRecipeNavigator::query);
    }

    /**
     * FMLClientSetup 期间仍可能有可选模组配置未完成。把 Worker 启动、知识库构建
     * 和 Tooltip 目录捕获统一推迟到所有模组完成加载后，避免第三方 Tooltip 处理器
     * 在配置尚未加载时被全量调用。
     */
    static void onLoadComplete(FMLLoadCompleteEvent event) {
        ItemCatalogSyncService.markClientLoadComplete();
        PonderJsCompatibility.inspectAsync();
        event.enqueueWork(() -> {
            FTB_QUESTS.registerCompletionListener();
            TaskWikiSyncService.startAfter(StartupKnowledgeBootstrap.startAsync());
        });
    }
}

/** 客户端游戏总线事件，处理手动重建按键。 */
final class ModPediaClientEvents {
    private static int suppressQueuedAssistantClicks;

    private ModPediaClientEvents() {
    }

    /**
     * 在原始 GLFW 输入事件层处理助手快捷键。
     *
     * <p>部分第三方页面（尤其是自定义 Widget/ScreenWrapper）不会可靠地进入
     * {@link ScreenEvent.KeyPressed.Pre}，或者会在自己的键盘处理链中提前吞掉 K。
     * 原始输入事件发生在 Screen 分发之前，因此这里作为统一入口；后续 Screen 事件
     * 和普通 KeyMapping 消费只负责抑制重复触发。</p>
     */
    @SubscribeEvent
    static void onRawKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS
                || !matchesAssistantKey(event.getKey(), event.getScanCode())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // Forge 1.20.1 的 InputEvent.Key 可能在 ScreenEvent.KeyPressed.Pre 之后
        // 到达。屏幕存在时交给 ScreenEvent 统一处理，避免 ScreenEvent 已打开
        // AssistantScreen 后，InputEvent.Key 又把它立即关闭。
        if (!shouldHandleRawKey(minecraft.screen != null)) {
            return;
        }
        suppressQueuedAssistantClicks = 2;
        toggleAssistant();
    }

    /**
     * Screen 会先于普通 KeyMapping 消费处理按键。拦截第三方 GUI 的 K，保证
     * FTBQ、JEI、容器和其它自定义页面都能打开助手，同时把后续一次 queued click
     * 抵消，避免同一按键在本 tick 被打开后又立即关闭。
     */
    @SubscribeEvent
    static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!matchesAssistantKey(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        if (AssistantInputPolicy.blocksAssistant(event.getScreen())) {
            event.setCanceled(true);
            suppressQueuedAssistantClicks = 2;
            return;
        }
        if (event.getScreen() instanceof AssistantScreen) {
            event.setCanceled(true);
            suppressQueuedAssistantClicks = 2;
            toggleAssistant();
            return;
        }
        event.setCanceled(true);
        suppressQueuedAssistantClicks = 2;
        toggleAssistant();
    }

    /** 原始输入只负责没有 Screen 的游戏画面；有 Screen 时由 ScreenEvent 处理。 */
    static boolean shouldHandleRawKey(boolean hasScreen) {
        return !hasScreen;
    }

    private static boolean matchesAssistantKey(int keyCode, int scanCode) {
        InputConstants.Key eventKey = InputConstants.getKey(keyCode, scanCode);
        if (ModPediaClient.OPEN_ASSISTANT.isActiveAndMatches(eventKey)) {
            return true;
        }
        // 某些自定义 Screen 传入的 keyCode/scanCode 组合会让
        // InputConstants.getKey() 选错类型；按绑定本身再做一次直接匹配。
        InputConstants.Key configured = ModPediaClient.OPEN_ASSISTANT.getKey();
        return configured.getType() == InputConstants.Type.KEYSYM
                ? keyCode == configured.getValue()
                : scanCode == configured.getValue();
    }

    /** 任意原生或第三方 GUI 在真正绘制 Tooltip 前都会经过的客户端事件。 */
    @SubscribeEvent
    static void onGatherTooltip(RenderTooltipEvent.GatherComponents event) {
        JadeTargetStore.updateFromTooltip(event.getItemStack());
    }

    /**
     * ScreenEvent.Render.Post 位于 ForgeHooksClient 的当前 Screen 绘制之后。
     * 用最低优先级做最后一次助手覆盖，处理 JEI/FTB 在父 Screen 中追加的物品层。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof AssistantScreen assistantScreen) {
            assistantScreen.renderFinalOverlay(
                    event.getGuiGraphics(),
                    event.getMouseX(),
                    event.getMouseY(),
                    event.getPartialTick()
            );
        }
    }

    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ModPediaClient.FTB_QUESTS.observeWorld(minecraft);
        ModPediaBridge.get().observeClientWorld(minecraft.level, minecraft.player);
        // 只在主菜单阶段检查语言切换。ItemCatalogSyncService 在进入世界后
        // 会立即返回，因此这里不会重新触发游戏内的全量 Tooltip 扫描。
        ItemCatalogSyncService.observeMenuState(minecraft);
        ItemCatalogSyncService.tick();
        JadeClientBridge.tick();
        JadeTargetStore.tick(minecraft);
        if (ModPediaClient.OPEN_ASSISTANT.consumeClick()) {
            if (suppressQueuedAssistantClicks > 0) {
                suppressQueuedAssistantClicks = 0;
            } else {
                toggleAssistant();
            }
            return;
        }
        if (suppressQueuedAssistantClicks > 0) {
            suppressQueuedAssistantClicks--;
        }
        if (!ModPediaClient.REBUILD_KNOWLEDGE.consumeClick()) {
            return;
        }

        String messageKey = ModPediaBridge.get().rebuildKnowledgeAsync(
                StartupKnowledgeBootstrap.resolveModsDirectory(),
                true
        )
                ? "message.modpedia.rebuild_started"
                : "message.modpedia.rebuild_busy";
        minecraft.gui.setOverlayMessage(Component.translatable(messageKey), false);
    }

    private static void toggleAssistant() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AssistantScreen assistantScreen) {
            assistantScreen.onClose();
            return;
        }
        JadeTargetStore.freezeForAssistant(minecraft);
        AssistantScreen assistantScreen = new AssistantScreen(
                minecraft.screen,
                ModPediaClient.ASSISTANT_SESSION,
                new ManualSourceNavigator()
        );
        minecraft.setScreen(assistantScreen);
    }

    @SubscribeEvent
    static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null
                && minecraft.level == null
                && minecraft.player == null
                && event.getScreen() != null) {
            ItemCatalogSyncService.markMainMenuReady();
        }
    }

    @SubscribeEvent
    static void onScreenClosing(ScreenEvent.Closing event) {
        AssistantScreen.handleExternalScreenClosing(event.getScreen());
    }

    @SubscribeEvent
    static void onGameShuttingDown(GameShuttingDownEvent event) {
        // Worker、IPC reader、启动任务和物品目录载荷都属于客户端生命周期；
        // 退出时必须先停止它们，避免关闭游戏后留下孤儿 JVM 或后台回调。
        ModPediaClient.FTB_QUESTS.unregisterCompletionListener();
        ModPediaClient.FTB_QUESTS.clearRuntimeCache();
        ItemCatalogSyncService.shutdown();
        PonderJsCompatibility.shutdown();
        StartupKnowledgeBootstrap.shutdown();
        ModPediaBridge.get().shutdown();
    }
}
