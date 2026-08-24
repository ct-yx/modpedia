package io.ctyx.modpedia.client;

import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 验证 1.12.2 客户端基础物品名称缓存的指纹、语言和完整性校验。 */
public final class LegacyItemCatalogCacheSelfTest {
    private LegacyItemCatalogCacheSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-112-item-cache-");
        try {
            List<String> ids = Arrays.asList("example:two", "example:one");
            String fingerprint = LegacyItemCatalogCache.registryFingerprint("zh_cn", ids);
            check(fingerprint.equals(LegacyItemCatalogCache.registryFingerprint(
                    "zh_cn", Arrays.asList("example:one", "example:two")
            )), "注册表指纹必须与枚举顺序无关");
            check(!fingerprint.equals(LegacyItemCatalogCache.registryFingerprint("en_us", ids)),
                    "语言切换必须产生不同指纹");

            LegacyItemCatalogCache.write(root, "zh_cn", fingerprint, Arrays.asList(
                    entry("example:two", "二号物品"),
                    entry("example:one", "一号物品")
            ));
            LegacyItemCatalogCache.CachedCatalog cached = LegacyItemCatalogCache.read(
                    root, "zh_cn", fingerprint, 2
            );
            check(cached != null, "完整缓存应可读取");
            check(cached.entries().get("example:one").getDisplayName().equals("一号物品"),
                    "缓存应保留显示名称");
            Files.write(root.resolve("item-catalog-names.jsonl"),
                    "{\"item_id\":\"example:changed\"}\n".getBytes(StandardCharsets.UTF_8));
            check(LegacyItemCatalogCache.read(root, "zh_cn", fingerprint, 2) == null,
                    "条目文件被替换但状态未更新时不得命中缓存");

            LegacyItemCatalogCache.write(root, "zh_cn", fingerprint, Arrays.asList(
                    entry("example:two", "二号物品"),
                    entry("example:one", "一号物品")
            ));
            check(LegacyItemCatalogCache.read(root, "en_us", fingerprint, 2) == null,
                    "语言不匹配时不得命中缓存");
            check(LegacyItemCatalogCache.read(root, "zh_cn", "wrong", 2) == null,
                    "指纹不匹配时不得命中缓存");
            check(LegacyItemCatalogCache.read(root, "zh_cn", fingerprint, 3) == null,
                    "条目数量不匹配时不得命中缓存");

            Files.write(LegacyItemCatalogCache.statePath(root),
                    ("{\"format\":2,\"language\":\"zh_cn\","
                            + "\"registry_fingerprint\":\"" + fingerprint
                            + "\",\"item_count\":2}").getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("item-catalog-names.jsonl"),
                    "{\"item_id\":\"example:one\"}\n".getBytes(StandardCharsets.UTF_8));
            check(LegacyItemCatalogCache.read(root, "zh_cn", fingerprint, 2) == null,
                    "不完整条目文件不得命中缓存");
            System.out.println("LegacyItemCatalogCacheSelfTest: OK");
        } finally {
            deleteTree(root);
        }
    }

    private static LegacyItemCatalogEntry entry(String id, String name) {
        return new LegacyItemCatalogEntry(id, "zh_cn", name, "", "example", id + "-v1");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
