package io.ctyx.modpedia;

import com.mojang.blaze3d.platform.InputConstants;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** 客户端入口，负责启动知识库构建和注册手动重建入口。 */
@EventBusSubscriber(modid = ModPedia.MOD_ID, value = Dist.CLIENT)
public final class ModPediaClient {
    static final KeyMapping REBUILD_KNOWLEDGE = new KeyMapping(
            "key.modpedia.rebuild_knowledge",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F9,
            "key.categories.modpedia"
    );

    public ModPediaClient(ModContainer modContainer) {
        ModPedia.LOGGER.info("Loading ModPedia client components");
        NeoForge.EVENT_BUS.register(ModPediaClientEvents.class);
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(REBUILD_KNOWLEDGE);
    }

    @SubscribeEvent
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
        if (!ModPediaClient.REBUILD_KNOWLEDGE.consumeClick()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String messageKey = KnowledgeUpdateService.rebuildAsync()
                ? "message.modpedia.rebuild_started"
                : "message.modpedia.rebuild_busy";
        minecraft.gui.setOverlayMessage(Component.translatable(messageKey), false);
    }
}
