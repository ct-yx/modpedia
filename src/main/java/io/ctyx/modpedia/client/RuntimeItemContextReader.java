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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * 在客户端线程读取已确认物品的当前 Tooltip。
 *
 * <p>这个适配器只处理一次 Worker 请求中的最多三个物品，不参与启动阶段的
 * 全量 item_catalog 扫描，也不访问 SQLite、文件、网络或 AI。返回值是纯文本
 * {@link RuntimeItemContext}，不会把 ItemStack、Level、玩家或第三方对象带出
 * 游戏 JVM。</p>
 */
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

        String language = language(minecraft);
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
                List<Component> lines = stack.getTooltipLines(
                        Item.TooltipContext.of(minecraft.level),
                        minecraft.player,
                        TooltipFlag.NORMAL
                );
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
                // 未知物品或单个物品 Tooltip 实现异常只跳过当前项。
            }
        }
        return List.copyOf(result);
    }

    /** 请求边界的纯文本规范化：去重、转小写，并最多保留三个物品 ID。 */
    static List<String> normalizeItemIds(List<String> values, int requestedLimit) {
        int limit = Math.min(
                WorkerProtocol.MAX_RUNTIME_ITEM_CONTEXT_ITEMS,
                Math.max(1, requestedLimit)
        );
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                unique.add(value.strip().toLowerCase(java.util.Locale.ROOT));
                if (unique.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    /** 第一行是名称，后续每个 Tooltip Component 作为一个 Markdown 列表项。 */
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

    /** 纯文本重载，供无 Minecraft 类路径的协议自测试复用同一转换规则。 */
    static String tooltipMarkdownText(List<String> lines) {
        StringBuilder result = new StringBuilder();
        if (lines == null) {
            return "";
        }
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line == null) {
                continue;
            }
            String text = line.replace('\r', ' ').replace('\n', ' ').strip();
            if (!text.isBlank()) {
                result.append("- ").append(text).append('\n');
            }
        }
        return result.toString().strip();
    }

    private static boolean worldReady(Minecraft minecraft) {
        return minecraft != null
                && minecraft.level != null
                && minecraft.player != null
                && minecraft.getConnection() != null
                && minecraft.getConnection().getConnection() != null
                && minecraft.getConnection().getConnection().isConnected();
    }

    private static String language(Minecraft minecraft) {
        // Tooltip 是客户端当前世界的事实，语言标记应与玩家界面一致；请求语言只
        // 决定 Worker 的检索分支，不能让 en_us 查询在中文客户端返回 zh_cn 以外的
        // 虚假标记。
        return SearchLanguage.fromMinecraft(minecraft.options.languageCode).code();
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
