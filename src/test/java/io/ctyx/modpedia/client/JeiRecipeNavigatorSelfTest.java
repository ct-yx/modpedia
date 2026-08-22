package io.ctyx.modpedia.client;

import java.util.List;
import java.util.Optional;

/** JEI 当前 Internal 入口和通用运行时形态的纯 Java 反射回归测试。 */
public final class JeiRecipeNavigatorSelfTest {
    private JeiRecipeNavigatorSelfTest() {
    }

    public static void main(String[] args) {
        Object currentRuntime = new Object();
        CurrentInternal.runtime = currentRuntime;
        CurrentInternal.optionalRuntime = Optional.empty();
        check(
                JeiRecipeNavigator.runtimeFrom(List.of(CurrentInternal.class, LegacyRuntimeOwner.class)) == currentRuntime,
                "JEI 当前 Internal.getJeiRuntime() 应优先解析"
        );

        Object optionalRuntime = new Object();
        CurrentInternal.runtime = null;
        CurrentInternal.optionalRuntime = Optional.of(optionalRuntime);
        check(
                JeiRecipeNavigator.runtimeFrom(List.of(CurrentInternal.class)) == optionalRuntime,
                "JEI Optional runtime 入口应解包"
        );

        Object legacyRuntime = new Object();
        LegacyRuntimeOwner.runtime = legacyRuntime;
        check(
                JeiRecipeNavigator.runtimeFrom(List.of(LegacyRuntimeOwner.class)) == legacyRuntime,
                "通用 runtime owner 入口应继续兼容"
        );

        check(
                JeiRecipeNavigator.runtimeFrom(List.of()) == null,
                "没有 JEI 入口时应返回 null"
        );
        System.out.println("ModPedia JEI runtime navigation self-test passed");
    }

    public static final class CurrentInternal {
        private static Object runtime;
        private static Optional<Object> optionalRuntime = Optional.empty();

        private CurrentInternal() {
        }

        public static Object getJeiRuntime() {
            return runtime;
        }

        public static Optional<Object> getOptionalJeiRuntime() {
            return optionalRuntime;
        }
    }

    public static final class LegacyRuntimeOwner {
        private static Object runtime;

        private LegacyRuntimeOwner() {
        }

        public static Object getRuntime() {
            return runtime;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
