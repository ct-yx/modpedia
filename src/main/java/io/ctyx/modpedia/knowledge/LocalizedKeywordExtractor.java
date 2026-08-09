package io.ctyx.modpedia.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 从手册页面自身的物品/方块 ID 对应的语言表中补充本地化搜索词。 */
final class LocalizedKeywordExtractor {
    private static final List<String> TRANSLATION_PREFIXES = List.of(
            "item", "block", "fluid", "entity", "effect", "enchantment"
    );

    private LocalizedKeywordExtractor() {
    }

    static List<String> extract(ScannedResource source) {
        if (source == null || source.translations().isEmpty()) {
            return List.of();
        }

        Set<String> pageIds = new LinkedHashSet<>();
        addPathId(source.path(), pageIds);

        Set<String> localized = new LinkedHashSet<>();
        for (String id : pageIds) {
            for (String prefix : TRANSLATION_PREFIXES) {
                String translation = source.translations().get(prefix + "." + source.modId() + "." + id);
                if (translation != null && !translation.isBlank()) {
                    localized.addAll(KeywordExtractor.extract(translation));
                }
            }
        }
        return List.copyOf(localized);
    }

    private static void addPathId(String path, Set<String> ids) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int extension = fileName.lastIndexOf('.');
        if (extension > 0) {
            fileName = fileName.substring(0, extension);
        }
        if (!fileName.isBlank()) {
            ids.add(fileName.toLowerCase(Locale.ROOT));
        }
    }
}
