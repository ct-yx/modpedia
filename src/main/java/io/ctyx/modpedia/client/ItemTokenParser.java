package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析模型协议 [[item:id|名称]] / [[tag:id|名称]]，未知令牌原样显示。 */
public final class ItemTokenParser {
    private static final Pattern TOKEN = Pattern.compile(
            "\\[\\[(item|tag):([^|\\]]+)(?:\\|([^\\]]*))?\\]\\]"
    );
    /** 只候选合法资源 ID；是否替换仍由当前物品注册表确认。 */
    private static final Pattern RAW_ITEM_ID = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9/._-]*)(?![A-Za-z0-9/._-])"
    );
    private static final Pattern TRANSLATION_ITEM_KEY = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.-])((?:item|block)\\.[a-z0-9][a-z0-9_.-]*\\.[a-z0-9][a-z0-9/._-]*)(?![A-Za-z0-9/._-])"
    );

    private ItemTokenParser() {
    }

    public static Parsed parse(String value, boolean showIds) {
        return parse(
                value,
                showIds,
                ItemNameResolver::registeredName,
                showIds ? ItemNameResolver.displayNameMatcher() : ItemNameMatcher.empty()
        );
    }

    /** 解析协议令牌和回答正文中直接出现的已注册物品 ID。 */
    static Parsed parse(
            String value,
            boolean showIds,
            Function<String, Optional<String>> nameResolver
    ) {
        return parse(value, showIds, nameResolver, ItemNameMatcher.empty());
    }

    /**
     * 兼容旧的纯 Java 测试调用方。生产渲染路径使用 {@link ItemNameMatcher}，
     * 不在每个 Markdown 行上重新构建候选名称列表。
     */
    static Parsed parse(
            String value,
            boolean showIds,
            Function<String, Optional<String>> nameResolver,
            Map<String, String> displayNames
    ) {
        return parse(
                value,
                showIds,
                nameResolver,
                showIds ? ItemNameMatcher.from(displayNames) : ItemNameMatcher.empty()
        );
    }

    /** 允许渲染器复用一次性构建的本地化名称匹配器。 */
    static Parsed parse(
            String value,
            boolean showIds,
            Function<String, Optional<String>> nameResolver,
            ItemNameMatcher displayNameMatcher
    ) {
        String text = value == null ? "" : value;
        Function<String, Optional<String>> resolver = nameResolver == null
                ? ignored -> Optional.empty()
                : nameResolver;
        ItemNameMatcher nameMatcher = displayNameMatcher == null
                ? ItemNameMatcher.empty()
                : displayNameMatcher;
        Matcher tokenMatcher = TOKEN.matcher(text);
        StringBuilder output = new StringBuilder();
        List<ItemReference> references = new ArrayList<>();
        int cursor = 0;
        while (tokenMatcher.find()) {
            appendRawItemIds(
                    text.substring(cursor, tokenMatcher.start()),
                    output,
                    references,
                    showIds,
                    resolver,
                    nameMatcher
            );
            String kind = tokenMatcher.group(1);
            String id = canonicalRegisteredId(tokenMatcher.group(2).strip(), resolver);
            String suppliedName = tokenMatcher.group(3) == null ? "" : tokenMatcher.group(3).strip();
            String display = protocolDisplay(kind, id, suppliedName, showIds, resolver);
            if (display.isBlank()) {
                display = id;
            }
            output.append(display);
            references.add(new ItemReference(kind, id, display));
            cursor = tokenMatcher.end();
        }
        appendRawItemIds(text.substring(cursor), output, references, showIds, resolver, nameMatcher);
        return new Parsed(output.toString(), List.copyOf(references));
    }

    private static String protocolDisplay(
            String kind,
            String id,
            String suppliedName,
            boolean showIds,
            Function<String, Optional<String>> resolver
    ) {
        String canonicalId = canonicalRegisteredId(id, resolver);
        if (showIds) {
            return canonicalId;
        }
        // item 使用客户端当前语言的真实名称，不能盲信模型传来的显示名；
        // tag 没有唯一物品名称时仍保留模型提供的可读标签。
        if ("item".equals(kind)) {
            Optional<String> registered = resolve(resolver, canonicalId);
            if (registered.isPresent()) {
                return registered.get();
            }
        }
        if (!suppliedName.isBlank()) {
            Optional<String> suppliedId = ItemNameResolver.itemIdFromTranslationKey(suppliedName);
            if (suppliedId.isPresent()) {
                return resolve(resolver, suppliedId.get()).orElse(suppliedId.get());
            }
            return suppliedName;
        }
        return canonicalId;
    }

    private static void appendRawItemIds(
            String value,
            StringBuilder output,
            List<ItemReference> references,
            boolean showIds,
            Function<String, Optional<String>> resolver,
            ItemNameMatcher displayNameMatcher
    ) {
        String text = value == null ? "" : value;
        Matcher matcher = RAW_ITEM_ID.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            String id = matcher.group(1);
            Optional<String> registered = resolve(resolver, id);
            if (registered.isEmpty()) {
                continue;
            }
            String canonicalId = id;
            appendKnownItemText(
                    text.substring(cursor, matcher.start()),
                    output,
                    references,
                    showIds,
                    resolver,
                    displayNameMatcher
            );
            String display = showIds ? canonicalId : registered.get();
            output.append(display);
            references.add(new ItemReference("item", canonicalId, display));
            cursor = matcher.end();
        }
        appendKnownItemText(text.substring(cursor), output, references, showIds, resolver, displayNameMatcher);
    }

    /**
     * Cmd/Ctrl 模式下，模型没有使用协议令牌而只写出本地化物品名称时，仍尝试
     * 还原为稳定 ID。只使用唯一名称，避免同名物品被猜错；普通显示模式不走这条
     * 路径，因此不会把自然语言中的普通名词误替换掉。
     */
    private static void appendKnownItemText(
            String text,
            StringBuilder output,
            List<ItemReference> references,
            boolean showIds,
            Function<String, Optional<String>> resolver,
            ItemNameMatcher displayNameMatcher
    ) {
        if (text == null || text.isEmpty()) {
            output.append(text == null ? "" : text);
            return;
        }
        Map<Integer, TranslationAlias> translationAliases = translationAliases(text, resolver);
        for (int offset = 0; offset < text.length();) {
            TranslationAlias translationAlias = translationAliases.get(offset);
            if (translationAlias != null) {
                String display = showIds
                        ? translationAlias.itemId()
                        : resolve(resolver, translationAlias.itemId()).orElse(translationAlias.itemId());
                output.append(display);
                references.add(new ItemReference("item", translationAlias.itemId(), display));
                offset += translationAlias.length();
                continue;
            }
            if (!showIds) {
                output.append(text.charAt(offset++));
                continue;
            }
            ItemNameMatcher.Match match = displayNameMatcher.longestMatch(text, offset);
            if (match == null || !displayNameBoundary(text, offset, match.length())) {
                output.append(text.charAt(offset++));
                continue;
            }
            String id = match.itemId();
            output.append(id);
            references.add(new ItemReference("item", id, id));
            offset += match.length();
        }
    }

    private static Map<Integer, TranslationAlias> translationAliases(
            String text,
            Function<String, Optional<String>> resolver
    ) {
        Map<Integer, TranslationAlias> result = new HashMap<>();
        Matcher matcher = TRANSLATION_ITEM_KEY.matcher(text);
        while (matcher.find()) {
            ItemNameResolver.itemIdFromTranslationKey(matcher.group(1)).ifPresent(id ->
                    resolve(resolver, id).ifPresent(ignored ->
                            result.put(matcher.start(), new TranslationAlias(id, matcher.group(1).length())))
            );
        }
        return result;
    }

    private static String canonicalRegisteredId(
            String value,
            Function<String, Optional<String>> resolver
    ) {
        String original = value == null ? "" : value.strip();
        return ItemNameResolver.itemIdFromTranslationKey(original)
                .filter(id -> resolve(resolver, id).isPresent())
                .orElse(original);
    }

    private static boolean displayNameBoundary(String text, int start, int length) {
        int end = start + length;
        String value = text.substring(start, end);
        boolean asciiWord = value.codePoints().allMatch(codePoint ->
                codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_'));
        if (!asciiWord) {
            return true;
        }
        boolean beforeWord = start > 0 && isAsciiWord(text.charAt(start - 1));
        boolean afterWord = end < text.length() && isAsciiWord(text.charAt(end));
        return !beforeWord && !afterWord;
    }

    private static boolean isAsciiWord(char value) {
        return value < 128 && (Character.isLetterOrDigit(value) || value == '_');
    }

    private static Optional<String> resolve(
            Function<String, Optional<String>> resolver,
            String id
    ) {
        try {
            Optional<String> name = resolver.apply(id);
            return name == null ? Optional.empty() : name.filter(value -> !value.isBlank());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public record Parsed(String text, List<ItemReference> references) {
        public Parsed {
            text = text == null ? "" : text;
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    private record TranslationAlias(String itemId, int length) {
    }
}
