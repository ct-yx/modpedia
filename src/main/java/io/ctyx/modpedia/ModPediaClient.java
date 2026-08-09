package io.ctyx.modpedia;

import com.mojang.blaze3d.platform.InputConstants;
import io.ctyx.modpedia.ai.AiAssistantSession;
import io.ctyx.modpedia.client.AssistantScreen;
import io.ctyx.modpedia.client.AssistantSession;
import io.ctyx.modpedia.client.ManualSourceNavigator;
import io.ctyx.modpedia.client.MockAssistantSession;
import io.ctyx.modpedia.client.FloatingAssistantWindow;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

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
            : new AiAssistantSession();

    public ModPediaClient(IEventBus modEventBus, ModContainer modContainer) {
        ModPedia.LOGGER.info("Loading ModPedia client components");
        modEventBus.addListener(ModPediaClient::onRegisterKeyMappings);
        modEventBus.addListener(ModPediaClient::onClientSetup);
        NeoForge.EVENT_BUS.register(ModPediaClientEvents.class);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_ASSISTANT);
        event.register(REBUILD_KNOWLEDGE);
    }

    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(KnowledgeUpdateService::startAsync);
    }
}

/** 客户端游戏总线事件，处理手动重建按键。 */
final class ModPediaClientEvents {
    private ModPediaClientEvents() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
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

        String messageKey = KnowledgeUpdateService.rebuildAsync()
                ? "message.modpedia.rebuild_started"
                : "message.modpedia.rebuild_busy";
        minecraft.gui.setOverlayMessage(Component.translatable(messageKey), false);
    }
}
