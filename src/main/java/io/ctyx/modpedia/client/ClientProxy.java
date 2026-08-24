package io.ctyx.modpedia.client;

import io.ctyx.modpedia.CommonProxy;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import io.ctyx.modpedia.knowledge.ManualCatalogBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiWorldSelection;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11;

/** 1.12.2 客户端入口；所有可选联动和 UI 类只从这一侧加载。 */
@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {
    private static final KeyBinding OPEN_ASSISTANT = new KeyBinding(
            "key.modpedia.open", Keyboard.KEY_K, "key.categories.modpedia"
    );
    private Path configDirectory;
    private Path instanceRoot;
    private volatile boolean knowledgePrepared;
    private volatile boolean workerReady;
    private volatile boolean rebuildRequested;
    private boolean remoteWorldActive;
    private String runtimeContextKey;
    private PendingWorldAction pendingWorldAction;

    @Override
    public void preInitialize(FMLPreInitializationEvent event) {
        super.preInitialize(event);
        configDirectory = event.getModConfigurationDirectory().toPath().toAbsolutePath().normalize();
        instanceRoot = configDirectory.getParent() == null
                ? configDirectory : configDirectory.getParent();
        ManualCatalogBootstrap.schedule(event.getModConfigurationDirectory(), new Runnable() {
            @Override
            public void run() {
                knowledgePrepared = true;
                requestKnowledgeRebuild();
            }
        });
        ClientRegistry.registerKeyBinding(OPEN_ASSISTANT);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(LegacyTargetStore.get());
        MinecraftForge.EVENT_BUS.register(LegacyItemCatalogSyncService.get());
    }

    @Override
    public void postInitialize(FMLPostInitializationEvent event) {
        LegacyWorkerBridge.get().startAsync(configDirectory, instanceRoot, new Runnable() {
            @Override
            public void run() {
                workerReady = true;
                requestKnowledgeRebuild();
                LegacyItemCatalogSyncService.get().trySubmitPending();
                LegacyItemCatalogSyncService.get().trySubmitCurrentSnapshot();
            }
        });
        // 物品注册表已经完成；捕获只在主菜单阶段分批进行，不在游戏世界中扫描。
        LegacyItemCatalogSyncService.get().start();
    }

    private void requestKnowledgeRebuild() {
        if (!knowledgePrepared || !workerReady || rebuildRequested || instanceRoot == null) {
            return;
        }
        rebuildRequested = true;
        LegacyWorkerBridge.get().rebuildKnowledge(instanceRoot.resolve("mods"), false)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        rebuildRequested = false;
                    }
                });
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        updateRuntimeContext(minecraft);
        if (!OPEN_ASSISTANT.isKeyDown() && !OPEN_ASSISTANT.isPressed()) {
            return;
        }
        if (!canOpen(minecraft.currentScreen)) {
            return;
        }
        LegacyTargetStore.Target target = LegacyTargetStore.get().freezeForAssistant();
        minecraft.displayGuiScreen(new LegacyAssistantScreen(minecraft.currentScreen, target));
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        LegacyManualNavigator.handleGuiOpen(event);
    }

    /**
     * 在原版“选择世界/创建世界”真正触发加载前暂存动作。目录未完成时
     * 取消这次按钮事件，并显示准备页；因此不会先进入世界再在世界线程里
     * 扫描几十万个 Tooltip。
     */
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onWorldStartAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (pendingWorldAction != null || isCatalogReady()) {
            return;
        }
        GuiScreen screen = event.getGui();
        GuiButton button = event.getButton();
        if (!isWorldStartScreen(screen, button)) {
            return;
        }
        event.setCanceled(true);
        final GuiScreen original = screen;
        final GuiButton originalButton = button;
        pendingWorldAction = new PendingWorldAction(original, originalButton);
        Minecraft.getMinecraft().displayGuiScreen(
                new LegacyCatalogPreparationScreen(original, new Runnable() {
                    @Override
                    public void run() {
                        resumeWorldAction(original, originalButton);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        pendingWorldAction = null;
                    }
                })
        );
    }

    /**
     * 主菜单上的非交互式目录进度浮窗。
     *
     * <p>浮窗只读取不可变进度快照，不触发扫描、Tooltip、数据库或 IPC，避免
     * 进度显示本身重新制造启动卡顿。</p>
     */
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onMainMenuRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiScreen screen = minecraft.currentScreen;
        if (!isPreWorldScreen(screen)) {
            return;
        }

        LegacyItemCatalogProgress.Snapshot snapshot =
                LegacyItemCatalogSyncService.get().progress();
        if (snapshot.phase() == LegacyItemCatalogProgress.Phase.NOT_STARTED) {
            return;
        }

        drawCatalogProgressOverlay(minecraft, screen, snapshot);
    }

    private void drawCatalogProgressOverlay(Minecraft minecraft, GuiScreen screen,
            LegacyItemCatalogProgress.Snapshot snapshot) {
        ScaledResolution resolution = new ScaledResolution(minecraft);
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        int availableWidth = screen == null ? screenWidth : screen.width;
        int boxWidth = Math.min(330, Math.max(220, availableWidth - 20));
        int boxHeight = 62;
        boxWidth = Math.min(boxWidth, screenWidth - 20);
        int x = screenWidth - boxWidth - 10;
        int y = screenHeight - boxHeight - 10;

        // Custom Main Menu 不会调用 GuiScreen.drawScreen 的 super 实现，因此
        // Forge DrawScreenEvent 不一定存在。RenderTick 上重新建立一个轻量的
        // GUI 投影，保证原版和自定义主菜单都能绘制同一浮窗。
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, screenWidth, screenHeight, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        try {
            Gui.drawRect(x, y, x + boxWidth, y + boxHeight, 0xCC10151F);
            Gui.drawRect(x, y, x + boxWidth, y + 2, 0xFF6FA9D4);

            String title = "物品目录 · " + catalogPhase(snapshot.phase());
            minecraft.fontRenderer.drawStringWithShadow(title, x + 10, y + 8, 0xFFE8EEF5);
            String counts = "基础物品 " + snapshot.processedVariants()
                    + " · 条目 " + snapshot.capturedEntries()
                    + " · 已写入 " + snapshot.syncedEntries();
            minecraft.fontRenderer.drawStringWithShadow(counts, x + 10, y + 24, 0xFFB9C5D0);
            String details = "注册物品 " + snapshot.registeredItems()
                    + " · 失败 " + snapshot.failedVariants();
            minecraft.fontRenderer.drawStringWithShadow(details, x + 10, y + 39, 0xFF8E9AAA);
            if (snapshot.phase() != LegacyItemCatalogProgress.Phase.COMPLETED) {
                minecraft.fontRenderer.drawStringWithShadow(
                        "目录未完成时，查询物品会按需读取当前 Tooltip",
                        x + 10, y + 52, 0xFF86C9E8
                );
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopAttrib();
        }
    }

    /**
     * GreedyCraft 使用 Custom Main Menu 替换了原版 GuiMainMenu。Forge 的绘制
     * 事件仍然会发送，但 Screen 类型已经不再是 GuiMainMenu，因此这里按
     * “尚未进入世界”识别，而不是把浮窗绑定到某个菜单类。
     */
    private boolean isPreWorldScreen(GuiScreen screen) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world != null) {
            return false;
        }
        if (screen instanceof LegacyAssistantScreen
                || screen instanceof LegacyCatalogPreparationScreen) {
            return false;
        }
        // 自定义主菜单和 FML 世界加载阶段的 Screen 类名并不稳定；以“世界尚未
        // 建立”为唯一条件，保证准备阶段浮窗不会因为换菜单模组而消失。
        return true;
    }

    private boolean isCatalogReady() {
        return LegacyItemCatalogSyncService.get().progress().phase()
                == LegacyItemCatalogProgress.Phase.COMPLETED;
    }

    private boolean isWorldStartScreen(GuiScreen screen, GuiButton button) {
        if (screen == null || button == null) {
            return false;
        }
        // 1.12.2 的 GuiWorldSelection：1=选择世界；GuiCreateWorld：0=创建世界。
        return (screen instanceof GuiWorldSelection && button.id == 1)
                || (screen instanceof GuiCreateWorld && button.id == 0);
    }

    private void resumeWorldAction(GuiScreen original, GuiButton button) {
        PendingWorldAction pending = pendingWorldAction;
        pendingWorldAction = null;
        if (pending == null || pending.screen != original || pending.button != button) {
            return;
        }
        Minecraft.getMinecraft().displayGuiScreen(original);
        try {
            java.lang.reflect.Method action = ObfuscationReflectionHelper.findMethod(
                    original.getClass(), "actionPerformed", Void.TYPE, GuiButton.class
            );
            action.setAccessible(true);
            action.invoke(original, button);
        } catch (Throwable failure) {
            ModPedia.LOGGER.warn("1.12 物品目录完成后恢复世界进入动作失败：{}",
                    failure.getClass().getSimpleName());
            Minecraft.getMinecraft().displayGuiScreen(original);
        }
    }

    private static final class PendingWorldAction {
        private final GuiScreen screen;
        private final GuiButton button;

        private PendingWorldAction(GuiScreen screen, GuiButton button) {
            this.screen = screen;
            this.button = button;
        }
    }

    private String catalogPhase(LegacyItemCatalogProgress.Phase phase) {
        switch (phase) {
            case WAITING:
                return "等待扫描";
            case SCANNING:
                return "扫描中";
            case PAUSED:
                return "已暂停";
            case WAITING_FOR_WORKER:
                return "等待 Worker 写入";
            case SYNCING:
                return "写入中";
            case COMPLETED:
                return "已完成";
            default:
                return "准备中";
        }
    }

    private void updateRuntimeContext(Minecraft minecraft) {
        if (minecraft.world == null || minecraft.player == null || minecraft.getIntegratedServer() == null) {
            if (minecraft.world == null) {
                LegacyFtbQuestRuntimeReader.clear();
                remoteWorldActive = false;
                runtimeContextKey = null;
            } else if (!remoteWorldActive) {
                // 远程服务器的权威快照由网络包提供；进入新服务器前先丢弃旧服务器快照。
                LegacyFtbQuestRuntimeReader.clearLocalContext();
                LegacyFtbQuestRuntimeReader.clearRemoteSnapshot();
                remoteWorldActive = true;
                runtimeContextKey = null;
            }
            if (minecraft.world != null && minecraft.getIntegratedServer() == null) {
                LegacyFtbQuestRuntimeReader.clearLocalContext();
                runtimeContextKey = null;
            }
            return;
        }
        remoteWorldActive = false;
        try {
            File world = minecraft.getIntegratedServer().getWorld(0)
                    .getSaveHandler().getWorldDirectory();
            String playerId = minecraft.player.getUniqueID().toString();
            String nextKey = world.toPath().toAbsolutePath().normalize().toString() + "|" + playerId;
            if (!nextKey.equals(runtimeContextKey)) {
                LegacyFtbQuestRuntimeReader.setWorldContext(
                        world.toPath(), playerId, minecraft.player.getName()
                );
                runtimeContextKey = nextKey;
            }
        } catch (Throwable ignored) {
            LegacyFtbQuestRuntimeReader.clear();
            runtimeContextKey = null;
        }
    }

    private boolean canOpen(GuiScreen screen) {
        if (screen == null) {
            return true;
        }
        // 助手已经是当前唯一的自有 Screen；按键重复触发时不能再套一层
        // LegacyAssistantScreen，否则关闭顺序和 frozen target 都会错乱。
        if (screen instanceof LegacyAssistantScreen) {
            return false;
        }
        String name = screen.getClass().getName();
        return !(name.contains("GuiOptions") || name.contains("GuiVideoSettings")
                || name.contains("GuiControls") || name.contains("GuiLanguage"));
    }
}
