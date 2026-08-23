package io.ctyx.modpedia.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.12.2 配置文件使用的最小 SNBT 解析器。
 *
 * <p>它只依赖 JDK，供任务定义归一化和运行时进度读取使用；不会加载
 * FTBQ/FTB Library 类，也不会把运行时进度写回文件。</p>
 */
public final class LegacySnbtParser {
    private LegacySnbtParser() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.value();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("SNBT 末尾存在无法解析的内容");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String text(Map<String, Object> object, String key) {
        return text(object.get(key));
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        try {
            return (int) Math.round(Double.parseDouble(text(object.get(key))));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public static double number(Object value, double fallback) {
        try {
            return Double.parseDouble(text(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private Object value() {
            skipWhitespace();
            if (end()) {
                throw error("缺少值");
            }
            char current = source.charAt(index);
            if (current == '{') {
                return compoundValue();
            }
            if (current == '[') {
                return listValue();
            }
            if (current == '"' || current == '\'') {
                return quoted();
            }
            return bareValue();
        }

        private Map<String, Object> compoundValue() {
            index++;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            skipWhitespace();
            while (!end() && source.charAt(index) != '}') {
                String key = keyValue();
                skipWhitespace();
                expect(':');
                result.put(key, value());
                skipWhitespace();
                if (!end() && source.charAt(index) == ',') {
                    index++;
                    skipWhitespace();
                }
            }
            expect('}');
            return result;
        }

        private List<Object> listValue() {
            index++;
            skipWhitespace();
            if (!end() && (source.charAt(index) == 'B'
                    || source.charAt(index) == 'I'
                    || source.charAt(index) == 'L')
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == ';') {
                index += 2;
            }
            List<Object> result = new ArrayList<Object>();
            skipWhitespace();
            while (!end() && source.charAt(index) != ']') {
                result.add(value());
                skipWhitespace();
                if (!end() && source.charAt(index) == ',') {
                    index++;
                    skipWhitespace();
                }
            }
            expect(']');
            return result;
        }

        private String keyValue() {
            skipWhitespace();
            if (!end() && (source.charAt(index) == '"' || source.charAt(index) == '\'')) {
                return quoted();
            }
            int start = index;
            while (!end() && source.charAt(index) != ':' && !Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("缺少 compound key");
            }
            return source.substring(start, index);
        }

        private String quoted() {
            char quote = source.charAt(index++);
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char current = source.charAt(index++);
                if (current == quote) {
                    return result.toString();
                }
                if (current == '\\' && !end()) {
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        default: result.append(escaped); break;
                    }
                } else {
                    result.append(current);
                }
            }
            throw error("字符串没有闭合");
        }

        private Object bareValue() {
            int start = index;
            while (!end()) {
                char current = source.charAt(index);
                if (Character.isWhitespace(current) || current == ',' || current == ']' || current == '}') {
                    break;
                }
                index++;
            }
            String token = source.substring(start, index);
            if (token.length() == 0) {
                throw error("缺少值");
            }
            if ("true".equalsIgnoreCase(token)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(token)) {
                return Boolean.FALSE;
            }
            String numeric = token;
            char suffix = Character.toLowerCase(numeric.charAt(numeric.length() - 1));
            if ("bslfd".indexOf(suffix) >= 0) {
                numeric = numeric.substring(0, numeric.length() - 1);
            }
            try {
                if (numeric.matches("[-+]?\\d+")) {
                    long number = Long.parseLong(numeric);
                    return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                            ? Integer.valueOf((int) number) : Long.valueOf(number);
                }
                return Double.valueOf(numeric);
            } catch (NumberFormatException ignored) {
                return token;
            }
        }

        private void skipWhitespace() {
            while (!end()) {
                char current = source.charAt(index);
                if (Character.isWhitespace(current)) {
                    index++;
                    continue;
                }
                if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    index += 2;
                    while (!end() && source.charAt(index) != '\n') {
                        index++;
                    }
                    continue;
                }
                break;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (end() || source.charAt(index) != expected) {
                throw error("期望字符 " + expected);
            }
            index++;
        }

        private boolean end() {
            return index >= source.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("SNBT 解析失败（位置 " + index + "）：" + message);
        }
    }
}
