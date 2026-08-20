package io.ctyx.modpedia.client;

import net.minecraft.client.gui.screens.Screen;

/** 助手快捷键的界面范围规则；原生游戏设置页不应被助手覆盖。 */
public final class AssistantInputPolicy {
    private static final String VANILLA_OPTIONS_PACKAGE =
            "net.minecraft.client.gui.screens.options.";

    private AssistantInputPolicy() {
    }

    /**
     * 只拦截 Minecraft 原生选项页及其子页面，保留 JEI、FTBQ、容器和其它第三方
     * 页面上的 K 呼出能力。
     */
    public static boolean blocksAssistant(Screen screen) {
        return screen != null && isVanillaOptionsScreenClassName(screen.getClass().getName());
    }

    static boolean isVanillaOptionsScreenClassName(String className) {
        return className != null && className.startsWith(VANILLA_OPTIONS_PACKAGE);
    }
}
