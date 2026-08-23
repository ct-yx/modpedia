package io.ctyx.modpedia.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 1.12.2 的物品身份：注册名 + metadata + 可选 NBT 指纹。
 *
 * <p>metadata 为 -1 表示查询文本没有指定变体；真实 ItemStack 应始终传入实际
 * metadata。这样不会把旧版的羊毛、染色玻璃等变体错误合并。</p>
 */
public final class LegacyItemIdentity {
    public static final int UNSPECIFIED_METADATA = -1;

    private final String itemId;
    private final int metadata;
    private final String nbtFingerprint;

    private LegacyItemIdentity(String itemId, int metadata, String nbtFingerprint) {
        this.itemId = normalizeItemId(itemId);
        this.metadata = metadata < UNSPECIFIED_METADATA ? UNSPECIFIED_METADATA : metadata;
        this.nbtFingerprint = nbtFingerprint == null ? "" : nbtFingerprint.trim();
    }

    public static LegacyItemIdentity of(String itemId, int metadata, String nbt) {
        return new LegacyItemIdentity(itemId, metadata, fingerprint(nbt));
    }

    public static LegacyItemIdentity parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("物品 ID 为空");
        }
        String text = value.trim();
        if (text.startsWith("[[item:") && text.endsWith("]]")) {
            text = text.substring("[[item:".length(), text.length() - 2);
        }
        String[] parts = text.split("\\|");
        String idPart = parts[0].trim();
        int metadata = UNSPECIFIED_METADATA;
        String nbt = "";
        int marker = Math.max(idPart.lastIndexOf('@'), idPart.lastIndexOf('#'));
        if (marker > 0 && marker + 1 < idPart.length()) {
            String suffix = idPart.substring(marker + 1);
            if (isInteger(suffix)) {
                metadata = Integer.parseInt(suffix);
                idPart = idPart.substring(0, marker);
            }
        }
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.startsWith("meta=") || part.startsWith("metadata=")) {
                String number = part.substring(part.indexOf('=') + 1).trim();
                if (isInteger(number)) {
                    metadata = Integer.parseInt(number);
                }
            } else if (part.startsWith("nbt=")) {
                nbt = part.substring("nbt=".length());
            }
        }
        return new LegacyItemIdentity(idPart, metadata, fingerprint(nbt));
    }

    public String getItemId() {
        return itemId;
    }

    public int getMetadata() {
        return metadata;
    }

    public String getNbtFingerprint() {
        return nbtFingerprint;
    }

    public String canonicalKey() {
        StringBuilder key = new StringBuilder(itemId);
        if (metadata != UNSPECIFIED_METADATA) {
            key.append('@').append(metadata);
        }
        if (!nbtFingerprint.isEmpty()) {
            key.append('#').append(nbtFingerprint);
        }
        return key.toString();
    }

    public String displayToken(String displayName) {
        StringBuilder token = new StringBuilder("[[item:").append(itemId);
        if (displayName != null && !displayName.trim().isEmpty()) {
            token.append('|').append(displayName.trim());
        }
        if (metadata != UNSPECIFIED_METADATA) {
            token.append("|meta=").append(metadata);
        }
        return token.append("]]" ).toString();
    }

    private static String normalizeItemId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.indexOf(':') < 0 && !normalized.isEmpty()) {
            return "minecraft:" + normalized;
        }
        return normalized;
    }

    private static String fingerprint(String nbt) {
        if (nbt == null || nbt.trim().isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(nbt.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JRE 缺少 SHA-256", exception);
        }
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((index == 0 && character == '-') || (character >= '0' && character <= '9')) {
                continue;
            }
            return false;
        }
        return true;
    }
}
