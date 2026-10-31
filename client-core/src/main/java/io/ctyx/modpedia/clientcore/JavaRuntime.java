package io.ctyx.modpedia.clientcore;

/** Worker 启动前的 Java 主版本检测。 */
public final class JavaRuntime {
    private JavaRuntime() { }

    public static int currentMajor() {
        return parseMajor(System.getProperty("java.specification.version", ""));
    }

    public static int parseMajor(String version) {
        if (version == null || version.trim().isEmpty()) return -1;
        String value = version.trim();
        try {
            if (value.startsWith("1.")) return Integer.parseInt(value.substring(2, 3));
            int dot = value.indexOf('.');
            int dash = value.indexOf('-');
            int end = dot > 0 ? dot : (dash > 0 ? dash : value.length());
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void require(int requiredMajor) {
        int actual = currentMajor();
        if (actual < requiredMajor) {
            throw new IllegalStateException("ModPedia Worker 需要 Java " + requiredMajor + "，当前 JVM 为 Java " + actual + "；请配置独立 Java " + requiredMajor + " 路径");
        }
    }
}
