package io.ctyx.modpedia.ai;

import java.util.Locale;

/** 判断问题是否需要先验证材料与工具部件的真实兼容性。 */
public final class MaterialQuestionClassifier {
    private MaterialQuestionClassifier() {
    }

    public static boolean isMaterialQuestion(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        boolean partMentioned = containsAny(value,
                "手柄", "工具杆", "十字柄", "护手", "剑刃", "刀刃", "刀头", "镐头",
                "护甲基底", "护甲板", "护甲夹板", "护甲镶边", "armorcore", "armorplate",
                "armortrim", "core", "plates", "trim", "handle", "tool rod",
                "cross guard", "hand guard", "blade", "head");
        boolean materialMentioned = containsAny(value,
                "材料", "材质", "搭配", "组合", "匠魂", "强化材料", "material", "tinkers",
                "build", "combination");
        boolean combatMentioned = containsAny(value,
                "伤害", "攻击力", "攻击速度", "耐久", "挖掘速度", "计算", "damage", "attack",
                "durability", "mining speed", "calculate");
        boolean contextualCombat = combatMentioned && (containsAny(value,
                "版本", "这套", "这把", "该武器", "该工具", "weapon", "tool", "set", "build")
                || (value.contains("计算") && value.contains("伤害")));
        return partMentioned || (materialMentioned && combatMentioned)
                || contextualCombat
                || (materialMentioned && containsAny(value, "推荐", "推荐一套", "recommend"));
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
