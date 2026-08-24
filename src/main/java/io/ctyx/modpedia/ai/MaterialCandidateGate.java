package io.ctyx.modpedia.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 材料与工具部件的事实闸门。
 *
 * <p>手册中的“材料特性”和注册表中的“可用部件”是两类事实。这个类只接收
 * 客户端运行时返回的结构化材料数据，并在交给模型前再次检查部件类型，避免
 * 把材料名和部件名机械拼接成一个不存在的组合。</p>
 */
public final class MaterialCandidateGate {
    private MaterialCandidateGate() {
    }

    public static JsonObject sanitize(
            JsonObject raw,
            List<String> requestedMaterials,
            List<String> requestedParts,
            String toolType
    ) {
        List<String> materials = clean(requestedMaterials, 16);
        List<String> parts = clean(requestedParts, 8);
        JsonObject result = new JsonObject();
        result.addProperty("tool", MaterialFactsTool.TOOL_NAME);
        result.addProperty("tool_type", toolType == null ? "" : toolType.strip());
        result.add("requested_materials", strings(materials));
        result.add("requested_parts", strings(parts));
        JsonArray candidates = new JsonArray();
        JsonArray rejected = new JsonArray();
        result.add("verified_candidates", candidates);
        result.add("rejected", rejected);

        if (materials.isEmpty() || parts.isEmpty()) {
            result.addProperty("status", "invalid");
            result.addProperty("message", "必须同时提供材料名称和部件类型");
            return result;
        }
        if (raw == null) {
            result.addProperty("status", "unavailable");
            result.addProperty("message", "客户端材料事实读取器未返回结果");
            return result;
        }
        String rawStatus = string(raw, "status");
        JsonArray facts = raw.has("facts") && raw.get("facts").isJsonArray()
                ? raw.getAsJsonArray("facts") : new JsonArray();
        Set<String> matchedQueries = new LinkedHashSet<>();
        for (JsonElement value : facts) {
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject fact = value.getAsJsonObject();
            String query = string(fact, "query");
            String materialId = string(fact, "material_id");
            if (!matchesRequested(materials, query, materialId, string(fact, "display_name"))) {
                continue;
            }
            Set<String> availableParts = partTypes(fact);
            List<String> missing = new ArrayList<>();
            for (String part : parts) {
                String canonical = normalizePart(part);
                if (canonical.isBlank() || !availableParts.contains(canonical)) {
                    missing.add(canonical.isBlank() ? part : canonical);
                }
            }
            String effectiveQuery = query.isBlank() ? materialId : query;
            matchedQueries.add(normalizeMaterial(effectiveQuery));
            if (!missing.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("query", effectiveQuery);
                item.addProperty("material_id", materialId);
                item.addProperty("reason", "材料已识别，但没有所需部件类型");
                item.add("missing_parts", strings(missing));
                rejected.add(item);
                continue;
            }
            JsonObject candidate = fact.deepCopy();
            candidate.addProperty("verified", true);
            candidates.add(candidate);
        }

        for (String requested : materials) {
            if (!matchedQueries.contains(normalizeMaterial(requested))) {
                JsonObject item = new JsonObject();
                item.addProperty("query", requested);
                item.addProperty("reason", "当前整合包运行时材料注册表中没有精确匹配");
                rejected.add(item);
            }
        }

        if (candidates.size() > 0) {
            result.addProperty("status", "ready");
            result.addProperty("message", "仅可使用 verified_candidates 中的材料部件组合");
        } else if ("unavailable".equalsIgnoreCase(rawStatus)) {
            result.addProperty("status", "unavailable");
            result.addProperty("message", "当前客户端没有可用的材料事实读取器");
        } else {
            result.addProperty("status", "no_match");
            result.addProperty("message", "没有经过部件兼容性验证的候选组合");
        }
        return result;
    }

    /** 把自然语言部件名归一到 1.12 工具/护甲材料统计类型。 */
    public static String normalizePart(String value) {
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
        if (normalized.equals("head") || normalized.equals("blade")
                || normalized.equals("swordblade") || normalized.equals("rapierblade")
                || normalized.contains("剑刃") || normalized.contains("刀刃")) {
            return "head";
        }
        if (normalized.equals("extra") || normalized.equals("crossguard")
                || normalized.equals("handguard") || normalized.equals("guard")
                || normalized.contains("十字柄") || normalized.contains("护手")
                || normalized.contains("交叉护手")) {
            return "extra";
        }
        if (normalized.equals("handle") || normalized.equals("rod")
                || normalized.equals("toolrod") || normalized.contains("手柄")
                || normalized.contains("工具杆")) {
            return "handle";
        }
        return normalized;
    }

    private static Set<String> partTypes(JsonObject fact) {
        Set<String> result = new LinkedHashSet<>();
        JsonElement value = fact.get("part_types");
        if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    String normalized = normalizePart(item.getAsString());
                    if (!normalized.isBlank()) {
                        result.add(normalized);
                    }
                }
            }
        }
        return result;
    }

    private static boolean matchesRequested(List<String> requested, String query,
            String materialId, String displayName) {
        String queryKey = normalizeMaterial(query);
        String idKey = normalizeMaterial(materialId);
        String displayKey = normalizeMaterial(displayName);
        for (String value : requested) {
            String key = normalizeMaterial(value);
            if (key.equals(queryKey) || key.equals(idKey) || key.equals(displayKey)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> clean(List<String> values, int limit) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                result.add(value.strip());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "")
                .strip();
    }

    /**
     * 材料查询可能来自“iron_core”“烈焰棒”等带部件后缀的自然语言。
     * 后缀只描述使用位置，不是材料本身；匹配和去重必须使用同一规则，
     * 否则候选虽已验证，结果仍会被追加为“未精确匹配”。
     */
    private static String normalizeMaterial(String value) {
        String normalized = normalize(value);
        String[] suffixes = new String[]{
                "crossguard", "handguard", "swordblade", "rapierblade", "blade",
                "handle", "toolrod", "guard", "rod", "armorcore", "armorplates",
                "armorplate", "armortrim", "plates", "plate", "core", "trim",
                "material", "ingot", "十字柄", "剑刃", "刀刃", "手柄", "工具杆",
                "材料", "锭", "棒", "杆", "柄"
        };
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }
}
