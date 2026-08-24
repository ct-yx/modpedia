package io.ctyx.modpedia.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** 材料组合不能由名称拼接推导的回归测试。 */
public final class MaterialCandidateGateSelfTest {
    private MaterialCandidateGateSelfTest() {
    }

    public static void main(String[] args) {
        JsonObject raw = new JsonObject();
        raw.addProperty("status", "ready");
        JsonArray facts = new JsonArray();
        JsonObject blaze = new JsonObject();
        blaze.addProperty("query", "烈焰棒");
        blaze.addProperty("material_id", "blaze");
        blaze.addProperty("display_name", "烈焰");
        blaze.add("part_types", array("shaft"));
        facts.add(blaze);
        JsonObject iron = new JsonObject();
        iron.addProperty("query", "iron");
        iron.addProperty("material_id", "iron");
        iron.addProperty("display_name", "铁");
        iron.add("part_types", array("head", "handle", "extra"));
        facts.add(iron);
        raw.add("facts", facts);

        JsonObject result = MaterialCandidateGate.sanitize(
                raw,
                List.of("烈焰棒", "iron"),
                List.of("handle"),
                "rapier"
        );
        check("ready".equals(result.get("status").getAsString()), "应保留可验证候选");
        check(result.getAsJsonArray("verified_candidates").size() == 1,
                "只有铁应作为已验证手柄候选");
        check(result.getAsJsonArray("rejected").size() == 1,
                "烈焰材料缺少手柄统计时应被拒绝");
        check(result.getAsJsonArray("rejected").get(0).toString().contains("handle"),
                "拒绝原因应指出缺少手柄部件");
        check("extra".equals(MaterialCandidateGate.normalizePart("十字柄")),
                "十字柄不得误判为手柄");
        check("handle".equals(MaterialCandidateGate.normalizePart("工具手柄")),
                "手柄别名应归一化");
        check("core".equals(MaterialCandidateGate.normalizePart("armorcore")),
                "护甲基底应归一化为 core");
        check("plates".equals(MaterialCandidateGate.normalizePart("armorplate")),
                "护甲板应归一化为 plates");
        check("trim".equals(MaterialCandidateGate.normalizePart("armortrim")),
                "护甲夹板应归一化为 trim");

        JsonObject armor = new JsonObject();
        armor.addProperty("query", "iron");
        armor.addProperty("material_id", "iron");
        armor.addProperty("display_name", "铁");
        armor.add("part_types", array("core", "plates", "trim"));
        JsonObject armorRaw = new JsonObject();
        armorRaw.addProperty("status", "ready");
        armorRaw.add("facts", arrayObjects(armor));
        JsonObject armorResult = MaterialCandidateGate.sanitize(
                armorRaw,
                List.of("iron"),
                List.of("armorcore", "armorplate", "armortrim"),
                "armor"
        );
        check("ready".equals(armorResult.get("status").getAsString()),
                "护甲 core/plates/trim 组合应通过事实闸门");
        check(armorResult.getAsJsonArray("verified_candidates").size() == 1,
                "护甲事实应生成一个已验证候选");
        JsonObject suffixResult = MaterialCandidateGate.sanitize(
                armorRaw,
                List.of("iron_core"),
                List.of("core"),
                "armor"
        );
        check("ready".equals(suffixResult.get("status").getAsString()),
                "带部件后缀的材料查询仍应匹配材料事实");
        check(suffixResult.getAsJsonArray("rejected").size() == 0,
                "已匹配的材料不应被重复标记为未精确匹配");
        check(MaterialFactsTool.parseList("[\"iron\", \"paper\", \"iron\"]", 8).size() == 2,
                "材料列表应去重");
        System.out.println("ModPedia material candidate gate self-test passed");
    }

    private static JsonArray array(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static JsonArray arrayObjects(JsonObject... values) {
        JsonArray result = new JsonArray();
        for (JsonObject value : values) {
            result.add(value);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
