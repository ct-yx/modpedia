package io.ctyx.modpedia;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * 客户端入口。
 *
 * <p>客户端界面和快捷键从这里继续扩展，避免公共入口直接引用客户端类。</p>
 */
@Mod(value = ModPedia.MOD_ID, dist = Dist.CLIENT)
public final class ModPediaClient {
    public ModPediaClient(ModContainer modContainer) {
        ModPedia.LOGGER.info("Loading ModPedia client components");
    }
}
