package io.ctyx.modpedia.task;

/** FTB Quests long ID 与静态 16 位十六进制 ID 的纯 Java 回归测试。 */
public final class FtbQuestIdCodecSelfTest {
    private FtbQuestIdCodecSelfTest() {
    }

    public static void main(String[] args) {
        long sample = 0x052A7C7918C3A0D5L;
        check("052A7C7918C3A0D5".equals(FtbQuestIdCodec.format(sample)),
                "long ID 必须使用 FTBQ 的 16 位大写十六进制格式");
        check("0000000000000020".equals(FtbQuestIdCodec.fromRuntimeKey(32L)),
                "运行时 Long 键不能直接转成十进制任务 ID");
        check("052A7C7918C3A0D5".equals(FtbQuestIdCodec.fromRuntimeKey(sample)),
                "Number 运行时键应与静态任务 ID 完全一致");
        check("052A7C7918C3A0D5".equals(FtbQuestIdCodec.fromRuntimeKey("052a7c7918c3a0d5")),
                "已有十六进制文本键应规范化为大写 16 位 ID");
        check("0011223344556677".equals(FtbQuestIdCodec.fromRuntimeKey("0011223344556677")),
                "16 位数字组成的规范任务 ID 不能误当成十进制");
        check("0000000000000020".equals(FtbQuestIdCodec.fromSnbtKey("20")),
                "SNBT 数字字符键应按十六进制任务 ID 读取");
        check("QUEST_CUSTOM".equals(FtbQuestIdCodec.fromRuntimeKey("QUEST_CUSTOM")),
                "自定义非 FTBQ ID 不应被误转换");
        System.out.println("ModPedia FTB Quest ID codec self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
