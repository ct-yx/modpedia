package io.ctyx.modpedia.ai;

/** 计算工具的纯 Java 回归测试，不调用网络模型。 */
public final class CalculationToolSelfTest {
    private CalculationToolSelfTest() {
    }

    public static void main(String[] args) {
        CalculationTool tool = new CalculationTool();

        checkResult(tool.calculate("2 * (3 + 4)"), "14", "基础四则运算");
        checkResult(tool.calculate("ceil(64 / 3) * 2"), "44", "合成总量和向上取整");
        checkResult(tool.calculate("round(10 / 3, 2)"), "3.33", "指定小数位四舍五入");
        checkResult(tool.calculate("max(4, min(9, 6), abs(-8))"), "8", "函数组合");
        checkResult(tool.calculate("2 ^ 10"), "1024", "整数幂");
        checkResult(tool.calculate("-2 ^ 2"), "-4", "幂运算优先于一元负号");
        checkResult(tool.calculate("2 ^ -2"), "0.25", "负指数");
        checkResult(tool.calculate("2 × (3 ＋ 4)"), "14", "常见乘号和全角括号");

        checkError(tool.calculate("1 / 0"), "除零");
        checkError(tool.calculate("Runtime.getRuntime()"), "拒绝任意代码");
        checkError(tool.calculate("ceil(1.5"), "括号错误");
        checkError(tool.calculate(""), "空表达式");

        System.out.println("ModPedia calculation tool self-test passed");
    }

    private static void checkResult(String value, String expected, String label) {
        String marker = "\"status\":\"ok\"";
        check(value.contains(marker) && value.contains("\"result\":\"" + expected + "\""),
                label + "结果错误：" + value);
    }

    private static void checkError(String value, String label) {
        check(value.contains("\"status\":\"error\""), label + "应返回结构化错误：" + value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
