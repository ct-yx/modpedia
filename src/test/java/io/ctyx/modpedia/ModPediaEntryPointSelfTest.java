package io.ctyx.modpedia;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.lang.reflect.Constructor;
import java.util.Arrays;

/** Forge 1.20.1 模组入口构造器回归检查。 */
public final class ModPediaEntryPointSelfTest {
    private ModPediaEntryPointSelfTest() {
    }

    public static void main(String[] args) {
        Constructor<?>[] constructors = ModPedia.class.getDeclaredConstructors();
        boolean forgeEntryPoint = Arrays.stream(constructors)
                .anyMatch(constructor -> Arrays.equals(
                        constructor.getParameterTypes(),
                        new Class<?>[]{FMLJavaModLoadingContext.class}
                ));
        check(forgeEntryPoint,
                "Forge 1.20.1 入口必须接收 FMLJavaModLoadingContext");
        check(Arrays.stream(constructors).noneMatch(constructor ->
                        constructor.getParameterCount() == 2),
                "Forge 1.20.1 入口不得使用加载器不识别的双参数构造器");
        System.out.println("ModPedia Forge entry point self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
