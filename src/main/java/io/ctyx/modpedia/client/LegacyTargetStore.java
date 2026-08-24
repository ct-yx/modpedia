package io.ctyx.modpedia.client;

import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import io.ctyx.modpedia.knowledge.LegacyItemStackIdentity;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 保存准星/Tooltip 当前物品；助手打开后冻结，避免 UI 盖住目标仍继续变更。 */
public final class LegacyTargetStore {
    private static final LegacyTargetStore INSTANCE = new LegacyTargetStore();
    private volatile Target current;
    private volatile boolean assistantOpen;

    private LegacyTargetStore() {
    }

    public static LegacyTargetStore get() {
        return INSTANCE;
    }

    public Target freezeForAssistant() {
        assistantOpen = true;
        Target value = current;
        return value;
    }

    public void release() {
        assistantOpen = false;
    }

    public Target current() {
        return current;
    }

    @SubscribeEvent
    public void onTooltip(RenderTooltipEvent.PostText event) {
        if (assistantOpen || event == null || event.getStack() == null || event.getStack().isEmpty()) {
            return;
        }
        capture(event.getStack(), event.getLines());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (assistantOpen || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            return;
        }
        if (minecraft.objectMouseOver == null
                || minecraft.objectMouseOver.typeOfHit == RayTraceResult.Type.MISS) {
            current = null;
            return;
        }
        RayTraceResult hit = minecraft.objectMouseOver;
        if (hit.typeOfHit == RayTraceResult.Type.BLOCK && minecraft.world != null) {
            BlockPos position = hit.getBlockPos();
            IBlockState state = minecraft.world.getBlockState(position);
            Item item = Item.getItemFromBlock(state.getBlock());
            if (item != null && item != Item.getItemFromBlock(net.minecraft.init.Blocks.AIR)) {
                capture(new ItemStack(item, 1, state.getBlock().getMetaFromState(state)), null);
            }
        } else if (hit.entityHit instanceof EntityLivingBase) {
            ItemStack held = ((EntityLivingBase) hit.entityHit).getHeldItemMainhand();
            if (held != null && !held.isEmpty()) {
                capture(held, null);
            } else {
                current = null;
            }
        } else {
            current = null;
        }
    }

    private void capture(ItemStack stack, List<String> renderedTooltipLines) {
        try {
            LegacyItemIdentity identity = LegacyItemStackIdentity.from(stack);
            List<String> lines = renderedTooltipLines;
            Target previous = current;
            if ((lines == null || lines.isEmpty()) && previous != null
                    && identity.canonicalKey().equals(previous.getItemId())) {
                lines = previous.getRenderedTooltipLines();
            }
            current = new Target(identity.canonicalKey(), stack.getDisplayName(), lines);
        } catch (RuntimeException ignored) {
            // 没有注册名的临时物品不作为可插入目标。
        }
    }

    public static final class Target {
        private final String itemId;
        private final String displayName;
        private final List<String> renderedTooltipLines;

        public Target(String itemId, String displayName) {
            this(itemId, displayName, Collections.<String>emptyList());
        }

        public Target(String itemId, String displayName, List<String> renderedTooltipLines) {
            this.itemId = itemId == null ? "" : itemId;
            this.displayName = displayName == null ? "" : displayName;
            List<String> lines = new ArrayList<String>();
            if (renderedTooltipLines != null) {
                int characters = 0;
                for (String line : renderedTooltipLines) {
                    if (line == null || line.trim().isEmpty() || lines.size() >= 64) {
                        continue;
                    }
                    String value = line.trim();
                    if (characters + value.length() > 12000) {
                        break;
                    }
                    lines.add(value);
                    characters += value.length();
                }
            }
            this.renderedTooltipLines = Collections.unmodifiableList(lines);
        }

        public String getItemId() {
            return itemId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public List<String> getRenderedTooltipLines() {
            return renderedTooltipLines;
        }
    }
}
