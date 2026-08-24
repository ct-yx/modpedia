package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 可选 JEI/同 API 实现桥接；类不存在时始终返回不可用而不阻止启动。 */
public final class LegacyJeiBridge {
    private static final String INTERNAL_CLASS = "mezz.jei.Internal";
    private static final String[] RUNTIME_METHODS = {
            // 当前 1.12.2 JEI/HEI 使用 getRuntime；保留新入口以便同一适配层
            // 面对回移版本时仍能工作。
            "getJeiRuntime", "getRuntime", "getOptionalJeiRuntime"
    };
    private static final String[] RUNTIME_FIELDS = {"jeiRuntime", "runtime"};
    private static volatile Object cachedRuntime;
    private static volatile long lastRuntimeLookupAt;

    private LegacyJeiBridge() {
    }

    public static boolean isAvailable() {
        return findJeiClass() != null;
    }

    /** 左键物品令牌时打开输出配方；配方界面关闭后 Minecraft 会回到助手。 */
    public static boolean showItem(String itemValue) {
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(itemValue);
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(identity.getItemId()));
            if (item == null) {
                return false;
            }
            int metadata = identity.getMetadata() < 0 ? 0 : identity.getMetadata();
            ItemStack stack = new ItemStack(item, 1, metadata);
            return showStack(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 打开手册页中的 recipe 注册 ID。
     *
     * <p>1.12.2 Patchouli 的 {@code recipe} 字段保存的是配方 ID，例如
     * {@code tconstruct:tools/pattern}，它不是物品 ID，不能直接交给
     * {@link #showItem(String)}。先从 Forge 配方注册表取输出物品，再用同一
     * 个 JEI/HEI focus 入口打开，兼容普通合成和 TConstruct 的自定义配方。</p>
     */
    public static boolean showRecipe(String recipeValue) {
        try {
            String recipeId = recipeId(recipeValue);
            if (recipeId.isEmpty()) {
                return false;
            }
            IRecipe recipe = ForgeRegistries.RECIPES.getValue(new ResourceLocation(recipeId));
            if (recipe != null) {
                ItemStack output = recipe.getRecipeOutput();
                if (output != null && !output.isEmpty() && showStack(output)) {
                    return true;
                }
            }
            // 少数来源把输出物品 ID 放在 recipe 字段中；这条回退不影响标准
            // 配方 ID，并使旧版自定义手册仍可使用。
            return showItem(recipeId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String recipeIdForTest(String value) {
        return recipeId(value);
    }

    private static String recipeId(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.regionMatches(true, 0, "[[recipe:", 0, "[[recipe:".length())
                && text.endsWith("]]")) {
            text = text.substring("[[recipe:".length(), text.length() - 2);
        }
        int separator = text.indexOf('|');
        if (separator >= 0) {
            text = text.substring(0, separator);
        }
        return text.trim();
    }

    private static boolean showStack(ItemStack stack) {
        try {
            Object runtime = runtimeForIntegration();
            if (runtime != null && openWithRuntime(runtime, stack)) {
                return true;
            }
            // JEI/HEI 在客户端初始化完成后才注入 runtime。首次点击可能刚好
            // 发生在注入前，因此失败后立即丢弃缓存并重新解析一次，避免用户
            // 必须再次点击。
            runtime = refreshRuntimeForIntegration();
            return runtime != null && openWithRuntime(runtime, stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openWithRuntime(Object runtime, ItemStack stack) throws Exception {
        Object registry = invokeNoArg(runtime, "getRecipeRegistry");
        Object recipesGui = invokeNoArg(runtime, "getRecipesGui");
        if (registry == null || recipesGui == null) {
            return false;
        }
        Class<?> modeClass = loadClass("mezz.jei.api.recipe.IFocus$Mode");
        if (modeClass == null) {
            return false;
        }
        Object outputMode = Enum.valueOf((Class) modeClass, "OUTPUT");
        Method createFocus = findMethod(registry.getClass(), "createFocus", 2);
        Object focus = createFocus.invoke(registry, outputMode, stack);
        if (focus == null) {
            return false;
        }
        findMethod(recipesGui.getClass(), "show", 1).invoke(recipesGui, focus);
        return true;
    }

    /**
     * 1.12.2 JEI 和 HadEnoughItems 共用 mezz.jei.* API，但运行时注入时机不固定。
     * 这里仅缓存运行时对象，不缓存失败的类解析结果，避免切换世界或重载后持有
     * 已关闭的 RecipesGui。
     */
    static Object runtimeForIntegration() {
        long now = System.currentTimeMillis();
        Object runtime = cachedRuntime;
        if (now - lastRuntimeLookupAt < 1_000L) {
            return runtime;
        }
        lastRuntimeLookupAt = now;
        runtime = runtimeFrom(Collections.singletonList(findJeiClass()));
        cachedRuntime = runtime;
        return runtime;
    }

    static Object refreshRuntimeForIntegration() {
        lastRuntimeLookupAt = 0L;
        cachedRuntime = null;
        return runtimeForIntegration();
    }

    /** 供无 Minecraft 的纯 Java 回归验证 runtime 入口优先级和 Optional 解包。 */
    static Object runtimeFrom(List<Class<?>> owners) {
        for (Class<?> owner : owners) {
            if (owner == null) {
                continue;
            }
            for (String methodName : RUNTIME_METHODS) {
                Method method = findMethodOrNull(owner, methodName, 0);
                if (method == null || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = unwrapOptional(method.invoke(null));
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 继续尝试兼容入口；缺失 runtime 不是启动错误。
                }
            }
            for (String fieldName : RUNTIME_FIELDS) {
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
                } catch (Throwable ignored) {
                    // 某些版本只保留方法入口。
                }
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional ? ((Optional<?>) value).orElse(null) : value;
    }

    private static Class<?> findJeiClass() {
        return loadClass(INTERNAL_CLASS);
    }

    private static Class<?> loadClass(String name) {
        List<ClassLoader> loaders = new ArrayList<ClassLoader>();
        addLoader(loaders, Thread.currentThread().getContextClassLoader());
        addLoader(loaders, LegacyJeiBridge.class.getClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                // 尝试下一个 Minecraft/LaunchWrapper 类加载器。
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addLoader(List<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null && !loaders.contains(loader)) {
            loaders.add(loader);
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
                Object runtime = runtimeForIntegration();
                Object registry = runtime == null ? null : invokeNoArg(runtime, "getRecipeRegistry");
                if (registry == null) {
                    payload.addProperty("message", "JEI 尚未完成客户端初始化");
                    payload.add("methods", methods);
                    payload.add("recipes", recipes);
                    payload.add("machines", machines);
                    response.add("recipe_response", payload);
                    return response;
                }
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

    private static Object invokeNoArg(Object object, String name) throws Exception {
        return findMethod(object.getClass(), name, 0).invoke(object);
    }

    private static Method findMethodOrNull(Class<?> type, String name, int count) {
        try {
            return findMethod(type, name, count);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
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
