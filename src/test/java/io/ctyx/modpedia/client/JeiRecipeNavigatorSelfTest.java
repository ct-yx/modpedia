package io.ctyx.modpedia.client;

import java.util.List;
import java.util.Optional;

/** JEI 当前 Internal 入口和旧入口的纯 Java 反射回归测试。 */
public final class JeiRecipeNavigatorSelfTest {
    private JeiRecipeNavigatorSelfTest() {
    }

    public static void main(String[] args) {
        Object currentRuntime = new Object();
        CurrentInternal.runtime = currentRuntime;
        CurrentInternal.optionalRuntime = Optional.empty();
        check(
                JeiRecipeNavigator.runtimeFrom(List.of(CurrentInternal.class, LegacyApi.class)) == currentRuntime,
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
        LegacyApi.runtime = legacyRuntime;
        check(
                JeiRecipeNavigator.runtimeFrom(List.of(LegacyApi.class)) == legacyRuntime,
                "旧 JEI runtime 入口应继续兼容"
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

    public static final class LegacyApi {
        private static Object runtime;

        private LegacyApi() {
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
