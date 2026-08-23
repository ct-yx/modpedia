package io.ctyx.modpedia.knowledge;

/** 1.12.2 非扁平化物品 ID 的纯 Java 回归测试。 */
public final class LegacyItemIdentitySelfTest {
    private LegacyItemIdentitySelfTest() {
    }

    public static void main(String[] args) {
        LegacyItemIdentity wool = LegacyItemIdentity.parse(
                "[[item:minecraft:wool|红色羊毛|meta=14]]"
        );
        assertEquals("minecraft:wool", wool.getItemId(), "item id");
        assertEquals(14, wool.getMetadata(), "metadata");
        assertEquals("minecraft:wool@14", wool.canonicalKey(), "canonical key");

        LegacyItemIdentity compact = LegacyItemIdentity.parse("example:machine#3");
        assertEquals(3, compact.getMetadata(), "hash metadata");
        assertEquals("example:machine@3", compact.canonicalKey(), "hash canonical key");

        LegacyItemIdentity invalidMetadata = LegacyItemIdentity.parse("example:machine@-");
        assertEquals(LegacyItemIdentity.UNSPECIFIED_METADATA, invalidMetadata.getMetadata(),
                "invalid metadata is unspecified");

        LegacyItemIdentity withNbt = LegacyItemIdentity.of("example:machine", 0, "{Mode:1b}");
        assertTrue(withNbt.canonicalKey().startsWith("example:machine@0#"), "NBT fingerprint");
        System.out.println("LegacyItemIdentitySelfTest: OK");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
