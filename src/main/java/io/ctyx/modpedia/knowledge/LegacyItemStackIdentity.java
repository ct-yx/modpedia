package io.ctyx.modpedia.knowledge;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** 仅在 1.12.2 客户端/服务端适配层使用的 ItemStack 身份转换。 */
public final class LegacyItemStackIdentity {
    private LegacyItemStackIdentity() {
    }

    public static LegacyItemIdentity from(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            throw new IllegalArgumentException("ItemStack 为空");
        }
        ResourceLocation registryName = stack.getItem().getRegistryName();
        if (registryName == null) {
            throw new IllegalArgumentException("物品没有注册名");
        }
        String nbt = stack.hasTagCompound() && stack.getTagCompound() != null
                ? stack.getTagCompound().toString() : "";
        return LegacyItemIdentity.of(registryName.toString(), stack.getMetadata(), nbt);
    }
}
