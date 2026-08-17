package io.ctyx.modpedia.task;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FTB Quests 运行时 ID 编解码。
 *
 * <p>FTB Quests 的任务对象 ID 在静态任务文件中使用
 * {@code %016X} 格式，而 {@code TeamData} 的 Long2LongMap 运行时键在反射
 * 后会变成 {@link Long}。两者必须在进入任务查询层前统一，否则运行时进度
 * 会被当成另一组任务 ID。</p>
 */
public final class FtbQuestIdCodec {
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+");
    private static final Pattern HEX = Pattern.compile("(?i)[0-9a-f]{1,16}");

    private FtbQuestIdCodec() {
    }

    /** 将 FTBQ 的 long ID 转成与静态任务定义一致的 16 位大写十六进制字符串。 */
    public static String format(long id) {
        return String.format(Locale.ROOT, "%016X", id);
    }

    /**
     * 将反射读取到的运行时 Map 键转换为稳定任务 ID。
     *
     * <p>正常路径是 {@link Number}；字符串分支用于兼容存档读取器或版本映射
     * 返回的文本键。非 FTBQ 形式的合成/自定义 ID 原样保留。</p>
     */
    public static String fromRuntimeKey(Object key) {
        if (key == null) {
            return "";
        }
        if (key instanceof Number number) {
            return format(number.longValue());
        }

        String value = key.toString().strip();
        if (value.isBlank()) {
            return "";
        }
        // 已经是 16 位规范 ID 时优先按十六进制处理；否则全数字的文本
        // 仍按兼容旧适配器的十进制键处理。
        if (value.length() == 16 && HEX.matcher(value).matches()) {
            return fromHex(value);
        }
        if (DECIMAL.matcher(value).matches()) {
            try {
                return format(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (HEX.matcher(value).matches()) {
            return fromHex(value);
        }
        return value;
    }

    /** SNBT 的 Map key 按 FTBQ 文件格式是十六进制文本，数字字符也不能当十进制。 */
    public static String fromSnbtKey(Object key) {
        if (key == null) {
            return "";
        }
        if (key instanceof Number) {
            return fromRuntimeKey(key);
        }
        String value = key.toString().strip();
        return HEX.matcher(value).matches() ? fromHex(value) : fromRuntimeKey(value);
    }

    private static String fromHex(String value) {
        try {
            return format(Long.parseUnsignedLong(value, 16));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
}
