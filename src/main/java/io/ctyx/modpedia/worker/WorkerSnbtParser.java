package io.ctyx.modpedia.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker 内使用的最小 SNBT 解析器。
 *
 * <p>任务定义只需要 compound、list、字符串和数字；这里不依赖 Minecraft/NBT
 * 类，确保独立 Worker 可以直接读取配置目录。</p>
 */
public final class WorkerSnbtParser {
    private WorkerSnbtParser() {
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
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static List<Object> list(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        return String.valueOf(value);
    }

    public static String text(Map<String, Object> object, String key) {
        return text(object.get(key));
    }

    public static double number(Map<String, Object> object, String key, double fallback) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(text(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        return (int) Math.round(number(object, key, fallback));
    }

    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(text(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(text(value))) {
            return false;
        }
        return fallback;
    }

    /** 把解析树重新编码为稳定的 SNBT 文本，用于 raw_json 保留未知字段。 */
    public static String toSnbt(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    result.append(' ');
                }
                first = false;
                result.append(key(String.valueOf(entry.getKey()))).append(':')
                        .append(toSnbt(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    result.append(' ');
                }
                result.append(toSnbt(list.get(index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String string) {
            return quote(string);
        }
        return String.valueOf(value);
    }

    private static String key(String value) {
        return value.matches("[A-Za-z0-9_+./-]+") ? value : quote(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
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
            return switch (source.charAt(index)) {
                case '{' -> compoundValue();
                case '[' -> listValue();
                case '\"', '\'' -> quoted();
                default -> bareValue();
            };
        }

        private Map<String, Object> compoundValue() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            while (!end() && source.charAt(index) != '}') {
                String name = keyValue();
                skipWhitespace();
                expect(':');
                result.put(name, value());
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
            // Typed arrays use [B;, [I; or [L;. The values are still useful as a
            // normal list to the task importer.
            if (!end() && (source.charAt(index) == 'B'
                    || source.charAt(index) == 'I'
                    || source.charAt(index) == 'L')
                    && index + 1 < source.length() && source.charAt(index + 1) == ';') {
                index += 2;
            }
            List<Object> result = new ArrayList<>();
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
            if (!end() && (source.charAt(index) == '\"' || source.charAt(index) == '\'')) {
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
                    result.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> escaped;
                    });
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
            if (token.isBlank()) {
                throw error("缺少值");
            }
            if ("true".equalsIgnoreCase(token)) {
                return true;
            }
            if ("false".equalsIgnoreCase(token)) {
                return false;
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
                            ? (int) number : number;
                }
                return Double.parseDouble(numeric);
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
                // A few hand-authored files contain line comments; accepting them
                // costs nothing and keeps the Worker importer tolerant.
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
