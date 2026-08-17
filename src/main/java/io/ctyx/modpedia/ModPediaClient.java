package io.ctyx.modpedia;

import com.mojang.blaze3d.platform.InputConstants;
import io.ctyx.modpedia.client.AssistantScreen;
import io.ctyx.modpedia.client.AssistantSession;
import io.ctyx.modpedia.client.FtbQuestsClientAdapter;
import io.ctyx.modpedia.client.JadeClientBridge;
import io.ctyx.modpedia.client.JadeTargetStore;
import io.ctyx.modpedia.client.ItemCatalogSyncService;
import io.ctyx.modpedia.client.ManualSourceNavigator;
import io.ctyx.modpedia.client.MockAssistantSession;
import io.ctyx.modpedia.client.FloatingAssistantWindow;
import io.ctyx.modpedia.client.ModPediaBridge;
import io.ctyx.modpedia.client.StartupKnowledgeBootstrap;
import io.ctyx.modpedia.client.TaskWikiSyncService;
import io.ctyx.modpedia.client.WorkerAssistantSession;
import io.ctyx.modpedia.task.TaskRuntimeReadResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** 客户端入口，负责启动知识库构建和注册手动重建入口。 */
@Mod(value = ModPedia.MOD_ID, dist = Dist.CLIENT)
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
        NeoForge.EVENT_BUS.register(ModPediaClientEvents.class);
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
    }

    /**
     * FMLClientSetup 期间仍可能有可选模组配置未完成。把 Worker 启动、知识库构建
     * 和 Tooltip 目录捕获统一推迟到所有模组完成加载后，避免第三方 Tooltip 处理器
     * 在配置尚未加载时被全量调用。
     */
    static void onLoadComplete(FMLLoadCompleteEvent event) {
        ItemCatalogSyncService.markClientLoadComplete();
        event.enqueueWork(() -> {
            FTB_QUESTS.registerCompletionListener();
            TaskWikiSyncService.startAfter(StartupKnowledgeBootstrap.startAsync());
        });
    }
}

/** 客户端游戏总线事件，处理手动重建按键。 */
final class ModPediaClientEvents {
    private ModPediaClientEvents() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ModPediaClient.FTB_QUESTS.observeWorld(minecraft);
        ModPediaBridge.get().observeClientWorld(minecraft.level, minecraft.player);
        // 只在主菜单阶段检查语言切换。ItemCatalogSyncService 在进入世界后
        // 会立即返回，因此这里不会重新触发游戏内的全量 Tooltip 扫描。
        ItemCatalogSyncService.tick();
        JadeClientBridge.tick();
        JadeTargetStore.tick(minecraft);
        if (ModPediaClient.OPEN_ASSISTANT.consumeClick()) {
            if (minecraft.screen instanceof AssistantScreen assistantScreen) {
                assistantScreen.onClose();
            } else {
                AssistantScreen assistantScreen = new AssistantScreen(
                        minecraft.screen,
                        ModPediaClient.ASSISTANT_SESSION,
                        new ManualSourceNavigator()
                );
                minecraft.setScreen(assistantScreen);
            }
            return;
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

    @SubscribeEvent
    static void onGameShuttingDown(GameShuttingDownEvent event) {
        // Worker、IPC reader、启动任务和物品目录载荷都属于客户端生命周期；
        // 退出时必须先停止它们，避免关闭游戏后留下孤儿 JVM 或后台回调。
        ModPediaClient.FTB_QUESTS.unregisterCompletionListener();
        ModPediaClient.FTB_QUESTS.clearRuntimeCache();
        ItemCatalogSyncService.shutdown();
        StartupKnowledgeBootstrap.shutdown();
        ModPediaBridge.get().shutdown();
    }
}
