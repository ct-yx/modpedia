package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;

/** 读取可选目标识别模组当前光标下的物品目标；失效或离开世界时自动清空。 */
public final class JadeTargetStore {
    private static final long EXPIRATION_MS = 2_000L;
    private static volatile Target current;

    private JadeTargetStore() {
    }

    public static void tick(Minecraft minecraft) {
        if (!ModList.get().isLoaded("jade") || minecraft == null || minecraft.level == null) {
            current = null;
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
            current = null;
            return;
        }
        BlockPos position = hit.getBlockPos();
        Block block = minecraft.level.getBlockState(position).getBlock();
        Item item = block.asItem();
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            current = null;
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        String name = new ItemStack(item).getHoverName().getString();
        current = new Target(id, name, System.currentTimeMillis());
    }

    public static Target current() {
        Target target = current;
        if (target == null || System.currentTimeMillis() - target.observedAt() > EXPIRATION_MS) {
            return null;
        }
        return target;
    }

    public record Target(String itemId, String displayName, long observedAt) {
        public Target {
            itemId = itemId == null ? "" : itemId;
            displayName = displayName == null ? itemId : displayName;
        }
    }
}
