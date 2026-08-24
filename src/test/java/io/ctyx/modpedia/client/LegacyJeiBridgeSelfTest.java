package io.ctyx.modpedia.client;

import java.util.Collections;
import java.util.Optional;

/** 验证 1.12.2 JEI/HEI runtime 入口的兼容解析，不启动 Minecraft 或真实 JEI GUI。 */
public final class LegacyJeiBridgeSelfTest {
    private LegacyJeiBridgeSelfTest() {
    }

    public static void main(String[] args) {
        Object current = new Object();
        RuntimeOwner.getJeiRuntimeValue = current;
        RuntimeOwner.getRuntimeValue = new Object();
        RuntimeOwner.optionalValue = Optional.of(new Object());
        check(LegacyJeiBridge.runtimeFrom(Collections.<Class<?>>singletonList(RuntimeOwner.class)) == current,
                "getJeiRuntime 应优先于旧入口");

        RuntimeOwner.getJeiRuntimeValue = null;
        Object legacy = new Object();
        RuntimeOwner.getRuntimeValue = legacy;
        check(LegacyJeiBridge.runtimeFrom(Collections.<Class<?>>singletonList(RuntimeOwner.class)) == legacy,
                "1.12.2 JEI/HEI 的 getRuntime 应可用");

        RuntimeOwner.getRuntimeValue = null;
        Object optional = new Object();
        RuntimeOwner.optionalValue = Optional.of(optional);
        check(LegacyJeiBridge.runtimeFrom(Collections.<Class<?>>singletonList(RuntimeOwner.class)) == optional,
                "Optional runtime 入口应正确解包");

        RuntimeOwner.optionalValue = Optional.empty();
        check(LegacyJeiBridge.runtimeFrom(Collections.<Class<?>>singletonList(RuntimeOwner.class)) == null,
                "runtime 未初始化时应返回 null");
        check(LegacyJeiBridge.runtimeFrom(Collections.<Class<?>>emptyList()) == null,
                "没有候选入口时应返回 null");

        check("tconstruct:tools/pattern".equals(LegacyJeiBridge.recipeIdForTest(
                        "[[recipe:tconstruct:tools/pattern|tconstruct:tools/pattern]]")),
                "recipe token 应只提取配方注册 ID");
        check("example:bee".equals(LegacyJeiBridge.recipeIdForTest("example:bee|显示文本")),
                "旧文本配方字段应兼容显示文本分隔符");

        System.out.println("LegacyJeiBridgeSelfTest passed");
    }

    public static final class RuntimeOwner {
        private static Object getJeiRuntimeValue;
        private static Object getRuntimeValue;
        private static Optional<Object> optionalValue = Optional.empty();

        private RuntimeOwner() {
        }

        public static Object getJeiRuntime() {
            return getJeiRuntimeValue;
        }

        public static Object getRuntime() {
            return getRuntimeValue;
        }

        public static Optional<Object> getOptionalJeiRuntime() {
            return optionalValue;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
