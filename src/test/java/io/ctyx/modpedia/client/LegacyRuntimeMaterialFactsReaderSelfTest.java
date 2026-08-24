package io.ctyx.modpedia.client;

/** 验证 1.12 工具和护甲材料部件的统一名称映射。 */
public final class LegacyRuntimeMaterialFactsReaderSelfTest {
    private LegacyRuntimeMaterialFactsReaderSelfTest() {
    }

    public static void main(String[] args) {
        check("head".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("blade")),
                "工具刀刃应映射为 head");
        check("handle".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("tool rod")),
                "工具杆应映射为 handle");
        check("extra".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("cross_guard")),
                "十字柄应映射为 extra");
        check("core".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("armorcore")),
                "护甲基底应映射为 core");
        check("plates".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("armorplate")),
                "护甲板应映射为 plates");
        check("trim".equals(LegacyRuntimeMaterialFactsReader.canonicalPart("armortrim")),
                "护甲夹板应映射为 trim");
        check("iron".equals(LegacyRuntimeMaterialFactsReader.normalizeMaterial("iron_core")),
                "材料部件后缀不应阻止材料匹配");
        System.out.println("LegacyRuntimeMaterialFactsReaderSelfTest: OK");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
