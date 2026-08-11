package io.ctyx.modpedia.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Optional;

/** 将模型使用的稳定物品 ID 转成玩家当前语言的显示名称。 */
public final class ItemNameResolver {
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
            ResourceLocation location = ResourceLocation.parse(normalized);
            Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
            if (item == null) {
                return Optional.empty();
            }
            String name = new ItemStack(item).getHoverName().getString().strip();
            if (name.isBlank() || name.equalsIgnoreCase(normalized)) {
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
