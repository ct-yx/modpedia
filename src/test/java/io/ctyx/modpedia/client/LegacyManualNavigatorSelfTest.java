package io.ctyx.modpedia.client;

/** 1.12.2 真实手册格式路径转换的无图形回归。 */
public final class LegacyManualNavigatorSelfTest {
    private LegacyManualNavigatorSelfTest() {
    }

    public static void main(String[] args) {
        check("tools.rapier".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/tconstruct/book!/zh_cn/tools/rapier.json")),
                "TConstruct 页面路径应转换为 tools.rapier");
        check("armory.armor".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/conarm/book/en_us/armory/armor.json")),
                "Construct's Armory 页面路径应转换为 armory.armor");
        check("sections.smeltery".equals(LegacyManualNavigator.mantlePageIdForTest(
                "mods/TConstruct-1.12.2-2.13.0.183.jar!/assets/tconstruct/book!/"
                        + "zh_cn/sections/smeltery.json")),
                "目标整合包中的 TConstruct 页面应转换为 sections.smeltery");
        check("sections.high_oven".equals(LegacyManualNavigator.mantlePageIdForTest(
                "mods/TinkersComplement-1.12.2-0.4.3.jar!/assets/tcomplement/book!/"
                        + "en_us/sections/high_oven.json")),
                "Mantle 扩展书籍页面应转换为 sections.high_oven");
        check("sections.alloy_furnace".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/taiga/book!/en_us/sections/alloy_furnace.json")),
                "TAIGA Mantle 页面应转换为 sections.alloy_furnace");
        check("modifiers.magic_mushroom".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/toolprogression/book!/en_us/modifiers/magic_mushroom.json")),
                "Tool Progression Mantle 页面应转换为 modifiers.magic_mushroom");
        check("modifiers.pickup".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/enderio/book!/en_us/modifiers/pickup.json")),
                "Ender IO Mantle 页面应转换为 modifiers.pickup");
        check("intro.welcome".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/enderio/eiobook!/en_us/intro/welcome.json")),
                "Ender IO 独立书籍页面应转换为 intro.welcome");
        check(LegacyManualNavigator.supports(new LegacySourceReference(
                "legacy:tconstruct:book:index.json", "index",
                "assets/tconstruct/book!/index.json", "")),
                "Mantle 书籍目录来源应至少可以打开书籍首页");
        check("".equals(LegacyManualNavigator.mantlePageIdForTest(
                "assets/patchouli_books/example/entries/start.json")),
                "Patchouli 路径不能误判为 Mantle 页面");
        check("patchouli:greedycraft_guide_book".equals(
                LegacyManualNavigator.patchouliBookIdForTest(
                        "patchouli_books/greedycraft_guide_book!/zh_cn/entries/food/food_introduction.json")),
                "实例根目录 Patchouli 书籍应回退到 patchouli 命名空间");
        check("example:guide".equals(LegacyManualNavigator.patchouliBookIdForTest(
                "mods/example.jar!/assets/example/patchouli_books/guide!/entries/start.json")),
                "JAR 内 Patchouli 书籍应保留 assets 命名空间");

        LegacySourceReference patchouli = new LegacySourceReference(
                "patchouli", "Patchouli", "assets/example/patchouli_books/example/entries/start.json", "");
        LegacySourceReference guideMe = new LegacySourceReference(
                "guideme", "GuideME", "assets/guideme/books/example.json", "");
        LegacySourceReference guideApi = new LegacySourceReference(
                "guideapi", "Guide API", "assets/bloodmagic/books/architect.xml", "");
        LegacySourceReference mantle = new LegacySourceReference(
                "tconstruct", "Tinkers' Construct", "assets/tconstruct/book!/zh_cn/tools/rapier.json", "");
        LegacySourceReference mantleComplement = new LegacySourceReference(
                "tcomplement", "Tinkers' Complement",
                "mods/TinkersComplement-1.12.2-0.4.3.jar!/assets/tcomplement/book!/"
                        + "en_us/sections/high_oven.json", "");
        LegacySourceReference taiga = new LegacySourceReference(
                "taiga", "TAIGA", "assets/taiga/book!/en_us/sections/alloy_furnace.json", "");
        LegacySourceReference toolProgression = new LegacySourceReference(
                "toolprogression", "Tool Progression",
                "assets/toolprogression/book!/en_us/modifiers/magic_mushroom.json", "");
        LegacySourceReference enderIo = new LegacySourceReference(
                "enderio", "Ender IO",
                "assets/enderio/book!/en_us/modifiers/pickup.json", "");
        LegacySourceReference enderIoBook = new LegacySourceReference(
                "enderio", "Ender IO",
                "assets/enderio/eiobook!/en_us/intro/welcome.json", "");
        LegacySourceReference forestry = new LegacySourceReference(
                "forestry", "Forestry",
                "assets/forestry/manual!/en_us/core/carpenter.json", "");
        LegacySourceReference research = new LegacySourceReference(
                "thaumcraft", "Thaumcraft",
                "assets/thaumcraft/research/alchemy.json", "");
        LegacySourceReference logisticsPipes = new LegacySourceReference(
                "logisticspipes", "Logistics Pipes",
                "assets/logisticspipes/book!/en_us/dev_zero_guides/index.md", "");
        LegacySourceReference plainWiki = new LegacySourceReference(
                "wiki", "Plain Wiki", "assets/example/wiki/en_us/start.md", "");
        check(LegacyManualNavigator.supports(patchouli), "Patchouli 来源应支持跳转");
        check(LegacyManualNavigator.supports(guideApi), "实际 Guide API 来源应支持跳转");
        check(LegacyManualNavigator.supports(mantle), "Mantle/TConstruct 来源应支持跳转");
        check(LegacyManualNavigator.supports(mantleComplement),
                "Mantle 扩展书籍来源应支持跳转");
        check(LegacyManualNavigator.supports(taiga),
                "TAIGA Mantle 书籍来源应支持跳转");
        check(LegacyManualNavigator.supports(toolProgression),
                "Tool Progression Mantle 书籍来源应支持跳转");
        check(LegacyManualNavigator.supports(enderIo),
                "Ender IO Mantle 书籍来源应支持跳转");
        check(LegacyManualNavigator.supports(enderIoBook),
                "Ender IO 独立书籍来源应支持跳转");
        check(LegacyManualNavigator.supports(forestry),
                "Forestry 手册来源应支持跳转");
        check(LegacyManualNavigator.supports(research),
                "Thaumcraft 研究来源应支持跳转到研究浏览器");
        check(LegacyManualNavigator.supports(logisticsPipes),
                "Logistics Pipes 书籍来源应支持跳转");
        check(!LegacyManualNavigator.supports(plainWiki),
                "没有游戏内书籍界面的普通 Wiki 不应伪造跳转");
        check("core/carpenter".equals(LegacyManualNavigator.forestryPageForTest(
                "assets/forestry/manual!/en_us/core/carpenter.json")),
                "Forestry 页面路径应转换为 core/carpenter");
        check("/dev_zero_guides/index.md".equals(
                LegacyManualNavigator.logisticsPipesPageForTest(
                        "assets/logisticspipes/book!/en_us/dev_zero_guides/index.md")),
                "Logistics Pipes 页面路径应保留绝对页面路径");
        check(!LegacyManualNavigator.supports(guideMe),
                "1.12.2 不应把 GuideME 识别为支持的手册跳转");
        System.out.println("LegacyManualNavigatorSelfTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
