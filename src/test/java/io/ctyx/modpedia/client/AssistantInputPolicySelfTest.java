package io.ctyx.modpedia.client;

/** 回归测试：K 不得在原生游戏设置页打开助手，但仍可用于其它 GUI。 */
public final class AssistantInputPolicySelfTest {
    private AssistantInputPolicySelfTest() {
    }

    public static void main(String[] args) {
        check(AssistantInputPolicy.isVanillaOptionsScreenClassName(
                        "net.minecraft.client.gui.screens.options.OptionsScreen"),
                "原生选项主页面必须拦截助手快捷键");
        check(AssistantInputPolicy.isVanillaOptionsScreenClassName(
                        "net.minecraft.client.gui.screens.options.controls.ControlsScreen"),
                "按键控制子页面必须拦截助手快捷键");
        check(AssistantInputPolicy.isVanillaOptionsScreenClassName(
                        "net.minecraft.client.gui.screens.options.VideoSettingsScreen"),
                "视频设置子页面必须拦截助手快捷键");
        check(!AssistantInputPolicy.isVanillaOptionsScreenClassName(
                        "net.minecraft.client.gui.screens.inventory.InventoryScreen"),
                "容器界面仍应允许呼出助手");
        check(!AssistantInputPolicy.isVanillaOptionsScreenClassName(
                        "com.example.mod.gui.SettingsScreen"),
                "第三方界面不应被原生设置页规则误拦截");
        check(!AssistantInputPolicy.isVanillaOptionsScreenClassName(null),
                "空界面名称应保持允许");
        System.out.println("ModPedia assistant input policy self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
