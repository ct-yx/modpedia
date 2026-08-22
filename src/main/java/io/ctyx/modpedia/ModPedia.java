package io.ctyx.modpedia;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;

/**
 * ModPedia 的公共入口。
 *
 * <p>第一阶段只保留最小可加载骨架，后续功能按 ai、knowledge、search 和 client 分层接入。</p>
 */
@Mod(ModPedia.MOD_ID)
public final class ModPedia {
    public static final String MOD_ID = "modpedia";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 1.20.1 通过 FMLJavaModLoadingContext 构造模组实例；双参数的
     * IEventBus/ModContainer 入口属于其他加载器版本，Forge 不会匹配它。
     */
    public ModPedia(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModContainer modContainer = context.getContainer();
        LOGGER.info("Loading ModPedia");
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> new ModPediaClient(modEventBus, modContainer));
    }
}
