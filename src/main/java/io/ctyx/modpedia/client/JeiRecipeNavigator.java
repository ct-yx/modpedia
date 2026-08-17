package io.ctyx.modpedia.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JEI 可选联动；所有 API 访问均反射隔离，缺失或版本变化时返回 false。 */
public final class JeiRecipeNavigator {
    private JeiRecipeNavigator() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded("jei");
    }

    public static boolean open(String itemId) {
        if (!isAvailable() || itemId == null || itemId.isBlank()) {
            return false;
        }
        try {
            ItemStack stack = stack(itemId);
            if (stack.isEmpty()) {
                return false;
            }
            Object runtime = runtime();
            if (runtime == null) {
                return false;
            }
            Object recipesGui = invokeNoArg(runtime, "getRecipesGui");
            if (recipesGui == null) {
                return false;
            }

            Object focus = createFocus(runtime, stack);
            if (focus != null && invokeListMethod(recipesGui, List.of(focus))) {
                return true;
            }
            return invokeListMethod(recipesGui, List.of(stack));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ItemStack stack(String itemId) {
        ResourceLocation location = ResourceLocation.parse(itemId.strip());
        Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static Object runtime() {
        List<Class<?>> owners = new ArrayList<>(2);
        // JEI 19.x exposes the runtime through Internal. The old public entry
        // point is kept as a fallback for older JEI builds.
        for (String className : List.of("mezz.jei.common.Internal", "mezz.jei.api.JeiApi")) {
            try {
                owners.add(Class.forName(className));
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return runtimeFrom(owners);
    }

    /**
     * Resolves a JEI runtime from the candidate owner classes.
     *
     * Package-private so the pure Java regression test can exercise the
     * current and legacy reflection contracts without loading Minecraft.
     */
    static Object runtimeFrom(List<Class<?>> owners) {
        for (Class<?> owner : owners) {
            if (owner == null) {
                continue;
            }
            for (String methodName : List.of("getJeiRuntime", "getOptionalJeiRuntime", "getRuntime")) {
                Method method = find(owner, methodName, 0);
                if (method == null || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = unwrapOptional(method.invoke(null));
                    if (value != null) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            for (String fieldName : List.of("jeiRuntime", "runtime")) {
                try {
                    Field field = owner.getDeclaredField(fieldName);
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = unwrapOptional(field.get(null));
                    if (value != null) {
                        return value;
                    }
                } catch (NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Object createFocus(Object runtime, ItemStack stack) {
        try {
            Object helpers = invokeNoArg(runtime, "getJeiHelpers");
            Object factory = helpers == null ? null : invokeNoArg(helpers, "getFocusFactory");
            if (factory == null) {
                return null;
            }
            Class<?> roleType = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
            Object role = Enum.valueOf((Class<? extends Enum>) roleType.asSubclass(Enum.class), "OUTPUT");
            Class<?> vanillaTypes = Class.forName("mezz.jei.api.constants.VanillaTypes");
            Field itemStackField = vanillaTypes.getField("ITEM_STACK");
            Object ingredientType = itemStackField.get(null);
            for (Method method : factory.getClass().getMethods()) {
                if (!method.getName().equals("createFocus") || method.getParameterCount() != 3) {
                    continue;
                }
                try {
                    return method.invoke(factory, role, ingredientType, stack);
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static boolean invokeListMethod(Object target, List<?> values) {
        for (Method method : target.getClass().getMethods()) {
            if (!(method.getName().equals("show") || method.getName().equals("showRecipes"))
                    || method.getParameterCount() != 1
                    || !List.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            try {
                method.invoke(target, values);
                return true;
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            }
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = find(target.getClass(), name, 0);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Method find(Class<?> type, String name, int count) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == count) {
                    return method;
                }
            }
        }
        return null;
    }
}
