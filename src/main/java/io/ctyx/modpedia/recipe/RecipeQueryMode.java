package io.ctyx.modpedia.recipe;

import java.util.Locale;

/** 配方查询阶段；OTHER 先列出方式，DETAIL 再读取选中的方式。 */
public enum RecipeQueryMode {
    WORKBENCH,
    FURNACE,
    OTHER,
    DETAIL;

    public static RecipeQueryMode parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "workbench", "crafting", "craft", "工作台", "工作台合成" -> WORKBENCH;
            case "furnace", "smelting", "smelt", "熔炉", "烧炼", "熔炉烧炼" -> FURNACE;
            case "detail", "recipe", "具体", "详情" -> DETAIL;
            case "other", "options", "methods", "其它", "其他", "处理方式" -> OTHER;
            default -> OTHER;
        };
    }
}
