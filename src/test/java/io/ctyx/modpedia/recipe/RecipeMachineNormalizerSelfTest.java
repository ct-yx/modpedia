package io.ctyx.modpedia.recipe;

import java.util.List;

/** JEI 机器等级合并回归；不加载可选机器模组。 */
public final class RecipeMachineNormalizerSelfTest {
    private RecipeMachineNormalizerSelfTest() {
    }

    public static void main(String[] args) {
        List<String> values = RecipeMachineNormalizer.unique(List.of(
                "基础处理机",
                "高级处理机",
                "精英处理机",
                "基础处理机",
                "Factory Tier I",
                "Factory Tier II",
                "Enrichment Chamber (Basic)",
                "Advanced Enrichment Chamber"
        ));
        check(values.size() == 3, "机器等级和重复名称应合并为三个机器");
        check(values.contains("基础处理机"), "中文机器名称应保留第一个可读名称");
        check(values.contains("Factory Tier I"), "Tier 机器应保留第一个可读名称");
        check(values.contains("Enrichment Chamber (Basic)"), "括号等级应保留第一个可读名称");
        check(RecipeMachineNormalizer.unique(List.of()).isEmpty(), "空机器列表应保持为空");
        System.out.println("ModPedia recipe machine normalizer self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
