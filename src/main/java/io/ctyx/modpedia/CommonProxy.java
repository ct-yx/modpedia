package io.ctyx.modpedia;

import io.ctyx.modpedia.knowledge.LegacyFtbQuestEventBridge;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

/** Dedicated Server 使用的公共生命周期和可选联动适配层。 */
public class CommonProxy {
    private final LegacyFtbQuestEventBridge ftbQuestEventBridge =
            new LegacyFtbQuestEventBridge();

    public void preInitialize(FMLPreInitializationEvent event) {
        ftbQuestEventBridge.register();
    }

    public void initialize(FMLInitializationEvent event) {
    }

    public void postInitialize(FMLPostInitializationEvent event) {
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        LegacyFtbQuestRuntimeReader.clearServerSnapshots();
    }
}
