package io.ctyx.modpedia;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * ModPedia 的公共入口。
 *
 * <p>第一阶段只保留最小可加载骨架，后续功能按 ai、knowledge、search 和 client 分层接入。</p>
 */
@Mod(ModPedia.MOD_ID)
public final class ModPedia {
    public static final String MOD_ID = "modpedia";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ModPedia(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Loading ModPedia");
    }
}
