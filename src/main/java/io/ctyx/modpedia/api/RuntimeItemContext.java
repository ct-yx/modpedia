package io.ctyx.modpedia.api;

/**
 * 当前客户端为一个已确认物品读取到的临时 Tooltip。
 *
 * <p>它首先存在于一次 AI 请求的内存和 IPC 载荷中；Worker 在成功读取后可将
 * Tooltip 缓存回 {@code knowledge.db} 的 {@code item_catalog}，客户端不会直接
 * 访问数据库。</p>
 */
public record RuntimeItemContext(
        String itemId,
        String language,
        String displayName,
        String tooltipMarkdown,
        boolean worldReady,
        long capturedAt
) {
    public RuntimeItemContext {
        itemId = itemId == null ? "" : itemId.strip().toLowerCase(java.util.Locale.ROOT);
        language = language == null || language.isBlank()
                ? "neutral"
                : language.strip().toLowerCase(java.util.Locale.ROOT);
        displayName = displayName == null ? "" : displayName.strip();
        tooltipMarkdown = tooltipMarkdown == null ? "" : tooltipMarkdown.strip();
        capturedAt = Math.max(0L, capturedAt);
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
    }

    /** 只有客户端确认世界已就绪且返回了可用文本时，才可交给模型。 */
    public boolean usable() {
        return worldReady && (!displayName.isBlank() || !tooltipMarkdown.isBlank());
    }
}
