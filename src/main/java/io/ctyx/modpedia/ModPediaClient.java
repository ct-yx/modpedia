package io.ctyx.modpedia;

import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 客户端入口，负责启动客户端知识库构建。 */
@Mod(value = ModPedia.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ModPedia.MOD_ID, value = Dist.CLIENT)
public final class ModPediaClient {
    public ModPediaClient(ModContainer modContainer) {
        ModPedia.LOGGER.info("Loading ModPedia client components");
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(KnowledgeUpdateService::startAsync);
    }
}
