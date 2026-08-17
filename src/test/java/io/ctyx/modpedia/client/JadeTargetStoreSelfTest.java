package io.ctyx.modpedia.client;

/** 视线识别目标提取的纯客户端类回归测试，不启动识别模组或真实模型。 */
public final class JadeTargetStoreSelfTest {
    private JadeTargetStoreSelfTest() {
    }

    public static void main(String[] args) {
        JadeTargetStore.clear();

        JadeTargetStore.updateFromIdentifier("minecraft:iron_ingot", "铁锭");
        JadeTargetStore.Target item = JadeTargetStore.current();
        check(item != null, "识别回调应保存物品目标");
        check("minecraft:iron_ingot".equals(item.itemId()), "目标应保存稳定物品 ID");
        check(!item.displayName().isBlank(), "目标应保存可显示名称");

        JadeTargetStore.updateFromIdentifier("minecraft:stone", "石头");
        JadeTargetStore.Target block = JadeTargetStore.current();
        check(block != null && "minecraft:stone".equals(block.itemId()),
                "没有拾取物时应回退到识别目标方块的物品 ID");

        JadeTargetStore.updateFromIdentifier("", "");
        check(JadeTargetStore.current() == null, "空目标标识应清理旧目标");

        System.out.println("ModPedia view-target integration self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
