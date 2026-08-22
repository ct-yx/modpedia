package io.ctyx.modpedia;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

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

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        // 后续迁移阶段在这里接入客户端适配层；当前初始化不创建 run 或测试目录。
    }
}
