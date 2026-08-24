package io.ctyx.modpedia.client;

import java.util.List;

/** 将 1.12.2 ItemStack Tooltip 转为不会依赖 Minecraft 类型的 Markdown 文本。 */
public final class LegacyItemTooltipFormatter {
    private LegacyItemTooltipFormatter() {
    }

    /**
     * Tooltip 的第一行是物品名称，名称已经单独写入 item_catalog；其余行保留
     * 原顺序并转换成 Markdown 无序列表。Tooltip 为空时返回空字符串，而不是把
     * 物品 ID 伪装成简介。
     */
    public static String toMarkdown(List<String> tooltipLines) {
        if (tooltipLines == null || tooltipLines.size() <= 1) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 1; index < tooltipLines.size(); index++) {
            String line = clean(tooltipLines.get(index));
            if (line.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append("- ").append(line);
        }
        return result.toString();
    }

    static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        boolean formattingCode = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (formattingCode) {
                formattingCode = false;
                continue;
            }
            if (character == '\u00a7') {
                formattingCode = true;
                continue;
            }
            if (character == '\r' || character == '\n') {
                result.append(' ');
            } else {
                result.append(character);
            }
        }
        return result.toString().trim();
    }
}
