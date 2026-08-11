package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.List;
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

    private ItemTokenParser() {
    }

    public static Parsed parse(String value, boolean showIds) {
        return parse(value, showIds, ItemNameResolver::registeredName);
    }

    /** 解析协议令牌和回答正文中直接出现的已注册物品 ID。 */
    static Parsed parse(
            String value,
            boolean showIds,
            Function<String, Optional<String>> nameResolver
    ) {
        String text = value == null ? "" : value;
        Function<String, Optional<String>> resolver = nameResolver == null
                ? ignored -> Optional.empty()
                : nameResolver;
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder output = new StringBuilder();
        List<ItemReference> references = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            appendRawItemIds(text.substring(cursor, matcher.start()), output, references, showIds, resolver);
            String kind = matcher.group(1);
            String id = matcher.group(2).strip();
            String suppliedName = matcher.group(3) == null ? "" : matcher.group(3).strip();
            String display = protocolDisplay(kind, id, suppliedName, showIds, resolver);
            if (display.isBlank()) {
                display = id;
            }
            output.append(display);
            references.add(new ItemReference(kind, id, display));
            cursor = matcher.end();
        }
        appendRawItemIds(text.substring(cursor), output, references, showIds, resolver);
        return new Parsed(output.toString(), List.copyOf(references));
    }

    private static String protocolDisplay(
            String kind,
            String id,
            String suppliedName,
            boolean showIds,
            Function<String, Optional<String>> resolver
    ) {
        if (showIds) {
            return id;
        }
        // item 使用客户端当前语言的真实名称，不能盲信模型传来的显示名；
        // tag 没有唯一物品名称时仍保留模型提供的可读标签。
        if ("item".equals(kind)) {
            Optional<String> registered = resolve(resolver, id);
            if (registered.isPresent()) {
                return registered.get();
            }
        }
        return suppliedName.isBlank() ? id : suppliedName;
    }

    private static void appendRawItemIds(
            String value,
            StringBuilder output,
            List<ItemReference> references,
            boolean showIds,
            Function<String, Optional<String>> resolver
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
            output.append(text, cursor, matcher.start());
            String display = showIds ? id : registered.get();
            output.append(display);
            references.add(new ItemReference("item", id, display));
            cursor = matcher.end();
        }
        output.append(text, cursor, text.length());
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
}
