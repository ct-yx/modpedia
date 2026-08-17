package io.ctyx.modpedia.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将模型使用的稳定物品 ID 转成玩家当前语言的显示名称。 */
public final class ItemNameResolver {
    private static final Pattern TRANSLATION_ITEM_KEY = Pattern.compile(
            "(?i)^(?:item|block)\\.([a-z0-9][a-z0-9_.-]*)\\.([a-z0-9][a-z0-9/._-]*)$"
    );
    private static final Object INDEX_LOCK = new Object();
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();
    private static final Map<String, String> UNIQUE_IDS_BY_DISPLAY_NAME = new HashMap<>();
    private static final Set<String> AMBIGUOUS_DISPLAY_NAMES = new HashSet<>();
    private static volatile Map<String, String> DISPLAY_NAME_SNAPSHOT = Map.of();
    private static volatile ItemNameMatcher DISPLAY_NAME_MATCHER = ItemNameMatcher.empty();
    private static volatile long INDEX_GENERATION;
    private static boolean BUILDING_INDEX;

    private ItemNameResolver() {
    }

    public static String displayName(String id) {
        String normalized = id == null ? "" : id.strip();
        return registeredName(normalized).orElse(normalized);
    }

    /** 返回当前客户端注册表中的本地化名称；未注册的 ID 返回空值。 */
    public static Optional<String> registeredName(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            String normalized = id.strip().toLowerCase(Locale.ROOT);
            normalized = itemIdFromTranslationKey(normalized).orElse(normalized);
            ResourceLocation location = ResourceLocation.parse(normalized);
            Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
            if (item == null) {
                return Optional.empty();
            }
            String name = localizedName(item, normalized).orElse("");
            if (name.isBlank()) {
                return Optional.empty();
            }
            remember(normalized, name);
            return Optional.of(name);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * 清空当前语言的反向名称索引。物品目录在主菜单阶段捕获新语言时调用。
     */
    static void beginLanguageIndex() {
        synchronized (INDEX_LOCK) {
            DISPLAY_NAMES.clear();
            UNIQUE_IDS_BY_DISPLAY_NAME.clear();
            AMBIGUOUS_DISPLAY_NAMES.clear();
            // 保留上一份已发布快照，新的注册表名称在完成前只写入构建缓冲区。
            // 这样 Worker/Tooltip 在启动阶段失败或重试时，Cmd 模式不会短暂退化
            // 成“所有名称都无法转换为 ID”。
            BUILDING_INDEX = true;
        }
    }

    static void finishLanguageIndex() {
        synchronized (INDEX_LOCK) {
            // ItemCatalogSyncService 已经在同一个客户端线程逐项读取过本地化名称，当前
            // 索引是完整快照。只有后续语言切换才会由 beginLanguageIndex 清空它。
            DISPLAY_NAME_SNAPSHOT = Map.copyOf(UNIQUE_IDS_BY_DISPLAY_NAME);
            DISPLAY_NAME_MATCHER = ItemNameMatcher.from(DISPLAY_NAME_SNAPSHOT);
            INDEX_GENERATION++;
            BUILDING_INDEX = false;
        }
    }

    /** 丢弃未完成的构建缓冲区，但保留上一份可用名称快照。 */
    static void abortLanguageIndex() {
        synchronized (INDEX_LOCK) {
            DISPLAY_NAMES.clear();
            UNIQUE_IDS_BY_DISPLAY_NAME.clear();
            AMBIGUOUS_DISPLAY_NAMES.clear();
            BUILDING_INDEX = false;
        }
    }

    /** 记录一次已经确认的本地化名称，供 Cmd/Ctrl 显示 ID 和点击命中复用。 */
    static void remember(String id, String displayName) {
        String normalizedId = normalizeId(id);
        String normalizedName = cleanCandidate(displayName, normalizedId, "");
        if (normalizedId.isBlank() || normalizedName.isBlank()) {
            return;
        }
        synchronized (INDEX_LOCK) {
            DISPLAY_NAMES.put(normalizedId, normalizedName);
            if (AMBIGUOUS_DISPLAY_NAMES.contains(normalizedName)) {
                return;
            }
            String previous = UNIQUE_IDS_BY_DISPLAY_NAME.putIfAbsent(normalizedName, normalizedId);
            if (previous != null && !previous.equals(normalizedId)) {
                UNIQUE_IDS_BY_DISPLAY_NAME.remove(normalizedName);
                AMBIGUOUS_DISPLAY_NAMES.add(normalizedName);
            }
        }
    }

    /** 返回唯一匹配的本地化名称对应 ID；同名物品不做猜测。 */
    static Optional<String> idForDisplayName(String displayName) {
        String normalizedName = displayName == null ? "" : displayName.strip();
        if (normalizedName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DISPLAY_NAME_SNAPSHOT.get(normalizedName));
    }

    static Map<String, String> displayNameIndex() {
        return DISPLAY_NAME_SNAPSHOT;
    }

    /** 返回已经构建好的名称匹配器；索引尚未完成时返回空匹配器，不触发注册表扫描。 */
    static ItemNameMatcher displayNameMatcher() {
        return DISPLAY_NAME_MATCHER;
    }

    /** 当前名称索引版本，供消息布局缓存判断是否需要失效。 */
    static long indexGeneration() {
        return INDEX_GENERATION;
    }

    /**
     * 把 Minecraft 的物品/方块翻译键还原为已注册的物品 ID。
     *
     * <p>某些模组在语言资源尚未完成加载时会把
     * {@code item.namespace.path}/{@code block.namespace.path} 作为文本返回。
     * 这不是可展示的物品名称，也不能在 Cmd 模式下保持原样。</p>
     */
    static Optional<String> itemIdFromTranslationKey(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TRANSLATION_ITEM_KEY.matcher(value.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String candidate = (matcher.group(1) + ":" + matcher.group(2)).toLowerCase(Locale.ROOT);
        // 这是纯文本规范化，不访问 BuiltInRegistries。这样 Cmd 显示路径既能
        // 处理资源重载期间的翻译键，也不会让纯 Java Markdown 回归测试触发
        // Minecraft Bootstrap。实际是否为已注册物品仍由 registeredName() 校验。
        return Optional.of(candidate);
    }

    /** 供目录捕获使用：名称未完成本地化时返回空值，不把翻译键写进 SQLite。 */
    static Optional<String> localizedName(ItemStack stack, Item item, String itemId) {
        String normalizedId = normalizeId(itemId);
        String descriptionId = "";
        try {
            descriptionId = item == null ? "" : item.getDescriptionId();
        } catch (RuntimeException ignored) {
        }
        String hoverName = "";
        try {
            hoverName = stack == null ? "" : stack.getHoverName().getString();
        } catch (RuntimeException ignored) {
        }
        String languageName = "";
        if (!descriptionId.isBlank()) {
            try {
                languageName = Language.getInstance().getOrDefault(descriptionId);
            } catch (RuntimeException ignored) {
            }
        }
        String name = cleanCandidate(hoverName, normalizedId, descriptionId);
        if (name.isBlank()) {
            name = cleanCandidate(languageName, normalizedId, descriptionId);
        }
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    private static Optional<String> localizedName(Item item, String itemId) {
        return localizedName(new ItemStack(item), item, itemId);
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    private static String cleanCandidate(String value, String itemId, String descriptionId) {
        String candidate = value == null ? "" : value.strip();
        if (candidate.isBlank()
                || candidate.equalsIgnoreCase(itemId)
                || (!descriptionId.isBlank() && candidate.equalsIgnoreCase(descriptionId))
                || isTranslationKey(candidate)) {
            return "";
        }
        return candidate;
    }

    private static boolean isTranslationKey(String value) {
        return TRANSLATION_ITEM_KEY.matcher(value).matches()
                || value.matches("(?i)(fluid|entity|effect|enchantment|painting|container|gui|blockentity)\\.[a-z0-9_./-]+")
                || value.startsWith("translation.");
    }
}
