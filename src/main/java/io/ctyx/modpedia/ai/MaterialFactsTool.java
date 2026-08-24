package io.ctyx.modpedia.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 请求客户端验证材料与工具部件兼容性，并把验证结果交给模型。
 *
 * <p>Worker 不读取游戏类或手册猜测材料组合；没有客户端事实时只返回降级状态。</p>
 */
public final class MaterialFactsTool {
    public static final String TOOL_NAME = "validate_material_candidates";
    private static final int MAX_MATERIALS = 16;
    private static final int MAX_PARTS = 8;

    private final Requester requester;

    public MaterialFactsTool(Requester requester) {
        this.requester = requester;
    }

    @Tool(
            name = TOOL_NAME,
            value = "验证材料是否真的支持指定工具或护甲部件。涉及材料搭配、工具/护甲数值或计算时，"
                    + "先调用本工具；只有 verified_candidates 可以作为组合，rejected 中的材料不得使用。"
    )
    public String validateMaterialCandidates(
            @P(name = "tool_type", value = "工具类型，例如 rapier、pickaxe") String toolType,
            @P(name = "materials", value = "材料名称列表，使用 JSON 数组字符串或逗号分隔") String materials,
            @P(name = "parts", value = "部件列表，例如 blade,handle,cross_guard") String parts
    ) {
        List<String> materialNames = parseList(materials, MAX_MATERIALS);
        List<String> partNames = parseList(parts, MAX_PARTS);
        if (materialNames.isEmpty() || partNames.isEmpty()) {
            return MaterialCandidateGate.sanitize(null, materialNames, partNames, toolType).toString();
        }
        if (requester == null) {
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("status", "unavailable");
            return MaterialCandidateGate.sanitize(unavailable, materialNames, partNames, toolType)
                    .toString();
        }
        try {
            JsonObject response = requester.request(
                    toolType == null ? "" : toolType.strip(),
                    materialNames,
                    partNames
            );
            return MaterialCandidateGate.sanitize(response, materialNames, partNames, toolType)
                    .toString();
        } catch (Throwable failure) {
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("status", "unavailable");
            return MaterialCandidateGate.sanitize(unavailable, materialNames, partNames, toolType)
                    .toString();
        }
    }

    static List<String> parseList(String value, int limit) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String source = value == null ? "" : value.strip();
        if (source.startsWith("[") && source.endsWith("]")) {
            try {
                JsonElement parsed = new JsonParser().parse(source);
                if (parsed.isJsonArray()) {
                    JsonArray array = parsed.getAsJsonArray();
                    for (JsonElement item : array) {
                        if (item != null && item.isJsonPrimitive()) {
                            add(result, item.getAsString(), limit);
                        }
                    }
                    return new ArrayList<>(result);
                }
            } catch (RuntimeException ignored) {
                // 继续按逗号分隔兼容模型的简短参数。
            }
        }
        for (String item : source.split("[,，;；\\n]")) {
            add(result, item, limit);
        }
        return new ArrayList<>(result);
    }

    private static void add(LinkedHashSet<String> result, String value, int limit) {
        if (value == null || value.strip().isBlank() || result.size() >= limit) {
            return;
        }
        result.add(value.strip());
    }

    @FunctionalInterface
    public interface Requester {
        JsonObject request(String toolType, List<String> materials, List<String> parts);
    }
}
