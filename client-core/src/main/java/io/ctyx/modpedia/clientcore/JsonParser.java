package io.ctyx.modpedia.clientcore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只解析版本清单和小型 IPC 元数据的 Java 8 JSON 子集。 */
final class JsonParser {
    private final String input;
    private int position;

    private JsonParser(String input) { this.input = input; }

    static Object parse(String input) {
        if (input == null) throw new IllegalArgumentException("JSON 不能为空");
        JsonParser parser = new JsonParser(input);
        Object value = parser.value();
        parser.space();
        if (parser.position != input.length()) throw parser.error("JSON 尾部存在多余内容");
        return value;
    }

    private Object value() {
        space();
        if (position >= input.length()) throw error("JSON 意外结束");
        char c = input.charAt(position);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (input.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (input.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (input.startsWith("null", position)) { position += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        space();
        if (take('}')) return result;
        while (true) {
            space();
            if (position >= input.length() || input.charAt(position) != '"') throw error("对象键必须是字符串");
            String key = string();
            space();
            expect(':');
            result.put(key, value());
            space();
            if (take('}')) return result;
            expect(',');
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<Object>();
        space();
        if (take(']')) return result;
        while (true) {
            result.add(value());
            space();
            if (take(']')) return result;
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (position >= input.length()) throw error("字符串转义不完整");
            char escaped = input.charAt(position++);
            switch (escaped) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u': out.append((char) Integer.parseInt(input.substring(position, position + 4), 16)); position += 4; break;
                default: throw error("未知字符串转义");
            }
        }
        throw error("字符串未闭合");
    }

    private Number number() {
        int start = position;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') position++;
            else break;
        }
        if (start == position) throw error("无效 JSON 值");
        String value = input.substring(start, position);
        try { return value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0
                ? Double.valueOf(value) : Long.valueOf(value); }
        catch (NumberFormatException e) { throw error("无效数字"); }
    }

    private void space() { while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++; }
    private boolean take(char expected) { if (position < input.length() && input.charAt(position) == expected) { position++; return true; } return false; }
    private void expect(char expected) { if (!take(expected)) throw error("期望字符 " + expected); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + "，位置 " + position); }
}
