package io.ctyx.modpedia.knowledge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 GuideME/GuideMarkdown 资源选择一个语言版本，并在翻译不完整时回退到基础文件。
 *
 * <p>GuideME 的资源通常把语言放在 {@code _zh_cn/} 目录中，而不是 Patchouli
 * 使用的 {@code zh_cn/} 目录。扫描器不能把基础版和所有翻译版一起导入，否则
 * 同一篇教程会变成多个 neutral 文档并污染搜索结果。</p>
 */
public final class GuideLocaleSelector {
    private static final Pattern ROOT = Pattern.compile(
            "^(assets|data)/([^/]+)/(guideme_guides|guides|ae2guide)(?:/(.*))?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LOCALIZED = Pattern.compile(
            "^(assets|data)/([^/]+)/(guideme_guides|guides|ae2guide)/_([a-z]{2}_[a-z]{2})/(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private GuideLocaleSelector() {
    }

    /** 返回应该读取的 GuideME 资源路径，路径比较按小写和正斜杠处理。 */
    public static Set<String> select(List<String> paths) {
        Map<String, Set<String>> localesByRoot = new HashMap<>();
        Map<String, Set<String>> localizedVariants = new HashMap<>();
        Set<String> rootsWithBaseFiles = new HashSet<>();

        for (String path : paths) {
            GuidePath guidePath = parse(path);
            if (guidePath == null) {
                continue;
            }
            String root = guidePath.rootKey();
            if (guidePath.locale().isBlank()) {
                rootsWithBaseFiles.add(root);
            } else {
                localesByRoot.computeIfAbsent(root, ignored -> new HashSet<>())
                        .add(guidePath.locale());
                localizedVariants.computeIfAbsent(
                                root + "|" + guidePath.relativePath(),
                                ignored -> new HashSet<>()
                        )
                        .add(guidePath.locale());
            }
        }

        Map<String, String> selectedByRoot = new HashMap<>();
        Set<String> roots = new HashSet<>(rootsWithBaseFiles);
        roots.addAll(localesByRoot.keySet());
        for (String root : roots) {
            Set<String> locales = localesByRoot.getOrDefault(root, Set.of());
            if (locales.contains("zh_cn")) {
                selectedByRoot.put(root, "zh_cn");
            } else if (locales.contains("en_us")) {
                selectedByRoot.put(root, "en_us");
            } else if (rootsWithBaseFiles.contains(root)) {
                // 基础文件就是 neutral/默认语言，优先于任意地区翻译。
                selectedByRoot.put(root, "");
            } else {
                // 没有基础文件时保留一个确定的地区版本，避免整本书消失。
                selectedByRoot.put(root, locales.stream().sorted().findFirst().orElse(""));
            }
        }

        Set<String> selected = new HashSet<>();
        for (String path : paths) {
            GuidePath guidePath = parse(path);
            if (guidePath == null) {
                continue;
            }
            String preferred = selectedByRoot.getOrDefault(guidePath.rootKey(), "");
            if (guidePath.locale().isBlank()) {
                boolean hasLocalizedVariant = localizedVariants
                        .getOrDefault(guidePath.rootKey() + "|" + guidePath.relativePath(), Set.of())
                        .contains(preferred);
                if (preferred.isBlank() || !hasLocalizedVariant) {
                    selected.add(normalize(path));
                }
            } else if (guidePath.locale().equals(preferred)) {
                selected.add(normalize(path));
            }
        }
        return Set.copyOf(selected);
    }

    /** 非 GuideME 路径不受此选择器约束。 */
    public static boolean shouldInclude(String path, Set<String> selectedPaths) {
        if (parse(path) == null) {
            return true;
        }
        return selectedPaths.contains(normalize(path));
    }

    private static GuidePath parse(String path) {
        String normalized = normalize(path);
        Matcher localized = LOCALIZED.matcher(normalized);
        if (localized.matches()) {
            return new GuidePath(
                    localized.group(1) + "/" + localized.group(2) + "/" + localized.group(3),
                    localized.group(4).toLowerCase(Locale.ROOT),
                    localized.group(5)
            );
        }
        Matcher root = ROOT.matcher(normalized);
        if (!root.matches() || root.group(4) == null || root.group(4).isBlank()) {
            return null;
        }
        return new GuidePath(
                root.group(1) + "/" + root.group(2) + "/" + root.group(3),
                "",
                root.group(4)
        );
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private record GuidePath(String rootKey, String locale, String relativePath) {
    }
}
