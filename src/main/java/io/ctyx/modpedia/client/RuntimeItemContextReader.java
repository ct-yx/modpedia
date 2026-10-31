package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.RuntimeItemContext;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import io.ctyx.modpedia.search.SearchLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Forge 1.20.1 适配：只在 Worker 请求且用户明确引用物品时读取当前 Tooltip。 */
public final class RuntimeItemContextReader implements ModPediaBridge.RuntimeItemContextHandler {
    private static final int MAX_TOOLTIP_BYTES_PER_ITEM = 12 * 1024;
    private static final int MAX_DISPLAY_NAME_BYTES = 1 * 1024;

    @Override
    public List<RuntimeItemContext> read(ModPediaBridge.RuntimeItemContextRequest request) {
        if (request == null || request.itemIds().isEmpty()) {
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!worldReady(minecraft)) {
            return List.of();
        }

        String language = SearchLanguage.fromMinecraft(minecraft.options.languageCode).code();
        List<String> itemIds = normalizeItemIds(request.itemIds(), request.maxItems());
        List<RuntimeItemContext> result = new ArrayList<>();
        for (String itemId : itemIds) {
            try {
                ResourceLocation location = ResourceLocation.parse(itemId);
                Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                List<Component> lines = stack.getTooltipLines(minecraft.player, TooltipFlag.NORMAL);
                if (lines == null || lines.isEmpty()) {
                    continue;
                }
                String displayName = limitUtf8(
                        lines.get(0) == null ? "" : lines.get(0).getString().strip(),
                        MAX_DISPLAY_NAME_BYTES
                );
                String tooltipMarkdown = limitUtf8(
                        tooltipMarkdown(lines),
                        MAX_TOOLTIP_BYTES_PER_ITEM
                );
                if (displayName.isBlank() && tooltipMarkdown.isBlank()) {
                    continue;
                }
                result.add(new RuntimeItemContext(
                        location.toString(),
                        language,
                        displayName,
                        tooltipMarkdown,
                        true,
                        System.currentTimeMillis()
                ));
            } catch (Throwable ignored) {
                // 单个物品或第三方 Tooltip 异常只跳过当前项，Worker 回退静态目录。
            }
        }
        return List.copyOf(result);
    }

    /** 请求边界去重、规范化，并限制最多五个物品。 */
    static List<String> normalizeItemIds(List<String> values, int requestedLimit) {
        int limit = Math.min(
                WorkerProtocol.MAX_RUNTIME_ITEM_CONTEXT_ITEMS,
                Math.max(1, requestedLimit)
        );
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalizeItemId(value);
                if (!normalized.isBlank()) {
                    unique.add(normalized);
                }
                if (unique.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    static String normalizeItemId(String itemId) {
        return itemId == null ? "" : itemId.strip().toLowerCase(Locale.ROOT);
    }

    /** 第一行是名称，后续 Tooltip Component 转换为 Markdown 列表。 */
    static String tooltipMarkdown(List<Component> lines) {
        StringBuilder result = new StringBuilder();
        if (lines == null) {
            return "";
        }
        for (int index = 1; index < lines.size(); index++) {
            Component line = lines.get(index);
            if (line == null) {
                continue;
            }
            String text = line.getString().replace('\r', ' ').replace('\n', ' ').strip();
            if (!text.isBlank()) {
                result.append("- ").append(text).append('\n');
            }
        }
        return result.toString().strip();
    }

    /** 纯文本自测重载。 */
    static String tooltipMarkdownText(List<String> lines) {
        List<Component> components = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                components.add(line == null ? null : Component.literal(line));
            }
        }
        return tooltipMarkdown(components);
    }

    private static boolean worldReady(Minecraft minecraft) {
        return minecraft != null
                && minecraft.level != null
                && minecraft.player != null
                && minecraft.getConnection() != null
                && minecraft.getConnection().getConnection() != null
                && minecraft.getConnection().getConnection().isConnected();
    }

    private static String limitUtf8(String value, int maxBytes) {
        String actual = value == null ? "" : value;
        if (actual.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return actual;
        }
        int end = actual.length();
        while (end > 0) {
            end = actual.offsetByCodePoints(end, -1);
            if (actual.substring(0, end).getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
                return actual.substring(0, end);
            }
        }
        return "";
    }
}
