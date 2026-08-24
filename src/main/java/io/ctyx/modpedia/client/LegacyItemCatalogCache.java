package io.ctyx.modpedia.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.12.2 客户端的轻量物品名称缓存。
 *
 * <p>Worker 的 SQLite 是检索事实源，但命中缓存后客户端仍需要名称来渲染
 * Markdown 里的物品令牌。因此这里只保存名称和指纹，不保存 ItemStack、Tooltip
 * 或任何 Minecraft 运行时对象。状态文件最后写入，保证崩溃时不会把半成品标记
 * 成可复用缓存。</p>
 */
public final class LegacyItemCatalogCache {
    private static final int FORMAT_VERSION = 2;
    private static final String STATE_FILE = "item-catalog-state.json";
    private static final String ENTRIES_FILE = "item-catalog-names.jsonl";

    private LegacyItemCatalogCache() {
    }

    public static String registryFingerprint(String language, Collection<String> registryItems) {
        List<String> values = new ArrayList<String>();
        if (registryItems != null) {
            for (String value : registryItems) {
                if (value != null && !value.trim().isEmpty()) {
                    values.add(value.trim());
                }
            }
        }
        Collections.sort(values);
        StringBuilder input = new StringBuilder(value(language));
        input.append('\n').append(values.size());
        for (String item : values) {
            input.append('\n').append(item);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[digest.length * 2];
            int offset = 0;
            for (byte item : digest) {
                int unsigned = item & 0xff;
                result[offset++] = hex[unsigned >>> 4];
                result[offset++] = hex[unsigned & 0x0f];
            }
            return new String(result);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public static CachedCatalog read(
            Path root,
            String language,
            String registryFingerprint,
            int expectedCount
    ) {
        if (root == null || expectedCount <= 0) {
            return null;
        }
        Path statePath = root.resolve(STATE_FILE);
        Path entriesPath = root.resolve(ENTRIES_FILE);
        try {
            if (!Files.isRegularFile(statePath) || !Files.isRegularFile(entriesPath)) {
                return null;
            }
            JsonObject state = new JsonParser().parse(
                    new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8)
            ).getAsJsonObject();
            if (state.get("format") == null
                    || state.get("format").getAsInt() != FORMAT_VERSION
                    || !value(language).equals(state.get("language").getAsString())
                    || !value(registryFingerprint).equals(
                            state.get("registry_fingerprint").getAsString())
                    || expectedCount != state.get("item_count").getAsInt()) {
                return null;
            }
            String storedEntriesHash = string(state, "entries_sha256");
            if (storedEntriesHash.isEmpty()
                    || !storedEntriesHash.equals(fileSha256(entriesPath))) {
                return null;
            }

            Map<String, LegacyItemCatalogEntry> entries = new LinkedHashMap<String, LegacyItemCatalogEntry>();
            BufferedReader input = Files.newBufferedReader(entriesPath, StandardCharsets.UTF_8);
            try {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    JsonObject item = new JsonParser().parse(line).getAsJsonObject();
                    LegacyItemCatalogEntry entry = new LegacyItemCatalogEntry(
                            string(item, "item_id"),
                            string(item, "language"),
                            string(item, "display_name"),
                            string(item, "description_markdown"),
                            string(item, "source_mod"),
                            string(item, "fingerprint")
                    );
                    if (!value(language).equals(entry.getLanguage())
                            || entry.getItemId().isEmpty()) {
                        return null;
                    }
                    entries.put(entry.getItemId(), entry);
                }
            } finally {
                input.close();
            }
            if (entries.size() != expectedCount) {
                return null;
            }
            return new CachedCatalog(value(language), value(registryFingerprint),
                    expectedCount, entries);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void write(
            Path root,
            String language,
            String registryFingerprint,
            Collection<LegacyItemCatalogEntry> values
    ) throws IOException {
        if (root == null) {
            throw new IOException("物品目录缓存目录为空");
        }
        List<LegacyItemCatalogEntry> entries = new ArrayList<LegacyItemCatalogEntry>();
        if (values != null) {
            entries.addAll(values);
        }
        Collections.sort(entries, Comparator.comparing(LegacyItemCatalogEntry::getItemId));
        Files.createDirectories(root);
        Path entriesTemporary = root.resolve(ENTRIES_FILE + ".tmp");
        Path stateTemporary = root.resolve(STATE_FILE + ".tmp");
        try {
            BufferedWriter output = Files.newBufferedWriter(
                    entriesTemporary, StandardCharsets.UTF_8
            );
            try {
                for (LegacyItemCatalogEntry entry : entries) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item_id", entry.getItemId());
                    item.addProperty("language", entry.getLanguage());
                    item.addProperty("display_name", entry.getDisplayName());
                    item.addProperty("description_markdown", entry.getDescriptionMarkdown());
                    item.addProperty("source_mod", entry.getSourceMod());
                    item.addProperty("fingerprint", entry.getFingerprint());
                    output.write(item.toString());
                    output.newLine();
                }
            } finally {
                output.close();
            }
            move(entriesTemporary, root.resolve(ENTRIES_FILE));

            JsonObject state = new JsonObject();
            state.addProperty("format", FORMAT_VERSION);
            state.addProperty("language", value(language));
            state.addProperty("registry_fingerprint", value(registryFingerprint));
            state.addProperty("item_count", entries.size());
            state.addProperty("entries_sha256", fileSha256(root.resolve(ENTRIES_FILE)));
            state.addProperty("updated_at", System.currentTimeMillis());
            Files.write(stateTemporary, state.toString().getBytes(StandardCharsets.UTF_8));
            // 状态文件最后替换；只有它存在且与条目文件数量相符时才会命中缓存。
            move(stateTemporary, root.resolve(STATE_FILE));
        } finally {
            Files.deleteIfExists(entriesTemporary);
            Files.deleteIfExists(stateTemporary);
        }
    }

    public static Path statePath(Path root) {
        return root.resolve(STATE_FILE);
    }

    public static final class CachedCatalog {
        private final String language;
        private final String registryFingerprint;
        private final int itemCount;
        private final Map<String, LegacyItemCatalogEntry> entries;

        private CachedCatalog(String language, String registryFingerprint, int itemCount,
                Map<String, LegacyItemCatalogEntry> entries) {
            this.language = language;
            this.registryFingerprint = registryFingerprint;
            this.itemCount = itemCount;
            this.entries = new LinkedHashMap<String, LegacyItemCatalogEntry>(entries);
        }

        public String language() { return language; }
        public String registryFingerprint() { return registryFingerprint; }
        public int itemCount() { return itemCount; }
        public Map<String, LegacyItemCatalogEntry> entries() {
            return Collections.unmodifiableMap(entries);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fileSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.io.InputStream input = Files.newInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            } finally {
                input.close();
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 不可用", exception);
        }
    }

    private static String hex(byte[] bytes) {
        char[] symbols = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        int offset = 0;
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            result[offset++] = symbols[unsigned >>> 4];
            result[offset++] = symbols[unsigned & 0x0f];
        }
        return new String(result);
    }
}
