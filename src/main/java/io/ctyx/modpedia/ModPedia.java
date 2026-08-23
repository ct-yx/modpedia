package io.ctyx.modpedia;

import io.ctyx.modpedia.network.LegacyFtbQuestSnapshotMessage;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 1.12.2 双运行时兼容初始化入口。
 *
 * <p>这里只保留 Forge 1.12.2 公共 API；目标加载器 0.3+ 的差异放在后续适配层，
 * 不在初始化阶段引入专用类。</p>
 */
@Mod(
        modid = ModPedia.MOD_ID,
        name = ModPedia.MOD_NAME,
        version = ModPedia.VERSION,
        acceptableRemoteVersions = "*"
)
public final class ModPedia {
    public static final String MOD_ID = "modpedia";
    public static final String MOD_NAME = "ModPedia · 模组百科";
    public static final String VERSION = "1.12.2-0.1.0";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE
            .newSimpleChannel(MOD_ID + "_network");

    @SidedProxy(
            clientSide = "io.ctyx.modpedia.client.ClientProxy",
            serverSide = "io.ctyx.modpedia.CommonProxy"
    )
    private static CommonProxy proxy;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        NETWORK.registerMessage(
                LegacyFtbQuestSnapshotMessage.Handler.class,
                LegacyFtbQuestSnapshotMessage.class,
                0,
                Side.CLIENT
        );
        proxy.preInitialize(event);
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        proxy.initialize(event);
    }

    @Mod.EventHandler
    public void postInitialize(FMLPostInitializationEvent event) {
        proxy.postInitialize(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }
}
