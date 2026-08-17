package io.ctyx.modpedia.search;

/**
 * 当前游戏语言下的注册物品资料。
 *
 * <p>物品目录是统一 knowledge.db 中独立于手册 FTS 的事实表；完整 Tooltip
 * 使用 Markdown 保存，供仅搜索模式和模型上下文直接读取。</p>
 */
public record ItemCatalogEntry(
        String itemId,
        String language,
        String displayName,
        String descriptionMarkdown,
        String sourceMod,
        String fingerprint
) {
    public ItemCatalogEntry {
        itemId = itemId == null ? "" : itemId.strip().toLowerCase(java.util.Locale.ROOT);
        language = language == null || language.isBlank() ? "neutral" : language.strip().toLowerCase(java.util.Locale.ROOT);
        displayName = displayName == null ? "" : displayName.strip();
        descriptionMarkdown = descriptionMarkdown == null ? "" : descriptionMarkdown.strip();
        sourceMod = sourceMod == null ? "" : sourceMod.strip();
        fingerprint = fingerprint == null ? "" : fingerprint.strip();
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        if (fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint 不能为空");
        }
    }
}
