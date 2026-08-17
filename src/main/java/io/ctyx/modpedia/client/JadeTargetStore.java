package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存可选视线识别联动当前报告的物品目标。
 *
 * <p>识别模组成功接入后，目标来自其 tooltip 回调，而不是直接读取 Minecraft 的
 * {@code hitResult}。未安装或尚未完成回调注册时保留原生准星读取作为基础模式；高级模式
 * 可以使用识别模组最终决定的目标（例如伪装方块、替代掉落物或实体拾取物）。</p>
 */
public final class JadeTargetStore {
    private static final long EXPIRATION_MS = 2_000L;
    private static final Map<Class<?>, Method> PICKED_RESULT_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> FAKE_BLOCK_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> TARGET_METHODS = new ConcurrentHashMap<>();
    private static volatile Target current;

    private JadeTargetStore() {
    }

    /** 每 tick 清理离开世界、移开目标或识别回调停止后的旧目标。 */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            current = null;
            return;
        }
        if (ModList.get().isLoaded("jade") && JadeClientBridge.isRegistered()) {
            // 高级模式完全以识别模组回调为准，避免把识别出的实体或伪装目标误换成原生方块。
            current();
            return;
        }
        // 没有识别模组，或它尚未完成回调注册时，保留原来的准星读取行为。
        updateFromCrosshair(minecraft);
    }

    private static void updateFromCrosshair(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
            clear();
            return;
        }
        BlockPos position = hit.getBlockPos();
        Block block = minecraft.level.getBlockState(position).getBlock();
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            clear();
            return;
        }
        updateFromItemStack(new ItemStack(item));
    }

    /**
     * 从识别模组公开 Accessor 的反射对象提取最终拾取物。
     *
     * <p>这里不直接引用可选模组的类，避免它缺失时客户端或 Dedicated Server 解析到
     * optional API。{@code getPickedResult()} 是识别模组 Accessor 的稳定公开入口；对
     * 伪装方块再回退到 {@code getFakeBlock()} 和 {@code getTarget().asItem()}。</p>
     */
    static void updateFromJadeAccessor(Object accessor) {
        if (accessor == null) {
            clear();
            return;
        }
        ItemStack stack = invokeItemStack(accessor, PICKED_RESULT_METHODS, "getPickedResult");
        if (stack.isEmpty()) {
            stack = invokeItemStack(accessor, FAKE_BLOCK_METHODS, "getFakeBlock");
        }
        if (stack.isEmpty()) {
            Object target = invoke(accessor, TARGET_METHODS, "getTarget");
            if (target instanceof net.minecraft.world.level.block.Block block) {
                Item item = block.asItem();
                if (item != null && item != Items.AIR) {
                    stack = new ItemStack(item);
                }
            }
        }
        updateFromItemStack(stack);
    }

    static void updateFromItemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            clear();
            return;
        }
        Item item = stack.getItem();
        if (item == null || item == Items.AIR) {
            clear();
            return;
        }
        var id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            clear();
            return;
        }
        updateFromIdentifier(id.toString(), stack.getHoverName().getString());
    }

    static void updateFromIdentifier(String itemId, String displayName) {
        if (itemId == null || itemId.isBlank()) {
            clear();
            return;
        }
        current = new Target(
                itemId,
                displayName == null || displayName.isBlank() ? itemId : displayName,
                System.currentTimeMillis()
        );
    }

    static void clear() {
        current = null;
    }

    public static Target current() {
        Target target = current;
        if (target == null || System.currentTimeMillis() - target.observedAt() > EXPIRATION_MS) {
            current = null;
            return null;
        }
        return target;
    }

    private static ItemStack invokeItemStack(
            Object target,
            Map<Class<?>, Method> cache,
            String methodName
    ) {
        Object value = invoke(target, cache, methodName);
        return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static Object invoke(Object target, Map<Class<?>, Method> cache, String methodName) {
        if (target == null) {
            return null;
        }
        Method method = cache.computeIfAbsent(target.getClass(), type -> findMethod(type, methodName));
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> currentType = type; currentType != null; currentType = currentType.getSuperclass()) {
            try {
                Method method = currentType.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // 继续检查父类；Accessor 也可能由代理或实现类继承公开方法。
            }
        }
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException ignored) {
            return null;
        }
    }

    public record Target(String itemId, String displayName, long observedAt) {
        public Target {
            itemId = itemId == null ? "" : itemId;
            displayName = displayName == null ? itemId : displayName;
        }
    }
}
