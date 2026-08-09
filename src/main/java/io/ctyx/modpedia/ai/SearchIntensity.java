package io.ctyx.modpedia.ai;

/** AI 工具搜索预算预设。 */
public enum SearchIntensity {
    FAST(1, 4, 8_000),
    STANDARD(3, 8, 16_000),
    DEEP(5, 12, 28_000),
    CUSTOM(3, 8, 16_000);

    private final int rounds;
    private final int results;
    private final int contextChars;

    SearchIntensity(int rounds, int results, int contextChars) {
        this.rounds = rounds;
        this.results = results;
        this.contextChars = contextChars;
    }

    public int rounds() {
        return rounds;
    }

    public int results() {
        return results;
    }

    public int contextChars() {
        return contextChars;
    }

    public static SearchIntensity parse(String value) {
        if (value == null) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }
}
