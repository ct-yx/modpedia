package io.ctyx.modpedia.client;

import io.ctyx.modpedia.CommonProxy;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import io.ctyx.modpedia.knowledge.ManualCatalogBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
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
            }
        });
        // 物品注册表已经完成；捕获在 ClientTick 中分批进行，不阻塞本次事件。
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

    private void updateRuntimeContext(Minecraft minecraft) {
        if (minecraft.world == null || minecraft.player == null || minecraft.getIntegratedServer() == null) {
            if (minecraft.world == null) {
                LegacyFtbQuestRuntimeReader.clear();
                remoteWorldActive = false;
            } else if (!remoteWorldActive) {
                // 远程服务器的权威快照由网络包提供；进入新服务器前先丢弃旧服务器快照。
                LegacyFtbQuestRuntimeReader.clearLocalContext();
                LegacyFtbQuestRuntimeReader.clearRemoteSnapshot();
                remoteWorldActive = true;
            }
            if (minecraft.world != null && minecraft.getIntegratedServer() == null) {
                LegacyFtbQuestRuntimeReader.clearLocalContext();
            }
            return;
        }
        remoteWorldActive = false;
        try {
            File world = minecraft.getIntegratedServer().getWorld(0)
                    .getSaveHandler().getWorldDirectory();
            LegacyFtbQuestRuntimeReader.setWorldContext(
                    world.toPath(), minecraft.player.getUniqueID().toString(), minecraft.player.getName()
            );
        } catch (Throwable ignored) {
            LegacyFtbQuestRuntimeReader.clear();
        }
    }

    private boolean canOpen(GuiScreen screen) {
        if (screen == null || screen instanceof LegacyAssistantScreen) {
            return true;
        }
        String name = screen.getClass().getName();
        return !(name.contains("GuiOptions") || name.contains("GuiVideoSettings")
                || name.contains("GuiControls") || name.contains("GuiLanguage"));
    }
}
