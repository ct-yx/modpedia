package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemStackIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemCatalogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在注册表完成后分批捕获 1.12.2 物品名称和 Tooltip，并交给 Worker 写入目录。 */
public final class LegacyItemCatalogSyncService {
    private static final LegacyItemCatalogSyncService INSTANCE = new LegacyItemCatalogSyncService();
    private final Map<String, LegacyItemCatalogEntry> cache = new HashMap<String, LegacyItemCatalogEntry>();
    private Iterator<Item> items;
    private Iterator<ItemStack> variants;
    private boolean running;
    private boolean submitted;
    private Item currentItem;
    private String activeLanguage;
    private String pendingLanguage;
    private List<LegacyItemCatalogEntry> pendingEntries;

    private LegacyItemCatalogSyncService() {
    }

    public static LegacyItemCatalogSyncService get() {
        return INSTANCE;
    }

    public void start() {
        if (running || submitted) {
            return;
        }
        Collection<Item> values = ForgeRegistries.ITEMS.getValuesCollection();
        items = new ArrayList<Item>(values).iterator();
        variants = null;
        currentItem = null;
        running = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null) {
            return;
        }
        String language = normalizeLanguage(minecraft.gameSettings.language);
        if (activeLanguage != null && !activeLanguage.equals(language)) {
            // 语言切换时只重建当前语言目录；不保留上一语言的内存缓存，避免
            // Ctrl/普通显示在切换后混用旧名称。
            cache.clear();
            pendingEntries = null;
            pendingLanguage = null;
            submitted = false;
            Collection<Item> values = ForgeRegistries.ITEMS.getValuesCollection();
            items = new ArrayList<Item>(values).iterator();
            variants = null;
            currentItem = null;
            running = true;
        }
        if (!running) {
            return;
        }
        if (activeLanguage == null) {
            activeLanguage = language;
        }

        // 预算按实际变体而不是按 Item 计算。大型整合包里一个 Item 可能
        // 展开出数百个 metadata 变体，不能在一个 ClientTick 中全部生成 Tooltip。
        int budget = 32;
        while (budget-- > 0) {
            if (variants == null || !variants.hasNext()) {
                if (!items.hasNext()) {
                    running = false;
                    pendingLanguage = language;
                    pendingEntries = new ArrayList<LegacyItemCatalogEntry>(cache.values());
                    trySubmitPending();
                    return;
                }
                currentItem = items.next();
                variants = variants(currentItem);
            }
            if (variants != null && variants.hasNext()) {
                collect(variants.next(), currentItem, minecraft, language);
            }
        }
        if (!items.hasNext() && (variants == null || !variants.hasNext())) {
            running = false;
            pendingLanguage = language;
            pendingEntries = new ArrayList<LegacyItemCatalogEntry>(cache.values());
            trySubmitPending();
        }
    }

    /** Worker 晚于注册表完成时由 ready 回调补交，不重复扫描 Tooltip。 */
    public void trySubmitPending() {
        if (submitted || pendingEntries == null || !LegacyWorkerBridge.get().isReady()) {
            return;
        }
        submitted = true;
        final List<LegacyItemCatalogEntry> values = pendingEntries;
        LegacyWorkerBridge.get().syncItems(pendingLanguage, values).whenComplete((ignored, failure) -> {
            if (failure != null) {
                ModPedia.LOGGER.debug("1.12 物品目录同步未完成：{}", failure.getMessage());
            }
        });
    }

    public String displayName(String itemValue, boolean showId) {
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(itemValue);
            LegacyItemCatalogEntry entry = cache.get(identity.canonicalKey());
            // 模型经常只返回 namespace:item；1.12.2 的目录按 metadata 保存，
            // 因此无显式变体时优先使用默认 metadata=0 的名称，而不是退回显示
            // 原始 ID。回答令牌显式带 meta 时仍然严格匹配对应变体。
            if (entry == null && identity.getMetadata() == LegacyItemIdentity.UNSPECIFIED_METADATA) {
                entry = cache.get(identity.getItemId() + "@0");
            }
            if (showId || entry == null || entry.getDisplayName().isEmpty()) {
                return identity.canonicalKey();
            }
            return entry.getDisplayName();
        } catch (RuntimeException exception) {
            return itemValue == null ? "" : itemValue;
        }
    }

    private Iterator<ItemStack> variants(Item item) {
        if (item == null || item.getRegistryName() == null) {
            return Collections.<ItemStack>emptyList().iterator();
        }
        NonNullList<ItemStack> values = NonNullList.create();
        try {
            item.getSubItems(CreativeTabs.SEARCH, values);
        } catch (Throwable ignored) {
            // 少数旧物品不实现 CreativeTabs；回退到 metadata=0。
        }
        if (values.isEmpty()) {
            values.add(new ItemStack(item, 1, 0));
        }
        return new ArrayList<ItemStack>(values).iterator();
    }

    private void collect(ItemStack stack, Item item, Minecraft minecraft, String language) {
        if (item == null || item.getRegistryName() == null) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        try {
            LegacyItemIdentity identity = LegacyItemStackIdentity.from(stack);
            String key = identity.canonicalKey();
            String display = stack.getDisplayName();
            List<String> tooltip = stack.getTooltip(minecraft.player, ITooltipFlag.TooltipFlags.NORMAL);
            StringBuilder description = new StringBuilder();
            for (int index = 1; index < tooltip.size(); index++) {
                String line = tooltip.get(index);
                if (line != null && !line.trim().isEmpty()) {
                    description.append("- ").append(line.trim()).append('\n');
                }
            }
            String sourceMod = item.getRegistryName().getResourceDomain();
            String fingerprint = sha256(key + "|" + language + "|" + display + "|" + description);
            cache.put(key, new LegacyItemCatalogEntry(
                    key, language, display, description.toString(), sourceMod, fingerprint
            ));
        } catch (Throwable ignored) {
            // 单个异常物品不影响其余注册物品完成目录同步。
        }
    }

    private String normalizeLanguage(String value) {
        String language = value == null ? "zh_cn" : value.toLowerCase(Locale.ROOT);
        return language.replace('-', '_');
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
