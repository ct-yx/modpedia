package io.ctyx.modpedia.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 将同一机器的 basic/advanced 或 tier/mk 等等级合并成一个名称。 */
public final class RecipeMachineNormalizer {
    private static final Pattern TIER_WORD = Pattern.compile(
            "(?i)(^|[ _:-])(basic|advanced|elite|ultimate|primitive|basic|tier|mk)[ _:-]*[0-9ivx]*($|[ _:-])"
    );
    private static final Pattern CJK_TIER_PREFIX = Pattern.compile(
            "(?i)(^|[ _:-])(基础|高级|精英|终极|原始|初级|低级|中级|顶级)(?=[\\p{L}\\p{N}])"
    );
    private static final Pattern TIER_NUMBER = Pattern.compile(
            "(?i)(^|[ _:-])(tier|mk)[ _:-]*[0-9ivx]+($|[ _:-])"
    );
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private RecipeMachineNormalizer() {
    }

    public static List<String> unique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String value : values) {
            String display = value == null ? "" : value.strip();
            if (display.isBlank()) {
                continue;
            }
            result.putIfAbsent(key(display), display);
        }
        return List.copyOf(result.values());
    }

    static String key(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        // 先把括号、斜杠等包裹等级的标点转换为空格，使
        // "Enrichment Chamber (Basic)" 与 "Advanced Enrichment Chamber"
        // 进入同一个归一化键。
        normalized = SEPARATOR.matcher(normalized).replaceAll(" ");
        normalized = TIER_NUMBER.matcher(normalized).replaceAll(" ");
        normalized = TIER_WORD.matcher(normalized).replaceAll(" ");
        normalized = CJK_TIER_PREFIX.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("\\s+", " ").strip();
        return normalized.isBlank() ? value.toLowerCase(Locale.ROOT) : normalized;
    }
}
