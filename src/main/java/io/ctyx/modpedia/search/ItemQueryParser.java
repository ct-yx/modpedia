package io.ctyx.modpedia.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提取玩家确认的物品令牌和正文中的资源 ID，供目录查询与手册检索共用。 */
public final class ItemQueryParser {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}\\u3400-\\u9fff]+(?:[:_./-][\\p{L}\\p{N}\\u3400-\\u9fff]+)*"
    );
    private static final Pattern ITEM_TOKEN = Pattern.compile(
            "\\[\\[item:([^|\\]]+)(?:\\|([^\\]]*))?\\]]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_ITEM_ID = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9/._-]*)(?![A-Za-z0-9/._-])",
            Pattern.CASE_INSENSITIVE
    );

    private ItemQueryParser() {
    }

    public static Parsed parse(String value) {
        String original = value == null ? "" : value;
        LinkedHashSet<String> itemIds = new LinkedHashSet<>();
        StringBuilder searchable = new StringBuilder();
        Matcher tokenMatcher = ITEM_TOKEN.matcher(original);
        int cursor = 0;
        while (tokenMatcher.find()) {
            searchable.append(original, cursor, tokenMatcher.start());
            String itemId = normalizeId(tokenMatcher.group(1));
            if (!itemId.isBlank()) {
                itemIds.add(itemId);
            }
            String suppliedName = tokenMatcher.group(2) == null ? "" : tokenMatcher.group(2).strip();
            searchable.append(suppliedName.isBlank() ? itemId : suppliedName);
            cursor = tokenMatcher.end();
        }
        searchable.append(original, cursor, original.length());

        Matcher rawMatcher = RAW_ITEM_ID.matcher(searchable.toString());
        while (rawMatcher.find()) {
            itemIds.add(normalizeId(rawMatcher.group(1)));
        }
        return new Parsed(original, searchable.toString().strip(), List.copyOf(itemIds));
    }

    /**
     * 提取用于物品目录精确名称查询的候选短语。
     *
     * <p>这里只生成很小的查询窗口，不读取整个目录到 Java 内存。中文使用连续
     * 子串，英文使用最多四个相邻词；最终是否命中仍由
     * {@code display_name_normalized = ?} 决定。</p>
     */
    public static List<String> displayNameCandidates(String value) {
        String normalized = SearchTextNormalizer.normalizeRaw(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = SearchTextNormalizer.normalizeField(matcher.group());
            if (token.isBlank()) {
                continue;
            }
            tokens.add(token);
            addCandidate(candidates, token);
            if (isCjk(token)) {
                int maxLength = Math.min(12, token.length());
                for (int length = 1; length <= maxLength; length++) {
                    for (int start = 0; start + length <= token.length(); start++) {
                        addCandidate(candidates, token.substring(start, start + length));
                        if (candidates.size() >= 64) {
                            return List.copyOf(candidates);
                        }
                    }
                }
            }
        }

        for (int start = 0; start < tokens.size(); start++) {
            StringBuilder phrase = new StringBuilder();
            for (int length = 1; length <= 4 && start + length <= tokens.size(); length++) {
                if (phrase.length() > 0) {
                    phrase.append(' ');
                }
                phrase.append(tokens.get(start + length - 1));
                addCandidate(candidates, phrase.toString());
                if (candidates.size() >= 64) {
                    return List.copyOf(candidates);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static void addCandidate(LinkedHashSet<String> candidates, String value) {
        String normalized = SearchTextNormalizer.normalizeField(value);
        if (normalized.length() >= 2 || isCjk(normalized)) {
            candidates.add(normalized);
        }
    }

    private static boolean isCjk(String value) {
        return !value.isBlank() && value.codePoints().allMatch(codePoint ->
                (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                        || (codePoint >= 0x4E00 && codePoint <= 0x9FFF));
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public record Parsed(String original, String searchableText, List<String> itemIds) {
        public Parsed {
            original = original == null ? "" : original;
            searchableText = searchableText == null ? "" : searchableText;
            itemIds = itemIds == null ? List.of() : List.copyOf(new ArrayList<>(itemIds));
        }
    }
}
