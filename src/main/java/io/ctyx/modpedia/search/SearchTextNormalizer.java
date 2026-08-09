package io.ctyx.modpedia.search;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将中文、英文和模组标识符归一为可比较的检索词。 */
final class SearchTextNormalizer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}\\u3400-\\u9fff]+(?:[:_./-][\\p{L}\\p{N}\\u3400-\\u9fff]+)*"
    );
    private static final Pattern CJK_RUN_PATTERN = Pattern.compile("[\\u3400-\\u9fff]{2,}");
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[\\s\\p{Punct}\\p{IsPunctuation}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is",
            "it", "of", "on", "or", "the", "to", "what", "where", "which", "with", "怎么", "如何",
            "什么", "哪个", "哪些", "怎么做", "可以", "需要", "这个", "那个"
    );

    private SearchTextNormalizer() {
    }

    static QueryTerms query(String raw, Map<String, Set<String>> synonyms) {
        String phrase = normalizeField(raw);
        if (phrase.isBlank()) {
            return new QueryTerms("", "", List.of(), 0);
        }

        LinkedHashMap<String, QueryTerm> terms = new LinkedHashMap<>();
        LinkedHashSet<String> primaryTerms = new LinkedHashSet<>();
        addTokens(normalizeRaw(raw), false, terms, primaryTerms);

        LinkedHashSet<String> synonymKeys = new LinkedHashSet<>(primaryTerms);
        String rawPhrase = normalizeRaw(raw).strip();
        if (!rawPhrase.isBlank()) {
            synonymKeys.add(rawPhrase);
        }
        for (String primary : synonymKeys) {
            for (String synonym : synonyms.getOrDefault(primary, Set.of())) {
                String normalized = normalizeField(synonym);
                if (!normalized.isBlank()) {
                    addTerm(terms, new QueryTerm(normalized, primary, true));
                    addTokens(normalized, true, terms, null);
                }
            }
        }

        return new QueryTerms(
                phrase,
                compact(phrase),
                List.copyOf(terms.values()),
                primaryTerms.size()
        );
    }

    static String normalizeField(String value) {
        return SEPARATOR_PATTERN.matcher(normalizeRaw(value)).replaceAll(" ").trim();
    }

    static String normalizeRaw(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u0000', ' ');
    }

    static String compact(String value) {
        return normalizeField(value).replace(" ", "");
    }

    static List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addTokens(normalizeRaw(value), false, toQueryMap(result), null);
        return List.copyOf(result);
    }

    static boolean isSignificant(String value) {
        String normalized = normalizeField(value);
        if (normalized.isBlank() || STOP_WORDS.contains(normalized)) {
            return false;
        }
        return normalized.length() >= 2 || normalized.matches("[a-z0-9_:/.-]+");
    }

    private static Map<String, QueryTerm> toQueryMap(Set<String> values) {
        return new LinkedHashMap<>() {
            @Override
            public QueryTerm put(String key, QueryTerm value) {
                values.add(key);
                return super.put(key, value);
            }
        };
    }

    private static void addTokens(
            String value,
            boolean synonym,
            Map<String, QueryTerm> terms,
            Set<String> primaryTerms
    ) {
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            String token = normalizeRaw(matcher.group());
            if (!isSignificant(token)) {
                continue;
            }
            addTerm(terms, new QueryTerm(token, token, synonym));
            if (primaryTerms != null) {
                primaryTerms.add(token);
            }

            for (String part : token.split("[:_./-]+")) {
                if (isSignificant(part)) {
                    addTerm(terms, new QueryTerm(part, token, synonym));
                }
            }

            Matcher cjkMatcher = CJK_RUN_PATTERN.matcher(token);
            while (cjkMatcher.find()) {
                String run = cjkMatcher.group();
                for (int index = 0; index + 1 < run.length(); index++) {
                    String gram = run.substring(index, index + 2);
                    addTerm(terms, new QueryTerm(gram, token, synonym));
                }
            }
        }
    }

    private static void addTerm(Map<String, QueryTerm> terms, QueryTerm candidate) {
        if (!isSignificant(candidate.value())) {
            return;
        }
        QueryTerm previous = terms.get(candidate.value());
        if (previous == null || (previous.synonym() && !candidate.synonym())) {
            terms.put(candidate.value(), candidate);
        }
    }

    record QueryTerms(
            String phrase,
            String compactPhrase,
            List<QueryTerm> terms,
            int primaryTermCount
    ) {
        QueryTerms {
            terms = terms == null ? List.of() : List.copyOf(terms);
        }

        boolean usable() {
            return !phrase.isBlank() && !terms.isEmpty();
        }
    }

    record QueryTerm(String value, String origin, boolean synonym) {
    }
}
