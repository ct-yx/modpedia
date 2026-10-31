package io.ctyx.modpedia.api;

/**
 * 当前客户端为一个已确认物品读取到的临时 Tooltip。
 *
 * <p>它只存在于一次 AI 请求的内存和 IPC 载荷中，不属于静态知识库；
 * Worker 缺少运行时能力时会回退到 item_catalog。</p>
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

    /** 只有世界已就绪且有可用文本时，才交给 Worker/模型。 */
    public boolean usable() {
        return worldReady && (!displayName.isBlank() || !tooltipMarkdown.isBlank());
    }
}
