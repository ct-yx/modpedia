package io.ctyx.modpedia.knowledge;

import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.lang.reflect.Method;

/** 防止把 FML 生命周期事件误注册到 Forge EventBus。 */
public final class LegacyFtbQuestEventBridgeSelfTest {
    private LegacyFtbQuestEventBridgeSelfTest() {
    }

    public static void main(String[] args) {
        for (Method method : LegacyFtbQuestEventBridge.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(SubscribeEvent.class)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            assertTrue(parameters.length == 1, method.getName() + " parameter count");
            assertTrue(Event.class.isAssignableFrom(parameters[0]),
                    method.getName() + " must receive a Forge Event");
        }
        System.out.println("LegacyFtbQuestEventBridgeSelfTest: OK");
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
