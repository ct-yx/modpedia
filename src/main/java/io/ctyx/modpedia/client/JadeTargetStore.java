package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
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
    private static final Map<Class<?>, Method> HOVERED_SLOT_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> FTB_INGREDIENT_METHODS = new ConcurrentHashMap<>();
    private static volatile Target current;
    private static volatile TargetSource source = TargetSource.NONE;
    private static volatile long lastTooltipAt;
    /**
     * 助手打开后固定按 K 瞬间捕获的目标。助手仍会绘制 previousScreen 作为背景，
     * 因此底层 GUI 可能继续触发 Tooltip/Jade 回调；这些回调在冻结期间必须全部忽略。
     */
    private static volatile boolean assistantTargetFrozen;
    private static volatile Target assistantTarget;

    private JadeTargetStore() {
    }

    /** 每 tick 清理离开世界、移开目标或识别回调停止后的旧目标。 */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            releaseAssistantTarget();
            return;
        }
        if (assistantTargetFrozen) {
            // AssistantScreen 会继续绘制底层页面，但底层页面的鼠标/准星目标不再
            // 代表用户要插入的对象；保持按 K 时的快照直到助手关闭。
            return;
        }
        // GatherComponents 能覆盖绝大多数第三方 Tooltip 绘制路径。JEI 自己的
        // ingredient overlay 和容器槽位再作为补充，覆盖那些没有经过原生 Tooltip
        // 事件的 GUI；全部检查都只在客户端 tick 的轻量反射路径执行。
        if (isRecentSource(TargetSource.TOOLTIP)) {
            return;
        }
        if (captureFtbLibraryHover(minecraft)
                || captureJeiHover()
                || captureContainerHover(minecraft)) {
            return;
        }
        if (isRecentJadeTarget()) {
            // Jade 回调优先于原生准星结果；回调暂时没有刷新时仍保留短时目标。
            return;
        }
        if (minecraft.screen != null) {
            // 打开 JEI、FTBQ 或容器时，准星物品已经不是鼠标悬浮物品。
            // 高级捕获失败时清除旧准星目标，避免把上一个世界目标插入助手。
            clear();
            return;
        }
        // 没有新的高级目标时始终保留原来的准星读取行为；即使安装了 Jade，
        // 也不能因为回调暂时失效而让基础模式消失。
        updateFromCrosshair(minecraft);
    }

    /**
     * 在第三方 GUI 处理 K 之前立即刷新一次鼠标目标。
     *
     * <p>ScreenEvent.KeyPressed.Pre 可能发生在本帧 Tooltip 绘制之前，因此只依赖上一
     * 帧的 RenderTooltipEvent 会留下旧的准星物品。这里先读取 FTBQ/JEI/容器当前槽位，
     * 再接受刚刚捕获的 Tooltip；GUI 中没有可靠悬浮物品时返回空，不回退到准星目标。</p>
     */
    public static Target captureForAssistant(Minecraft minecraft) {
        if (assistantTargetFrozen) {
            return assistantTarget;
        }
        if (minecraft == null || minecraft.screen == null) {
            return current();
        }
        if (captureFtbLibraryHover(minecraft)
                || captureJeiHover()
                || captureContainerHover(minecraft)
                || captureJeiHover(true)) {
            return current();
        }
        if (isRecentSource(TargetSource.TOOLTIP)
                || isRecentSource(TargetSource.JEI)
                || isRecentSource(TargetSource.CONTAINER)
                || isRecentSource(TargetSource.FTB_LIBRARY)) {
            return current();
        }
        clear();
        return null;
    }

    /**
     * 在助手即将显示前捕获并冻结当前目标。
     *
     * <p>即使当前没有目标，也会冻结为“空目标”。这样打开助手后，底层 Tooltip
     * 或识别回调不会把后来悬浮到的物品偷偷写入插入按钮。</p>
     */
    public static Target freezeForAssistant(Minecraft minecraft) {
        if (assistantTargetFrozen) {
            return assistantTarget;
        }
        return freezeSnapshot(captureForAssistant(minecraft));
    }

    /** 纯 Java 自测试使用，不触碰 Minecraft 客户端类。 */
    static Target freezeCurrentTargetForTest() {
        return freezeSnapshot(current());
    }

    private static Target freezeSnapshot(Target snapshot) {
        assistantTarget = snapshot;
        assistantTargetFrozen = true;
        return snapshot;
    }

    /** 关闭助手或切换世界时释放快照，并让下一 tick 恢复正常识别。 */
    public static void releaseAssistantTarget() {
        assistantTargetFrozen = false;
        assistantTarget = null;
        forceClear();
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
        updateFromItemStack(new ItemStack(item), TargetSource.CROSSHAIR);
    }

    /**
     * 从识别模组公开 Accessor 的反射对象提取最终拾取物。
     *
     * <p>这里不直接引用可选模组的类，避免它缺失时客户端或 Dedicated Server 解析到
     * optional API。{@code getPickedResult()} 是识别模组 Accessor 的稳定公开入口；对
     * 伪装方块再回退到 {@code getFakeBlock()} 和 {@code getTarget().asItem()}。</p>
     */
    static void updateFromJadeAccessor(Object accessor) {
        if (assistantTargetFrozen) {
            return;
        }
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
        updateFromItemStack(stack, TargetSource.JADE);
    }

    static void updateFromItemStack(ItemStack stack) {
        updateFromItemStack(stack, TargetSource.JADE);
    }

    private static void updateFromItemStack(ItemStack stack, TargetSource targetSource) {
        if (assistantTargetFrozen) {
            return;
        }
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
        updateFromIdentifier(id.toString(), stack.getHoverName().getString(), targetSource);
    }

    static void updateFromIdentifier(String itemId, String displayName) {
        updateFromIdentifier(itemId, displayName, TargetSource.JADE);
    }

    /**
     * 记录任意 GUI 正在显示的物品 Tooltip。NeoForge 会在 FTBQ、JEI 和普通容器
     * 的 Tooltip 绘制路径上触发此事件，因此不需要把这些模组声明成硬依赖。
     */
    public static void updateFromTooltip(ItemStack stack) {
        if (assistantTargetFrozen) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        updateFromItemStack(stack, TargetSource.TOOLTIP);
        lastTooltipAt = System.currentTimeMillis();
    }

    /**
     * 从 JEI 当前悬浮 ingredient 中提取 ItemStack。JEI API 通过反射隔离，既支持
     * 物品列表覆盖层，也支持配方页面内部的 ingredient 悬浮状态。
     */
    private static boolean captureJeiHover() {
        return captureJeiHover(false);
    }

    private static boolean captureJeiHover(boolean refreshRuntime) {
        if (!ModList.get().isLoaded("jei")) {
            return false;
        }
        try {
            Object runtime = refreshRuntime
                    ? JeiRecipeNavigator.refreshRuntimeForIntegration()
                    : JeiRecipeNavigator.runtimeForIntegration();
            if (runtime == null) {
                return false;
            }
            ItemStack stack = itemStackFromTypedIngredient(
                    invokeNoArg(runtime, "getIngredientListOverlay")
            );
            if (stack.isEmpty()) {
                Object recipesGui = invokeNoArg(runtime, "getRecipesGui");
                stack = itemStackFromRecipesGui(recipesGui);
            }
            if (stack.isEmpty()) {
                return false;
            }
            updateFromItemStack(stack, TargetSource.JEI);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * FTB Library 的任务界面使用自己的 Widget/Tooltip 管线，不一定经过原生
     * RenderTooltipEvent。其公开 Widget#getIngredientUnderMouse() 会返回当前
     * PositionedIngredient，因此 FTBQ 只通过反射接入这一条轻量读取路径。
     */
    private static boolean captureFtbLibraryHover(Minecraft minecraft) {
        if (!ModList.get().isLoaded("ftblibrary") || minecraft.screen == null) {
            return false;
        }
        Method method = FTB_INGREDIENT_METHODS.computeIfAbsent(
                minecraft.screen.getClass(), type -> findMethod(type, "getIngredientUnderMouse")
        );
        if (method == null) {
            return false;
        }
        try {
            Object positioned = unwrapOptional(method.invoke(minecraft.screen));
            if (positioned == null) {
                return false;
            }
            Object tooltip = invokeNoArg(positioned, "tooltip");
            if (tooltip instanceof Boolean visible && !visible) {
                return false;
            }
            Object ingredient = invokeNoArg(positioned, "ingredient");
            if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
                updateFromItemStack(stack, TargetSource.FTB_LIBRARY);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 非 FTB Library 屏幕或版本差异不会影响其它目标来源。
        }
        return false;
    }

    private static ItemStack itemStackFromTypedIngredient(Object overlay) throws ReflectiveOperationException {
        Object typed = unwrapOptional(invokeNoArg(overlay, "getIngredientUnderMouse"));
        if (typed == null) {
            return ItemStack.EMPTY;
        }
        Object stack = unwrapOptional(invokeNoArg(typed, "getItemStack"));
        if (stack instanceof ItemStack itemStack) {
            return itemStack;
        }
        Object ingredient = invokeNoArg(typed, "getIngredient");
        return ingredient instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
    }

    private static ItemStack itemStackFromRecipesGui(Object recipesGui) throws ReflectiveOperationException {
        if (recipesGui == null) {
            return ItemStack.EMPTY;
        }
        Class<?> vanillaTypes = Class.forName("mezz.jei.api.constants.VanillaTypes");
        Object itemStackType = vanillaTypes.getField("ITEM_STACK").get(null);
        for (Method method : recipesGui.getClass().getMethods()) {
            if (!method.getName().equals("getIngredientUnderMouse") || method.getParameterCount() != 1) {
                continue;
            }
            try {
                Object typed = unwrapOptional(method.invoke(recipesGui, itemStackType));
                if (typed instanceof ItemStack itemStack) {
                    return itemStack;
                }
                if (typed != null) {
                    Object ingredient = invokeNoArg(typed, "getIngredient");
                    if (ingredient instanceof ItemStack itemStack) {
                        return itemStack;
                    }
                }
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // 继续尝试下一种 JEI 入口。
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 容器屏幕没有经过 Tooltip 事件时，读取 AbstractContainerScreen 的当前槽位。
     * FTBQ、原版容器和大多数第三方容器都继承这个基类；其它自绘 GUI 仍由
     * RenderTooltipEvent 或各自的公开 API 负责。
     */
    private static boolean captureContainerHover(Minecraft minecraft) {
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        Method method = HOVERED_SLOT_METHODS.computeIfAbsent(
                screen.getClass(), type -> findMethod(type, "getSlotUnderMouse")
        );
        if (method == null) {
            return false;
        }
        try {
            Object value = method.invoke(screen);
            if (value instanceof Slot slot && !slot.getItem().isEmpty()) {
                updateFromItemStack(slot.getItem(), TargetSource.CONTAINER);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 第三方容器屏幕的 protected API 可能被其实现隐藏，继续使用准星模式。
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name);
        if (method == null) {
            return null;
        }
        return method.invoke(target);
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static void updateFromIdentifier(String itemId, String displayName, TargetSource targetSource) {
        if (assistantTargetFrozen) {
            return;
        }
        if (itemId == null || itemId.isBlank()) {
            clear();
            return;
        }
        current = new Target(
                itemId,
                displayName == null || displayName.isBlank() ? itemId : displayName,
                System.currentTimeMillis()
        );
        source = targetSource == null ? TargetSource.JADE : targetSource;
    }

    static void clear() {
        if (assistantTargetFrozen) {
            return;
        }
        forceClear();
    }

    private static void forceClear() {
        current = null;
        source = TargetSource.NONE;
        lastTooltipAt = 0L;
    }

    public static Target current() {
        if (assistantTargetFrozen) {
            return assistantTarget;
        }
        Target target = current;
        if (target == null || System.currentTimeMillis() - target.observedAt() > EXPIRATION_MS) {
            current = null;
            source = TargetSource.NONE;
            return null;
        }
        return target;
    }

    private static boolean isRecentSource(TargetSource expected) {
        return source == expected && current() != null;
    }

    private static boolean isRecentTooltip() {
        return lastTooltipAt > 0L && System.currentTimeMillis() - lastTooltipAt <= EXPIRATION_MS;
    }

    private static boolean isRecentJadeTarget() {
        return ModList.get().isLoaded("jade")
                && JadeClientBridge.isRegistered()
                && source == TargetSource.JADE
                && current() != null;
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

    private enum TargetSource {
        NONE,
        CROSSHAIR,
        JADE,
        TOOLTIP,
        JEI,
        CONTAINER,
        FTB_LIBRARY
    }
}
