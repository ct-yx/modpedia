package io.ctyx.modpedia.knowledge;

/** 1.12.2 物品目录的 IPC 数据，不依赖 Minecraft 类。 */
public final class LegacyItemCatalogEntry {
    private final String itemId;
    private final String language;
    private final String displayName;
    private final String descriptionMarkdown;
    private final String sourceMod;
    private final String fingerprint;

    public LegacyItemCatalogEntry(
            String itemId,
            String language,
            String displayName,
            String descriptionMarkdown,
            String sourceMod,
            String fingerprint
    ) {
        this.itemId = value(itemId);
        this.language = value(language);
        this.displayName = value(displayName);
        this.descriptionMarkdown = descriptionMarkdown == null ? "" : descriptionMarkdown;
        this.sourceMod = value(sourceMod);
        this.fingerprint = value(fingerprint);
    }

    public String getItemId() { return itemId; }
    public String getLanguage() { return language; }
    public String getDisplayName() { return displayName; }
    public String getDescriptionMarkdown() { return descriptionMarkdown; }
    public String getSourceMod() { return sourceMod; }
    public String getFingerprint() { return fingerprint; }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
