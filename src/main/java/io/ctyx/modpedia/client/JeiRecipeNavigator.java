package io.ctyx.modpedia.client;

import io.ctyx.modpedia.recipe.RecipeEntry;
import io.ctyx.modpedia.recipe.RecipeIngredient;
import io.ctyx.modpedia.recipe.RecipeMachineNormalizer;
import io.ctyx.modpedia.recipe.RecipeMethod;
import io.ctyx.modpedia.recipe.RecipeQuery;
import io.ctyx.modpedia.recipe.RecipeQueryMode;
import io.ctyx.modpedia.recipe.RecipeResponse;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** JEI 可选联动；所有 API 访问均反射隔离，缺失或版本变化时返回 false。 */
public final class JeiRecipeNavigator {
    private static volatile Object cachedRuntime;
    private static volatile long lastRuntimeLookupAt;

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
            Object runtime = runtimeForIntegration();
            if (runtime == null) {
                return false;
            }
            Object recipesGui = invokeNoArg(runtime, "getRecipesGui");
            if (recipesGui == null) {
                return false;
            }

            Object focus = createFocus(runtime, stack);
            if (focus == null) {
                return false;
            }
            // JEI 19.x 的 IRecipesGui.show(List) 接收的是 IFocus 列表，不能把
            // ItemStack 直接塞进去；优先调用 default show(IFocus)，旧版本再退回
            // 到 show(List<IFocus<?>>)。
            return invokeFocusMethod(recipesGui, focus)
                    || invokeListMethod(recipesGui, List.of(focus));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 在客户端运行时读取 JEI 的当前配方，不把配方写入 knowledge.db。
     *
     * <p>这里故意只依赖反射：JEI 是可选联动，Dedicated Server 和未安装 JEI 的
     * 客户端仍然可以加载 ModPedia。返回值已经是通用协议对象，Worker 不会接触
     * JEI 或 Minecraft 类型。</p>
     */
    public static RecipeResponse query(RecipeQuery query) {
        if (query == null) {
            return RecipeResponse.invalid(null, "配方查询请求为空");
        }
        if (!isAvailable()) {
            return RecipeResponse.unavailable(query, "JEI 未安装或尚未初始化");
        }
        try {
            ItemStack stack = stack(query.itemId());
            if (stack.isEmpty()) {
                return RecipeResponse.invalid(query, "未找到物品：" + query.itemId());
            }
            Object runtime = runtimeForIntegration();
            Object manager = runtime == null ? null : invokeNoArg(runtime, "getRecipeManager");
            Object focus = runtime == null ? null : createFocus(runtime, stack);
            if (manager == null || focus == null) {
                return RecipeResponse.unavailable(query, "JEI 配方管理器尚未就绪");
            }
            String itemName = stack.getHoverName().getString();
            return switch (query.mode()) {
                case WORKBENCH -> queryDirect(
                        manager, focus, stack, query, "CRAFTING", "工作台合成"
                );
                case FURNACE -> queryDirect(
                        manager, focus, stack, query, "SMELTING", "熔炉烧炼"
                );
                case DETAIL -> queryDetail(manager, focus, stack, query);
                case OTHER -> queryMethods(manager, focus, stack, query, itemName);
            };
        } catch (Throwable failure) {
            return RecipeResponse.unavailable(query, "JEI 配方查询失败：" + messageOf(failure));
        }
    }

    private static RecipeResponse queryDirect(
            Object manager,
            Object focus,
            ItemStack stack,
            RecipeQuery query,
            String typeField,
            String displayName
    ) throws ReflectiveOperationException {
        Object recipeType = recipeType(typeField);
        if (recipeType == null) {
            return RecipeResponse.unavailable(query, "当前 JEI 版本没有 " + displayName + " 类型");
        }
        Object category = recipeCategory(manager, recipeType, focus);
        List<?> rawRecipes = lookupRecipes(manager, recipeType, focus, query.limit() + 1);
        boolean hasMore = rawRecipes.size() > query.limit();
        List<?> selected = trim(rawRecipes, query.limit());
        String categoryTitle = categoryTitle(category);
        String methodId = recipeTypeId(recipeType);
        List<RecipeEntry> recipes = new ArrayList<>();
        for (Object rawRecipe : selected) {
            recipes.add(toRecipeEntry(
                    manager,
                    category,
                    rawRecipe,
                    recipeType,
                    methodId,
                    displayName,
                    stack,
                    typeField.equals("SMELTING"),
                    List.of(),
                    categoryTitle
            ));
        }
        if (recipes.isEmpty()) {
            return new RecipeResponse(
                    "not_found",
                    query.itemId(),
                    stack.getHoverName().getString(),
                    query.mode(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    "没有找到可用的 " + displayName + " 配方"
            );
        }
        return new RecipeResponse(
                "ok",
                query.itemId(),
                stack.getHoverName().getString(),
                query.mode(),
                List.of(),
                recipes,
                // 工作台和熔炉结果只显示配方类别，不把处理机器当作机器列表返回。
                List.of(),
                hasMore,
                "已返回 " + displayName + " 配方"
        );
    }

    private static RecipeResponse queryMethods(
            Object manager,
            Object focus,
            ItemStack stack,
            RecipeQuery query,
            String itemName
    ) throws ReflectiveOperationException {
        List<?> categories = categoriesForFocus(manager, focus, RecipeQuery.MAX_LIMIT + 1);
        List<RecipeMethod> methods = new ArrayList<>();
        boolean hasMore = false;
        for (Object category : categories) {
            Object recipeType = invokeNoArg(category, "getRecipeType");
            if (recipeType == null || isDirectType(recipeType)) {
                continue;
            }
            String methodId = recipeTypeId(recipeType);
            if (methodId.isBlank()) {
                continue;
            }
            List<?> recipes = lookupRecipes(manager, recipeType, focus, query.limit() + 1);
            if (recipes.isEmpty()) {
                continue;
            }
            if (methods.size() >= query.limit()) {
                hasMore = true;
                break;
            }
            List<String> machines = catalystNames(manager, recipeType);
            methods.add(new RecipeMethod(
                    methodId,
                    categoryTitle(category).isBlank() ? methodId : categoryTitle(category),
                    recipes.size() > query.limit() ? query.limit() + 1 : recipes.size(),
                    machines
            ));
        }
        return new RecipeResponse(
                methods.isEmpty() ? "not_found" : "ok",
                query.itemId(),
                itemName,
                query.mode(),
                methods,
                List.of(),
                List.of(),
                hasMore,
                methods.isEmpty() ? "没有找到其它可用的处理方式" : "请选择一个 method_id 再查询具体配方"
        );
    }

    private static RecipeResponse queryDetail(
            Object manager,
            Object focus,
            ItemStack stack,
            RecipeQuery query
    ) throws ReflectiveOperationException {
        Object category = null;
        Object recipeType = null;
        for (Object candidate : categoriesForFocus(manager, focus, RecipeQuery.MAX_LIMIT + 1)) {
            Object candidateType = invokeNoArg(candidate, "getRecipeType");
            if (query.methodId().equals(recipeTypeId(candidateType))) {
                category = candidate;
                recipeType = candidateType;
                break;
            }
        }
        if (category == null || recipeType == null) {
            return new RecipeResponse(
                    "not_found",
                    query.itemId(),
                    stack.getHoverName().getString(),
                    query.mode(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    "没有找到 method_id 对应的 JEI 处理方式：" + query.methodId()
            );
        }
        List<String> machines = catalystNames(manager, recipeType);
        List<?> rawRecipes = lookupRecipes(manager, recipeType, focus, query.limit() + 1);
        boolean hasMore = rawRecipes.size() > query.limit();
        List<RecipeEntry> recipes = new ArrayList<>();
        String methodName = categoryTitle(category);
        for (Object rawRecipe : trim(rawRecipes, query.limit())) {
            recipes.add(toRecipeEntry(
                    manager,
                    category,
                    rawRecipe,
                    recipeType,
                    query.methodId(),
                    methodName,
                    stack,
                    false,
                    machines,
                    methodName
            ));
        }
        return new RecipeResponse(
                recipes.isEmpty() ? "not_found" : "ok",
                query.itemId(),
                stack.getHoverName().getString(),
                query.mode(),
                List.of(new RecipeMethod(query.methodId(), methodName, recipes.size(), machines)),
                recipes,
                machines,
                hasMore,
                recipes.isEmpty() ? "该处理方式没有返回具体配方" : "已返回具体配方和可用机器"
        );
    }

    private static RecipeEntry toRecipeEntry(
            Object manager,
            Object category,
            Object rawRecipe,
            Object recipeType,
            String methodId,
            String methodName,
            ItemStack target,
            boolean furnace,
            List<String> machines,
            String categoryTitle
    ) {
        Object recipeValue = unwrapOptional(invokeOrNull(rawRecipe, "value"));
        if (recipeValue == null) {
            recipeValue = rawRecipe;
        }
        String recipeId = firstText(
                invokeOrNull(rawRecipe, "id"),
                invokeOrNull(rawRecipe, "getId"),
                invokeOrNull(category, "getRegistryName"),
                recipeValue == null ? null : recipeValue.getClass().getName()
        );
        List<RecipeIngredient> inputs = List.of();
        List<RecipeIngredient> outputs = List.of();
        if (category != null) {
            Object supplier = invokeOrNull(manager, "getRecipeIngredients", category, rawRecipe);
            inputs = ingredients(supplier, "INPUT");
            outputs = ingredients(supplier, "OUTPUT");
        }
        // 某些 JEI 分类只暴露空的 ingredient supplier；保留当前输出物，避免
        // 模型收到“有配方但没有结果”的空条目。
        if (outputs.isEmpty() && target != null && !target.isEmpty()) {
            outputs = List.of(ingredient(target));
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        String typeId = recipeTypeId(recipeType);
        if (!typeId.isBlank()) {
            metadata.put("recipe_type", typeId);
        }
        if (!categoryTitle.isBlank()) {
            metadata.put("jei_category_title", categoryTitle);
        }
        if (recipeValue != null) {
            metadata.put("recipe_class", recipeValue.getClass().getSimpleName());
        }
        if (!machines.isEmpty()) {
            metadata.put("machines", String.join(", ", machines));
        }
        Integer processingTime = furnace ? processingTime(recipeValue) : null;
        return new RecipeEntry(
                recipeId,
                methodId,
                methodName,
                inputs,
                outputs,
                metadata,
                processingTime
        );
    }

    private static List<RecipeIngredient> ingredients(Object supplier, String roleName) {
        if (supplier == null) {
            return List.of();
        }
        try {
            Class<?> roleType = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
            Object role = Enum.valueOf(
                    roleType.asSubclass(Enum.class),
                    roleName
            );
            Object values = invoke(supplier, "getIngredients", role);
            List<RecipeIngredient> result = new ArrayList<>();
            for (Object value : asList(values, RecipeQuery.MAX_LIMIT)) {
                RecipeIngredient ingredient = ingredient(value);
                if (ingredient != null) {
                    result.add(ingredient);
                }
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static RecipeIngredient ingredient(Object typedIngredient) {
        if (typedIngredient == null) {
            return null;
        }
        ItemStack stack = itemStackFromTypedIngredient(typedIngredient);
        if (!stack.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return new RecipeIngredient(
                    "item",
                    id == null ? "" : id.toString(),
                    stack.getHoverName().getString(),
                    Math.max(1, stack.getCount())
            );
        }
        Object raw = invokeOrNull(typedIngredient, "getIngredient");
        if (raw == null) {
            return null;
        }
        String display = String.valueOf(raw).strip();
        return new RecipeIngredient(
                "ingredient",
                "",
                display,
                1
        );
    }

    private static List<String> catalystNames(Object manager, Object recipeType) {
        try {
            Object lookup = invoke(manager, "createRecipeCatalystLookup", recipeType);
            Object values = invokeOrNull(lookup, "getItemStack");
            if (values == null) {
                Class<?> vanillaTypes = Class.forName("mezz.jei.api.constants.VanillaTypes");
                Object itemStackType = vanillaTypes.getField("ITEM_STACK").get(null);
                values = invoke(lookup, "get", itemStackType);
            }
            List<String> names = new ArrayList<>();
            for (Object value : asList(values, RecipeQuery.MAX_LIMIT)) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    names.add(stack.getHoverName().getString());
                } else {
                    ItemStack stack = itemStackFromTypedIngredient(value);
                    if (!stack.isEmpty()) {
                        names.add(stack.getHoverName().getString());
                    }
                }
            }
            return RecipeMachineNormalizer.unique(names);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static Integer processingTime(Object recipe) {
        if (recipe == null) {
            return null;
        }
        for (String name : List.of("getCookingTime", "cookingTime", "getProcessingTime")) {
            Object value = invokeOrNull(recipe, name);
            if (value instanceof Number number && number.intValue() >= 0) {
                return number.intValue();
            }
        }
        return null;
    }

    private static Object recipeCategory(Object manager, Object recipeType, Object focus) {
        Object category = unwrapOptional(invokeOrNull(manager, "getRecipeCategory", recipeType));
        if (category != null) {
            return category;
        }
        for (Object candidate : categoriesForFocus(manager, focus, RecipeQuery.MAX_LIMIT + 1)) {
            Object candidateType = invokeOrNull(candidate, "getRecipeType");
            if (sameRecipeType(candidateType, recipeType)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<?> categoriesForFocus(Object manager, Object focus, int max) {
        try {
            Object lookup = invoke(manager, "createRecipeCategoryLookup");
            Object limited = invokeOrNull(lookup, "limitFocus", List.of(focus));
            Object values = invoke(limited == null ? lookup : limited, "get");
            return asList(values, max);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<?> lookupRecipes(Object manager, Object recipeType, Object focus, int max) {
        try {
            Object lookup = invoke(manager, "createRecipeLookup", recipeType);
            Object limited = invokeOrNull(lookup, "limitFocus", List.of(focus));
            Object values = invoke(limited == null ? lookup : limited, "get");
            return asList(values, Math.max(1, max));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<?> asList(Object values, int max) {
        if (values == null) {
            return List.of();
        }
        int limit = Math.max(0, max);
        if (values instanceof Stream<?> stream) {
            try (stream) {
                return stream.limit(limit).toList();
            }
        }
        if (values instanceof Collection<?> collection) {
            return collection.stream().limit(limit).toList();
        }
        if (values instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object value : iterable) {
                if (result.size() >= limit) {
                    break;
                }
                result.add(value);
            }
            return List.copyOf(result);
        }
        if (values.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, Array.getLength(values)); index++) {
                result.add(Array.get(values, index));
            }
            return List.copyOf(result);
        }
        return List.of(values);
    }

    private static List<?> trim(List<?> values, int limit) {
        return values.size() <= limit ? values : values.subList(0, limit);
    }

    private static Object recipeType(String fieldName) throws ReflectiveOperationException {
        Class<?> type = Class.forName("mezz.jei.api.constants.RecipeTypes");
        Field field = type.getField(fieldName);
        return field.get(null);
    }

    private static boolean isDirectType(Object recipeType) {
        try {
            return sameRecipeType(recipeType, JeiRecipeNavigator.recipeType("CRAFTING"))
                    || sameRecipeType(recipeType, JeiRecipeNavigator.recipeType("SMELTING"));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean sameRecipeType(Object first, Object second) {
        return first != null && second != null
                && (first.equals(second) || recipeTypeId(first).equals(recipeTypeId(second)));
    }

    private static String recipeTypeId(Object recipeType) {
        Object uid = invokeOrNull(recipeType, "getUid");
        return uid == null ? "" : String.valueOf(uid);
    }

    private static String categoryTitle(Object category) {
        Object title = invokeOrNull(category, "getTitle");
        if (title == null) {
            return "";
        }
        Object text = invokeOrNull(title, "getString");
        return (text == null ? String.valueOf(title) : String.valueOf(text)).strip();
    }

    private static ItemStack itemStackFromTypedIngredient(Object typedIngredient) {
        if (typedIngredient instanceof ItemStack stack) {
            return stack;
        }
        Object optional = invokeOrNull(typedIngredient, "getItemStack");
        Object value = unwrapOptional(optional);
        if (value instanceof ItemStack stack) {
            return stack;
        }
        Object ingredient = invokeOrNull(typedIngredient, "getIngredient");
        return ingredient instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).strip();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? failure == null ? "未知错误" : failure.getClass().getSimpleName()
                : current.getMessage().replaceAll("[\\r\\n]+", " ");
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
     * 为悬浮物品捕获提供同一个 JEI 运行时入口。调用方仍然只通过反射使用
     * 可选模组，JEI 缺失时返回 {@code null}。
     */
    static Object runtimeForIntegration() {
        Object runtime = cachedRuntime;
        long now = System.currentTimeMillis();
        if (now - lastRuntimeLookupAt < 1_000L) {
            return runtime;
        }
        lastRuntimeLookupAt = now;
        runtime = runtime();
        // JEI 在客户端重载/退出世界时会替换 runtime；成功和失败都写回缓存，
        // 避免继续持有已停止的旧实例，也避免每一帧重复反射扫描。
        cachedRuntime = runtime;
        return runtime;
    }

    /**
     * 按键打开助手前强制重新读取一次 JEI runtime。JEI 可能在客户端初始化后才设置
     * Internal.jeiRuntime，普通 tick 的节流缓存不能让第一次悬浮捕获继续拿到 null。
     */
    static Object refreshRuntimeForIntegration() {
        lastRuntimeLookupAt = 0L;
        return runtimeForIntegration();
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

    private static boolean invokeFocusMethod(Object target, Object focus) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals("show")
                    || method.getParameterCount() != 1
                    || List.class.isAssignableFrom(method.getParameterTypes()[0])
                    || !wrap(method.getParameterTypes()[0]).isAssignableFrom(focus.getClass())) {
                continue;
            }
            try {
                method.invoke(target, focus);
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
        return invoke(target, name);
    }

    private static Object invoke(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findCompatible(target.getClass(), name, arguments);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        }
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Object invokeOrNull(Object target, String name, Object... arguments) {
        try {
            return invoke(target, name, arguments);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method findCompatible(Class<?> type, String name, Object[] arguments) {
        for (Method method : type.getMethods()) {
            if (matches(method, name, arguments)) {
                return method;
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (matches(method, name, arguments)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean matches(Method method, String name, Object[] arguments) {
        if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = arguments[index];
            if (argument == null) {
                if (parameterTypes[index].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!wrap(parameterTypes[index]).isAssignableFrom(argument.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
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
