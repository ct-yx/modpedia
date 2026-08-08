package io.ctyx.modpedia.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 第一版轻量关键词提取器，后续可替换为更完整的中文分词实现。 */
public final class KeywordExtractor {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}\\u3400-\\u9fff][\\p{L}\\p{N}\\u3400-\\u9fff:_-]*");

    private KeywordExtractor() {
    }

    public static List<String> extract(String... values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Matcher matcher = TOKEN_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String token = matcher.group().trim();
                if (token.length() >= 2 || token.matches("[a-z0-9_:-]+")) {
                    result.add(token);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
