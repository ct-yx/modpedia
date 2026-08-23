package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可选 JEI/同 API 实现桥接；类不存在时始终返回不可用而不阻止启动。 */
public final class LegacyJeiBridge {
    private LegacyJeiBridge() {
    }

    public static boolean isAvailable() {
        try {
            return Class.forName("mezz.jei.Internal", false, LegacyJeiBridge.class.getClassLoader())
                    .getMethod("getRuntime") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Shift+左键物品令牌时打开输出配方；配方界面关闭后 Minecraft 会回到助手。 */
    public static boolean showItem(String itemValue) {
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(itemValue);
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(identity.getItemId()));
            if (item == null) {
                return false;
            }
            int metadata = identity.getMetadata() < 0 ? 0 : identity.getMetadata();
            ItemStack stack = new ItemStack(item, 1, metadata);
            Class<?> internal = Class.forName("mezz.jei.Internal");
            Object runtime = internal.getMethod("getRuntime").invoke(null);
            if (runtime == null) {
                return false;
            }
            Object registry = runtime.getClass().getMethod("getRecipeRegistry").invoke(runtime);
            Class<?> modeClass = Class.forName("mezz.jei.api.recipe.IFocus$Mode");
            Object outputMode = Enum.valueOf((Class) modeClass, "OUTPUT");
            Method createFocus = findMethod(registry.getClass(), "createFocus", 2);
            Object focus = createFocus.invoke(registry, outputMode, stack);
            Object recipesGui = runtime.getClass().getMethod("getRecipesGui").invoke(runtime);
            findMethod(recipesGui.getClass(), "show", 1).invoke(recipesGui, focus);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Worker 请求配方时返回结构化的 JEI 结果。JEI 1.12 的公开 API 能稳定提供
     * 分类、输出和布局中的输入/输出；处理时间等非公开信息不伪造，缺少时由
     * Worker 标记为未知。
     */
    public static JsonObject queryResponse(String requestId, JsonObject request) {
        JsonObject response = message("recipe_query_response", requestId);
        JsonObject payload = new JsonObject();
        JsonObject query = request == null || !request.has("query") || !request.get("query").isJsonObject()
                ? new JsonObject() : request.getAsJsonObject("query");
        String itemId = value(query, "item_id");
        payload.addProperty("item_id", itemId);
        payload.addProperty("mode", value(query, "mode"));
        payload.addProperty("status", "unavailable");
        payload.addProperty("item_name", "");
        JsonArray methods = new JsonArray();
        JsonArray recipes = new JsonArray();
        JsonArray machines = new JsonArray();
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(itemId);
            Item target = ForgeRegistries.ITEMS.getValue(new ResourceLocation(identity.getItemId()));
            if (target == null || !isAvailable()) {
                payload.addProperty("message", "JEI 未安装或物品未注册");
            } else {
                ItemStack targetStack = new ItemStack(target, 1,
                        identity.getMetadata() < 0 ? 0 : identity.getMetadata());
                Object runtime = Class.forName("mezz.jei.Internal").getMethod("getRuntime").invoke(null);
                Object registry = runtime.getClass().getMethod("getRecipeRegistry").invoke(runtime);
                List<?> categories = list(invoke(registry, "getRecipeCategories"));
                Set<String> seenMethods = new HashSet<String>();
                int limit = intValue(query, "limit", 8);
                for (Object category : categories) {
                    if (category == null) {
                        continue;
                    }
                    List<?> outputs = list(invoke(registry, "getCraftingItems", category));
                    if (!containsStack(outputs, targetStack)) {
                        continue;
                    }
                    String methodId = string(invoke(category, "getUid"));
                    String methodName = string(invoke(category, "getTitle"));
                    String machine = string(invoke(category, "getModName"));
                    if (seenMethods.add(methodId)) {
                        JsonObject method = new JsonObject();
                        method.addProperty("method_id", methodId);
                        method.addProperty("name", methodName);
                        method.addProperty("recipe_count", list(invoke(registry, "getRecipeWrappers", category)).size());
                        JsonArray methodMachines = new JsonArray();
                        if (!machine.isEmpty()) {
                            methodMachines.add(machine);
                            if (!contains(machines, machine)) {
                                machines.add(machine);
                            }
                        }
                        method.add("machines", methodMachines);
                        methods.add(method);
                    }
                    List<?> wrappers = list(invoke(registry, "getRecipeWrappers", category));
                    for (Object wrapper : wrappers) {
                        if (recipes.size() >= limit) {
                            break;
                        }
                        JsonObject recipe = layoutRecipe(registry, category, wrapper, methodId, methodName);
                        if (recipe != null) {
                            recipes.add(recipe);
                        }
                    }
                    payload.addProperty("status", "ok");
                    payload.addProperty("item_name", targetStack.getDisplayName());
                    if (recipes.size() >= limit) {
                        break;
                    }
                }
                if (methods.size() == 0) {
                    payload.addProperty("message", "JEI 中没有找到该物品的输出配方");
                }
                payload.addProperty("has_more", recipes.size() >= limit);
            }
        } catch (Throwable failure) {
            payload.addProperty("message", "JEI 配方查询暂不可用");
        }
        payload.add("methods", methods);
        payload.add("recipes", recipes);
        payload.add("machines", machines);
        response.add("recipe_response", payload);
        return response;
    }

    private static JsonObject layoutRecipe(
            Object registry,
            Object category,
            Object wrapper,
            String methodId,
            String methodName
    ) {
        try {
            Method createLayout = findMethod(registry.getClass(), "createRecipeLayoutDrawable", 3);
            Object layout = createLayout.invoke(registry, category, wrapper, null);
            Object group = invoke(layout, "getItemStacks");
            Object guiIngredients = invoke(group, "getGuiIngredients");
            if (!(guiIngredients instanceof Map)) {
                return null;
            }
            JsonArray inputs = new JsonArray();
            JsonArray outputs = new JsonArray();
            for (Object value : ((Map<?, ?>) guiIngredients).values()) {
                boolean input = Boolean.TRUE.equals(invoke(value, "isInput"));
                Object displayed = invoke(value, "getDisplayedIngredient");
                JsonArray target = input ? inputs : outputs;
                addIngredient(target, displayed);
            }
            JsonObject recipe = new JsonObject();
            recipe.addProperty("recipe_id", methodId + ":" + Integer.toHexString(wrapper.hashCode()));
            recipe.addProperty("method_id", methodId);
            recipe.addProperty("method_name", methodName);
            recipe.add("inputs", inputs);
            recipe.add("outputs", outputs);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("jei_wrapper", String.valueOf(wrapper));
            recipe.add("metadata", metadata);
            return recipe;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addIngredient(JsonArray values, Object ingredient) {
        if (!(ingredient instanceof ItemStack)) {
            return;
        }
        ItemStack stack = (ItemStack) ingredient;
        if (stack.isEmpty() || stack.getItem() == null || stack.getItem().getRegistryName() == null) {
            return;
        }
        JsonObject value = new JsonObject();
        value.addProperty("kind", "item");
        value.addProperty("id", stack.getItem().getRegistryName().toString()
                + (stack.getMetadata() == 0 ? "" : "@" + stack.getMetadata()));
        value.addProperty("display_name", stack.getDisplayName());
        value.addProperty("amount", stack.getCount());
        values.add(value);
    }

    private static boolean containsStack(List<?> values, ItemStack target) {
        for (Object value : values) {
            if (value instanceof ItemStack) {
                ItemStack stack = (ItemStack) value;
                if (stack.getItem() == target.getItem() && stack.getMetadata() == target.getMetadata()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(JsonArray values, String text) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && text.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    private static Object invoke(Object object, String name, Object... args) throws Exception {
        if (object == null) {
            return null;
        }
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                return method.invoke(object, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Method findMethod(Class<?> type, String name, int count) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == count) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static JsonObject message(String type, String requestId) {
        JsonObject value = new JsonObject();
        value.addProperty("protocol_version", 1);
        value.addProperty("type", type);
        value.addProperty("request_id", requestId == null ? "" : requestId);
        value.addProperty("conversation_id", "");
        return value;
    }

    private static String value(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return Integer.parseInt(value(object, key));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
