package io.ctyx.modpedia.search;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将中文、英文和模组标识符归一为可比较的检索词。 */
public final class SearchTextNormalizer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}\\u3400-\\u9fff]+(?:[:_./-][\\p{L}\\p{N}\\u3400-\\u9fff]+)*"
    );
    private static final Pattern CJK_RUN_PATTERN = Pattern.compile("[\\u3400-\\u9fff]{2,}");
    private static final Pattern CJK_ENTITY_SEPARATOR = Pattern.compile("(?:以及|或者|和|与|及|或)");
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[\\s\\p{Punct}\\p{IsPunctuation}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is",
            "it", "of", "on", "or", "the", "to", "what", "where", "which", "with", "step", "steps",
            "procedure", "procedures", "setup", "prerequisite", "prerequisites", "requirement",
            "requirements", "usage", "instructions", "start", "begin", "using", "怎么", "如何",
            "什么", "哪个", "哪些", "怎么做", "如何使用", "使用方法", "用法", "可以", "需要", "这个", "那个",
            "设置", "前置", "前置条件", "前提", "条件", "步骤", "操作", "操作步骤", "防止"
    );
    private static final Set<String> CJK_QUERY_FILLERS = Set.of(
            "怎么", "如何", "什么", "哪个", "哪些", "怎么做", "如何使用", "使用方法", "用法",
            "可以", "需要", "这个", "那个", "模组", "连接", "连接方式", "设置", "安装", "启动",
            "使用", "怎么用", "配方", "合成", "制作", "材料", "依赖", "前置依赖", "前置条件",
            "避免", "是否", "能否", "为什么", "无法", "没有", "怎么解决", "操作", "操作步骤",
            "步骤", "防止", "漏气", "故障", "问题", "请问", "我想知道",
            "我想了解", "介绍", "简介", "概览", "是什么", "开始", "核心玩法", "主要玩法",
            "玩法", "功能", "机制", "生存流程", "模组介绍", "模组功能", "全称", "缩写", "内容",
            "和", "以及"
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

    /**
     * 提取连续中文问题中的实体短语，去掉动作和疑问词。
     * 例如“压力管道怎么连接”得到“压力管道”，“me成型面板怎么用”得到“成型面板”。
     */
    public static List<String> semanticCjkPhrases(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher tokenMatcher = TOKEN_PATTERN.matcher(normalizeRaw(value));
        while (tokenMatcher.find()) {
            Matcher cjkMatcher = CJK_RUN_PATTERN.matcher(tokenMatcher.group());
            while (cjkMatcher.find()) {
                // 中文问题可能同时包含多个实体，例如“压力容器和控制器怎么启动”。
                // 如果把连接词两侧合并成一个短语，索引必须在同一段同时出现两个实体，
                // 结果就会被错误收窄；先拆成独立实体，后续检索可以分别召回两套证据。
                for (String entityPart : CJK_ENTITY_SEPARATOR.split(cjkMatcher.group())) {
                    String phrase = stripQuestionWords(entityPart);
                    if (phrase.length() >= 2 && !STOP_WORDS.contains(phrase)) {
                        result.add(phrase);
                        // “新的压力”“机器的使用方法”这类自然表达中，结构助词会把
                        // 整句变成一个永远不会出现在标题里的词。保留完整短语用于精确
                        // 匹配，同时补充“的”两侧的实体片段用于召回。
                        for (String part : phrase.split("[的地得]")) {
                            if (part.length() >= 2 && !STOP_WORDS.contains(part)) {
                                result.add(part);
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static String stripQuestionWords(String value) {
        String result = value == null ? "" : value;
        List<String> fillers = CJK_QUERY_FILLERS.stream()
                .filter(candidate -> !candidate.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        boolean changed;
        do {
            changed = false;
            for (String filler : fillers) {
                String prefixRemainder = result.startsWith(filler)
                        ? result.substring(filler.length())
                        : "";
                if (result.startsWith(filler)
                        && (prefixRemainder.isBlank() || prefixRemainder.length() >= 2)) {
                    result = prefixRemainder;
                    changed = true;
                }
                String suffixRemainder = result.endsWith(filler)
                        ? result.substring(0, result.length() - filler.length())
                        : "";
                if (result.endsWith(filler)
                        && (suffixRemainder.isBlank() || suffixRemainder.length() >= 2)) {
                    result = suffixRemainder;
                    changed = true;
                }
            }
            if (result.startsWith("的") || result.startsWith("地") || result.startsWith("得")) {
                result = result.substring(1);
                changed = true;
            }
            if (result.endsWith("的") || result.endsWith("地") || result.endsWith("得")) {
                result = result.substring(0, result.length() - 1);
                changed = true;
            }
        } while (changed && !result.isBlank());
        return result;
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
