package io.ctyx.modpedia.client;

/** 回答正文中的可选物品/标签令牌。 */
public record ItemReference(
        String kind,
        String id,
        String displayText
) {
    public ItemReference {
        kind = kind == null || kind.isBlank() ? "item" : kind;
        id = id == null ? "" : id.strip();
        displayText = displayText == null ? "" : displayText.strip();
    }
}
