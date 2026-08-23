package io.ctyx.modpedia;

/** Forge 1.20.1 按键事件顺序回归：避免 ScreenEvent 与 InputEvent 双重切换助手。 */
public final class ModPediaClientEventsSelfTest {
    private ModPediaClientEventsSelfTest() {
    }

    public static void main(String[] args) {
        check(ModPediaClientEvents.shouldHandleRawKey(false),
                "无 Screen 的游戏画面应由原始输入打开助手");
        check(!ModPediaClientEvents.shouldHandleRawKey(true),
                "已有 Screen 时原始输入不得再次切换助手");
        System.out.println("ModPedia Forge client key event self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
