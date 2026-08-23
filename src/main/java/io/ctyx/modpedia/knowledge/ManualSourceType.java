package io.ctyx.modpedia.knowledge;

/** 真实 1.12.2 手册格式；不要根据目录名把所有 guide 当成同一种格式。 */
public enum ManualSourceType {
    PATCHOULI_1_12_JSON("patchouli_1_12_json"),
    MANTLE_BOOK_1_12("mantle_book_1_12"),
    FORESTRY_MANUAL_1_12("forestry_manual_1_12"),
    ENDERIO_BOOK_1_12("enderio_book_1_12"),
    GUIDE_API_1_12("guide_api_1_12"),
    THAUMCRAFT_RESEARCH_1_12("thaumcraft_research_1_12"),
    CUSTOM_TEXT_1_12("custom_text_1_12"),
    WIKI_ASSET_TEXT_1_12("wiki_asset_text_1_12");

    private final String id;

    ManualSourceType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
