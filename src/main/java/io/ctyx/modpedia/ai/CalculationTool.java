package io.ctyx.modpedia.ai;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地确定性计算工具。
 *
 * <p>只解析受限的数学表达式，不执行 Java、脚本或系统命令。模型负责把问题
 * 转换为表达式，本地使用 BigDecimal 完成计算并把结果返回给模型。</p>
 */
public final class CalculationTool {
    public static final String TOOL_NAME = "calculate";

    private static final MathContext MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);
    private static final int MAX_EXPRESSION_LENGTH = 512;
    private static final int MAX_OPERATIONS = 256;
    private static final int MAX_NESTING = 64;
    private static final int MAX_EXPONENT = 1000;
    private static final int MAX_SCALE = 18;
    private static final int MAX_OUTPUT_LENGTH = 512;

    @Tool(
            name = TOOL_NAME,
            value = "用本地 BigDecimal 计算表达式。复杂算术、比例、总量、取整或换算时调用；" +
                    "只用数字、括号、+ - * / % ^ 和 ceil/floor/round/min/max/abs/pow/sum。"
    )
    public String calculate(
            @P(name = "expression", value = "数字和受支持运算符，例如 ceil(64/3)*2")
            String expression
    ) {
        String raw = expression == null ? "" : expression.strip();
        if (raw.isBlank()) {
            return error(raw, "表达式为空");
        }
        if (raw.length() > MAX_EXPRESSION_LENGTH) {
            return error(raw, "表达式过长，最多支持 " + MAX_EXPRESSION_LENGTH + " 个字符");
        }

        String normalized = normalize(raw);
        try {
            Parser parser = new Parser(normalized);
            BigDecimal result = parser.parse();
            parser.ensureEnd();
            String formatted = format(result);
            return "{\"status\":\"ok\",\"expression\":" + quote(raw)
                    + ",\"result\":" + quote(formatted) + "}";
        } catch (CalculationException exception) {
            return error(raw, exception.getMessage());
        } catch (ArithmeticException exception) {
            return error(raw, "算术错误：" + exception.getMessage());
        }
    }

    private static String normalize(String expression) {
        return expression
                .replace('×', '*')
                .replace('÷', '/')
                .replace('＋', '+')
                .replace('－', '-')
                .replace('％', '%')
                .replace('（', '(')
                .replace('）', ')')
                .replace('，', ',');
    }

    private static String format(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        String output = normalized.toPlainString();
        if (output.length() > MAX_OUTPUT_LENGTH) {
            throw new CalculationException("结果过长，已拒绝输出");
        }
        return output;
    }

    private static String error(String expression, String message) {
        return "{\"status\":\"error\",\"expression\":" + quote(expression)
                + ",\"message\":" + quote(message == null ? "计算失败" : message) + "}";
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    private static BigDecimal applyPower(BigDecimal base, BigDecimal exponent) {
        int integerExponent;
        try {
            integerExponent = exponent.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException exception) {
            throw new CalculationException("^ 和 pow 的指数必须是整数");
        }
        if (Math.abs((long) integerExponent) > MAX_EXPONENT) {
            throw new CalculationException("指数范围必须在 -" + MAX_EXPONENT + " 到 " + MAX_EXPONENT + " 之间");
        }
        if (integerExponent >= 0) {
            return base.pow(integerExponent, MATH_CONTEXT);
        }
        if (base.signum() == 0) {
            throw new CalculationException("零不能使用负指数");
        }
        return BigDecimal.ONE.divide(base.pow(-integerExponent, MATH_CONTEXT), MATH_CONTEXT);
    }

    private static BigDecimal requireArguments(String name, List<BigDecimal> arguments, int count) {
        if (arguments.size() != count) {
            throw new CalculationException(name + " 需要 " + count + " 个参数，实际收到 " + arguments.size() + " 个");
        }
        return arguments.get(0);
    }

    private static BigDecimal requireScale(String name, BigDecimal value) {
        int scale;
        try {
            scale = value.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException exception) {
            throw new CalculationException(name + " 的小数位数必须是整数");
        }
        if (scale < -MAX_SCALE || scale > MAX_SCALE) {
            throw new CalculationException(name + " 的小数位数必须在 -" + MAX_SCALE + " 到 " + MAX_SCALE + " 之间");
        }
        return BigDecimal.valueOf(scale);
    }

    private static BigDecimal applyFunction(String name, List<BigDecimal> arguments) {
        return switch (name) {
            case "abs" -> requireArguments(name, arguments, 1).abs(MATH_CONTEXT);
            case "ceil" -> requireArguments(name, arguments, 1).setScale(0, RoundingMode.CEILING);
            case "floor" -> requireArguments(name, arguments, 1).setScale(0, RoundingMode.FLOOR);
            case "round" -> {
                if (arguments.size() != 1 && arguments.size() != 2) {
                    throw new CalculationException("round 需要 1 或 2 个参数，实际收到 " + arguments.size() + " 个");
                }
                int scale = arguments.size() == 1
                        ? 0
                        : requireScale(name, arguments.get(1)).intValueExact();
                yield arguments.get(0).setScale(scale, RoundingMode.HALF_UP);
            }
            case "min", "max" -> {
                if (arguments.size() < 2) {
                    throw new CalculationException(name + " 至少需要 2 个参数");
                }
                BigDecimal value = arguments.get(0);
                for (int index = 1; index < arguments.size(); index++) {
                    int comparison = value.compareTo(arguments.get(index));
                    if (("min".equals(name) && comparison > 0)
                            || ("max".equals(name) && comparison < 0)) {
                        value = arguments.get(index);
                    }
                }
                yield value;
            }
            case "pow" -> {
                requireArguments(name, arguments, 2);
                yield applyPower(arguments.get(0), arguments.get(1));
            }
            case "sum" -> {
                if (arguments.isEmpty()) {
                    throw new CalculationException("sum 至少需要 1 个参数");
                }
                BigDecimal value = BigDecimal.ZERO;
                for (BigDecimal argument : arguments) {
                    value = value.add(argument, MATH_CONTEXT);
                }
                yield value;
            }
            default -> throw new CalculationException("不支持的函数：" + name);
        };
    }

    private static final class Parser {
        private final String input;
        private int position;
        private int operations;
        private int nesting;

        private Parser(String input) {
            this.input = input;
        }

        private BigDecimal parse() {
            return parseAdditive();
        }

        private void ensureEnd() {
            skipWhitespace();
            if (position != input.length()) {
                throw errorAt("无法解析字符");
            }
        }

        private BigDecimal parseAdditive() {
            BigDecimal value = parseMultiplicative();
            while (true) {
                if (match('+')) {
                    value = value.add(parseMultiplicative(), MATH_CONTEXT);
                    tick();
                } else if (match('-')) {
                    value = value.subtract(parseMultiplicative(), MATH_CONTEXT);
                    tick();
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseMultiplicative() {
            BigDecimal value = parseUnary();
            while (true) {
                if (match('*')) {
                    value = value.multiply(parseUnary(), MATH_CONTEXT);
                    tick();
                } else if (match('/')) {
                    BigDecimal divisor = parseUnary();
                    if (divisor.signum() == 0) {
                        throw new CalculationException("除数不能为零");
                    }
                    value = value.divide(divisor, MATH_CONTEXT);
                    tick();
                } else if (match('%')) {
                    BigDecimal divisor = parseUnary();
                    if (divisor.signum() == 0) {
                        throw new CalculationException("取余除数不能为零");
                    }
                    value = value.remainder(divisor, MATH_CONTEXT);
                    tick();
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parsePower() {
            BigDecimal value = parsePrimary();
            if (match('^')) {
                value = applyPower(value, parseUnary());
                tick();
            }
            return value;
        }

        private BigDecimal parseUnary() {
            if (match('+')) {
                tick();
                return parseUnary();
            }
            if (match('-')) {
                tick();
                return parseUnary().negate(MATH_CONTEXT);
            }
            return parsePower();
        }

        private BigDecimal parsePrimary() {
            skipWhitespace();
            if (match('(')) {
                nesting++;
                if (nesting > MAX_NESTING) {
                    throw new CalculationException("表达式嵌套层数过多");
                }
                BigDecimal value = parseAdditive();
                expect(')');
                nesting--;
                return value;
            }
            if (position < input.length()
                    && (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) {
                return parseNumber();
            }
            if (position < input.length()
                    && (Character.isLetter(input.charAt(position)) || input.charAt(position) == '_')) {
                return parseFunction();
            }
            throw errorAt("需要数字、函数或左括号");
        }

        private BigDecimal parseNumber() {
            int start = position;
            boolean hasDigits = false;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
                hasDigits = true;
            }
            if (position < input.length() && input.charAt(position) == '.') {
                position++;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                    hasDigits = true;
                }
            }
            if (!hasDigits) {
                throw errorAt("数字格式错误");
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                position++;
                if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                    position++;
                }
                int exponentStart = position;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
                if (exponentStart == position) {
                    throw errorAt("科学计数法缺少指数");
                }
                String exponentText = input.substring(exponentStart, position);
                try {
                    if (Math.abs(Long.parseLong(exponentText)) > MAX_EXPONENT) {
                        throw new CalculationException("科学计数法指数超出范围");
                    }
                } catch (NumberFormatException exception) {
                    throw new CalculationException("科学计数法指数格式错误");
                }
            }
            try {
                return new BigDecimal(input.substring(start, position), MATH_CONTEXT);
            } catch (NumberFormatException exception) {
                throw errorAt("数字格式错误");
            }
        }

        private BigDecimal parseFunction() {
            String name = parseIdentifier();
            expect('(');
            List<BigDecimal> arguments = new ArrayList<>();
            if (!match(')')) {
                do {
                    arguments.add(parseAdditive());
                } while (match(','));
                expect(')');
            }
            return applyFunction(name, arguments);
        }

        private String parseIdentifier() {
            skipWhitespace();
            int start = position;
            while (position < input.length()
                    && (Character.isLetterOrDigit(input.charAt(position)) || input.charAt(position) == '_')) {
                position++;
            }
            return input.substring(start, position).toLowerCase(Locale.ROOT);
        }

        private void expect(char character) {
            if (!match(character)) {
                throw errorAt("缺少 '" + character + "'");
            }
        }

        private boolean match(char character) {
            skipWhitespace();
            if (position < input.length() && input.charAt(position) == character) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private void tick() {
            operations++;
            if (operations > MAX_OPERATIONS) {
                throw new CalculationException("表达式运算步骤过多");
            }
        }

        private CalculationException errorAt(String message) {
            return new CalculationException(message + "（位置 " + position + "）");
        }
    }

    private static final class CalculationException extends IllegalArgumentException {
        private CalculationException(String message) {
            super(message);
        }
    }
}
