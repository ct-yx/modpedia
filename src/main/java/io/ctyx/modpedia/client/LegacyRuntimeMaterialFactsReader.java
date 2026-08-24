package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1.12.2 材料事实读取器。
 *
 * <p>材料注册表只在 Worker 明确请求材料兼容性时读取；这里通过反射隔离可选
 * 材料模组，响应只包含字符串和数值，不把游戏对象传出客户端 JVM。</p>
 */
public final class LegacyRuntimeMaterialFactsReader {
    private static final String MATERIALS_CLASS =
            "slimeknights.tconstruct.tools.TinkerMaterials";
    private static final int MAX_MATERIALS = 16;
    private static final int MAX_PARTS = 8;

    private LegacyRuntimeMaterialFactsReader() {
    }

    public static boolean isAvailable() {
        try {
            Class<?> type = Class.forName(MATERIALS_CLASS, false,
                    LegacyRuntimeMaterialFactsReader.class.getClassLoader());
            Field field = type.getDeclaredField("materials");
            return Modifier.isStatic(field.getModifiers());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static JsonObject read(JsonObject request) {
        JsonObject response = base(value(request, "request_id"));
        response.addProperty("request_kind", "material_facts");
        response.addProperty("tool_type", value(request, "tool_type"));
        response.add("facts", new JsonArray());

        List<String> materials = requested(request, "materials", MAX_MATERIALS);
        List<String> parts = requested(request, "parts", MAX_PARTS);
        if (materials.isEmpty() || parts.isEmpty()) {
            response.addProperty("status", "invalid");
            response.addProperty("world_ready", false);
            return response;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        boolean worldReady = minecraft != null
                && minecraft.world != null
                && minecraft.player != null
                && minecraft.getConnection() != null;
        response.addProperty("world_ready", worldReady);
        if (!worldReady) {
            response.addProperty("status", "unavailable");
            response.addProperty("message", "当前世界尚未就绪");
            return response;
        }

        try {
            Class<?> materialsType = Class.forName(MATERIALS_CLASS, false,
                    LegacyRuntimeMaterialFactsReader.class.getClassLoader());
            Field field = materialsType.getDeclaredField("materials");
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof Collection)) {
                response.addProperty("status", "unavailable");
                response.addProperty("message", "材料注册表尚未完成");
                return response;
            }
            JsonArray facts = response.getAsJsonArray("facts");
            for (String query : materials) {
                Object material = findMaterial((Collection<?>) value, query);
                if (material != null) {
                    facts.add(fact(query, material, parts));
                }
            }
            response.addProperty("status", facts.size() == 0 ? "no_match" : "ready");
            response.addProperty("captured_at", System.currentTimeMillis());
            return response;
        } catch (Throwable failure) {
            response.addProperty("status", "unavailable");
            response.addProperty("message", "材料注册表读取失败");
            return response;
        }
    }

    private static Object findMaterial(Collection<?> values, String query) {
        String requested = normalizeMaterial(query);
        if (requested.isEmpty()) {
            return null;
        }
        for (Object material : values) {
            if (material == null) {
                continue;
            }
            String id = invokeString(material, "getIdentifier");
            String localized = invokeString(material, "getLocalizedName");
            if (sameMaterial(requested, id) || sameMaterial(requested, localized)) {
                return material;
            }
        }
        return null;
    }

    private static boolean sameMaterial(String requested, String actual) {
        String normalized = normalizeMaterial(actual);
        return !normalized.isEmpty()
                && (requested.equals(normalized)
                || requested.equals(stripMaterialSuffix(normalized))
                || stripMaterialSuffix(requested).equals(normalized));
    }

    private static JsonObject fact(String query, Object material, List<String> requestedParts) {
        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.addProperty("material_id", invokeString(material, "getIdentifier"));
        result.addProperty("display_name", invokeString(material, "getLocalizedName"));
        JsonArray partTypes = new JsonArray();
        Set<String> seen = new LinkedHashSet<String>();
        Object stats = invoke(material, "getAllStats");
        if (stats instanceof Iterable) {
            for (Object stat : (Iterable<?>) stats) {
                String type = canonicalPart(invokeString(stat, "getIdentifier"));
                if (!type.isEmpty() && seen.add(type)) {
                    partTypes.add(type);
                }
            }
        }
        result.add("part_types", partTypes);
        JsonObject statsObject = new JsonObject();
        for (String part : requestedParts) {
            String canonical = canonicalPart(part);
            if (canonical.isEmpty() || statsObject.has(canonical)) {
                continue;
            }
            Object stat = invoke(material, "getStats", new Class<?>[]{String.class}, canonical);
            if (stat != null) {
                JsonObject values = numericFields(stat);
                if (values.entrySet().size() > 0) {
                    statsObject.add(canonical, values);
                }
            }
        }
        result.add("stats", statsObject);
        return result;
    }

    private static JsonObject numericFields(Object object) {
        JsonObject result = new JsonObject();
        Class<?> type = object.getClass();
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                Object value = field.get(object);
                if (value instanceof Number) {
                    result.addProperty(field.getName(), (Number) value);
                }
            } catch (Throwable ignored) {
                // 一个统计字段不可读时，保留其他可读字段。
            }
        }
        return result;
    }

    static String canonicalPart(String value) {
        String normalized = normalize(value);
        if (normalized.equals("core") || normalized.equals("armorcore")
                || normalized.contains("护甲基底") || normalized.contains("胸甲基底")) {
            return "core";
        }
        if (normalized.equals("plates") || normalized.equals("plate")
                || normalized.equals("armorplates") || normalized.equals("armorplate")
                || normalized.contains("护甲板") || normalized.contains("护甲板材")) {
            return "plates";
        }
        if (normalized.equals("trim") || normalized.equals("armortrim")
                || normalized.equals("armortrims") || normalized.contains("护甲夹板")
                || normalized.contains("护甲镶边") || normalized.contains("护甲边")) {
            return "trim";
        }
        if (normalized.equals("head") || normalized.contains("blade")
                || normalized.contains("剑刃") || normalized.contains("刀刃")) {
            return "head";
        }
        if (normalized.equals("extra") || normalized.contains("guard")
                || normalized.contains("十字柄") || normalized.contains("护手")) {
            return "extra";
        }
        if (normalized.equals("handle") || normalized.equals("rod")
                || normalized.equals("toolrod")
                || normalized.contains("手柄") || normalized.contains("工具杆")) {
            return "handle";
        }
        return normalized;
    }

    static String normalizeMaterial(String value) {
        String normalized = normalize(value);
        String stripped = stripMaterialSuffix(normalized);
        return stripped.isEmpty() ? normalized : stripped;
    }

    private static String stripMaterialSuffix(String value) {
        String result = value == null ? "" : value;
        String[] suffixes = new String[]{
                "crossguard", "handguard", "swordblade", "rapierblade", "blade",
                "handle", "toolrod", "guard", "rod", "armorcore", "armorplates",
                "armorplate", "armortrim", "plates", "plate", "core", "trim",
                "material", "ingot",
                "十字柄", "剑刃", "刀刃", "手柄", "工具杆", "材料", "锭", "棒", "杆", "柄"
        };
        for (String suffix : suffixes) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                return result.substring(0, result.length() - suffix.length());
            }
        }
        return result;
    }

    private static List<String> requested(JsonObject request, String name, int limit) {
        List<String> result = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        JsonElement values = request == null ? null : request.get(name);
        if (values == null || !values.isJsonArray()) {
            return result;
        }
        for (JsonElement value : values.getAsJsonArray()) {
            if (value == null || !value.isJsonPrimitive()) {
                continue;
            }
            String text = value.getAsString().trim();
            if (!text.isEmpty() && seen.add(text)) {
                result.add(text);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method value = target.getClass().getMethod(method, types);
            value.setAccessible(true);
            return value.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0]);
    }

    private static String invokeString(Object target, String method) {
        Object value = invoke(target, method);
        return value == null ? "" : String.valueOf(value);
    }

    private static JsonObject base(String requestId) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", 1);
        result.addProperty("type", "runtime_context_response");
        result.addProperty("request_id", requestId == null ? "" : requestId);
        result.addProperty("conversation_id", "");
        return result;
    }

    private static String value(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "")
                .trim();
    }
}
