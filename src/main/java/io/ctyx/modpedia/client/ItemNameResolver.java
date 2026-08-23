package io.ctyx.modpedia.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
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
    private static volatile Object ENGLISH_RESOURCE_MANAGER;
    private static volatile Map<String, String> ENGLISH_LANGUAGE_DATA = Map.of();
    private static boolean BUILDING_INDEX;

    private ItemNameResolver() {
    }

    public static String displayName(String id) {
        String normalized = id == null ? "" : id.strip();
        Optional<String> registered = registeredName(normalized);
        if (registered.isPresent()) {
            return registered.get();
        }
        // 已注册但缺少当前语言和 en_us 翻译的物品，始终使用可读回退名。
        // 未注册的外部 ID 仍保留原文，便于诊断未知数据。
        try {
            if (!normalized.isBlank()
                    && BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(normalized)).isPresent()) {
                return readableFallbackName(normalized);
            }
        } catch (RuntimeException ignored) {
            // 非法或未知 ID 保留原文。
        }
        return normalized;
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

    /** 从持久化目录缓存恢复名称索引，不访问注册表和动态 Tooltip。 */
    static void replaceLanguageIndex(Collection<io.ctyx.modpedia.search.ItemCatalogEntry> entries) {
        synchronized (INDEX_LOCK) {
            DISPLAY_NAMES.clear();
            UNIQUE_IDS_BY_DISPLAY_NAME.clear();
            AMBIGUOUS_DISPLAY_NAMES.clear();
            BUILDING_INDEX = true;
        }
        if (entries != null) {
            for (io.ctyx.modpedia.search.ItemCatalogEntry entry : entries) {
                if (entry != null) {
                    remember(entry.itemId(), entry.displayName());
                }
            }
        }
        finishLanguageIndex();
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

    /**
     * 启动前静态目录使用的名称解析。这个入口只读取物品的固定描述键和当前
     * Language，不创建 ItemStack，也不调用任何 Tooltip/物品回调。
     */
    static Optional<String> staticLocalizedName(Item item, String itemId) {
        String normalizedId = normalizeId(itemId);
        if (item == null || normalizedId.isBlank()) {
            return Optional.empty();
        }
        try {
            String descriptionId = item.getDescriptionId();
            if (descriptionId == null || descriptionId.isBlank()) {
                return Optional.empty();
            }
            return staticLocalizedName(
                    descriptionId,
                    normalizedId,
                    Map.copyOf(Language.getInstance().getLanguageData()),
                    englishLanguageDataSnapshot()
            );
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * 使用已经快照化的语言表解析名称。这个重载不触碰 Minecraft/Language 单例，
     * 供启动前目录在专用后台线程批量处理数万条注册表记录。
     */
    static Optional<String> staticLocalizedName(
            String descriptionId,
            String itemId,
            Map<String, String> languageData,
            Map<String, String> englishLanguageData
    ) {
        String normalizedId = normalizeId(itemId);
        if (descriptionId == null || descriptionId.isBlank() || normalizedId.isBlank()) {
            return Optional.empty();
        }
        String name = cleanCandidate(
                languageData == null ? "" : languageData.getOrDefault(descriptionId, ""),
                normalizedId,
                descriptionId
        );
        if (name.isBlank()) {
            name = cleanCandidate(
                    englishLanguageData == null
                            ? ""
                            : englishLanguageData.getOrDefault(descriptionId, ""),
                    normalizedId,
                    descriptionId
            );
        }
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    /** 目录缓存和渲染入口共用的名称质量检查。 */
    static boolean isDisplayNameUsable(String displayName, String itemId) {
        String candidate = displayName == null ? "" : displayName.strip();
        String normalizedId = normalizeId(itemId);
        return !candidate.isBlank()
                && !candidate.equalsIgnoreCase(normalizedId)
                && !isTranslationKey(candidate);
    }

    /** 返回当前已加载语言表的不可变快照；调用方应在客户端加载阶段调用。 */
    static Map<String, String> languageDataSnapshot() {
        try {
            return Map.copyOf(Language.getInstance().getLanguageData());
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * 在客户端线程加载一次 en_us 资源，并返回不可变语言表。
     * 后台目录线程只使用返回值，不再访问 Minecraft 资源管理器。
     */
    static Map<String, String> englishLanguageDataSnapshot() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getResourceManager() == null) {
                return Map.of();
            }
            Object resourceManager = minecraft.getResourceManager();
            Map<String, String> data = ENGLISH_LANGUAGE_DATA;
            if (data.isEmpty() || resourceManager != ENGLISH_RESOURCE_MANAGER) {
                ClientLanguage language = ClientLanguage.loadFrom(
                        minecraft.getResourceManager(),
                        List.of("en_us"),
                        false
                );
                data = Map.copyOf(language.getLanguageData());
                ENGLISH_RESOURCE_MANAGER = resourceManager;
                ENGLISH_LANGUAGE_DATA = data;
            }
            return data;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * 从当前资源包中读取 en_us 语言表作为安全静态回退。这里只解析翻译资源，
     * 不创建 ItemStack、不触发 Tooltip 事件，也不会改变客户端当前语言。
     */
    private static String englishLocalizedName(String descriptionId) {
        if (descriptionId == null || descriptionId.isBlank()) {
            return "";
        }
        return englishLanguageDataSnapshot().getOrDefault(descriptionId, "");
    }

    /**
     * 真正没有任何翻译资源时使用可读的本地名称，而不是把 namespace:path 原样
     * 展示给玩家。稳定 ID 仍单独保存在 item_id，Cmd/Ctrl 模式可以继续显示它。
     */
    static String readableFallbackName(String itemId) {
        String normalized = normalizeId(itemId);
        if (normalized.isBlank()) {
            return "未知物品";
        }
        int separator = normalized.indexOf(':');
        String namespace = separator > 0 ? normalized.substring(0, separator) : "";
        String path = separator > 0 ? normalized.substring(separator + 1) : normalized;
        String readablePath = humanize(path);
        if (namespace.isBlank() || "minecraft".equals(namespace)) {
            return readablePath;
        }
        return humanize(namespace) + " · " + readablePath;
    }

    private static String humanize(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.split("[_./-]+")) {
            if (word.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? "未知物品" : result.toString();
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
