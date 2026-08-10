package io.ctyx.modpedia.ai;

/** AI 客户端设置；设置页填写的密钥优先，留空时才回退到环境变量。 */
public record AiSettings(
        AssistantMode mode,
        String endpoint,
        String model,
        String apiKey,
        boolean streaming,
        SearchIntensity intensity,
        int maxRounds,
        int maxResults,
        int maxContextChars,
        int timeoutSeconds
) {
    public AiSettings {
        mode = mode == null ? AssistantMode.AI : mode;
        endpoint = endpoint == null ? "" : endpoint.strip();
        model = model == null ? "" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        intensity = intensity == null ? SearchIntensity.STANDARD : intensity;
        maxRounds = clamp(maxRounds, 1, 8);
        maxResults = clamp(maxResults, 1, 20);
        maxContextChars = clamp(maxContextChars, 4_000, 64_000);
        timeoutSeconds = clamp(timeoutSeconds, 10, 300);
    }

    public static AiSettings defaults() {
        SearchIntensity intensity = SearchIntensity.STANDARD;
        return new AiSettings(
                AssistantMode.AI,
                "",
                "",
                "",
                true,
                intensity,
                intensity.rounds(),
                intensity.results(),
                intensity.contextChars(),
                90
        );
    }

    public String effectiveApiKey() {
        return resolveApiKey(apiKey, System.getenv("MODPEDIA_API_KEY"));
    }

    static String resolveApiKey(String configured, String environment) {
        String configuredValue = configured == null ? "" : configured.strip();
        if (!configuredValue.isBlank()) {
            return configuredValue;
        }
        return environment == null ? "" : environment.strip();
    }

    public boolean configured() {
        return !endpoint.isBlank() && !model.isBlank();
    }

    public int effectiveMaxRounds() {
        return intensity == SearchIntensity.CUSTOM ? maxRounds : intensity.rounds();
    }

    public int effectiveMaxResults() {
        return intensity == SearchIntensity.CUSTOM ? maxResults : intensity.results();
    }

    public int effectiveMaxContextChars() {
        return intensity == SearchIntensity.CUSTOM ? maxContextChars : intensity.contextChars();
    }

    public AiSettings withIntensity(SearchIntensity next) {
        SearchIntensity actual = next == null ? SearchIntensity.STANDARD : next;
        return new AiSettings(
                mode,
                endpoint,
                model,
                apiKey,
                streaming,
                actual,
                actual == SearchIntensity.CUSTOM ? maxRounds : actual.rounds(),
                actual == SearchIntensity.CUSTOM ? maxResults : actual.results(),
                actual == SearchIntensity.CUSTOM ? maxContextChars : actual.contextChars(),
                timeoutSeconds
        );
    }

    public AiSettings withMode(AssistantMode next) {
        return new AiSettings(
                next == null ? AssistantMode.AI : next,
                endpoint,
                model,
                apiKey,
                streaming,
                intensity,
                maxRounds,
                maxResults,
                maxContextChars,
                timeoutSeconds
        );
    }

    public AiSettings withModel(String next) {
        return new AiSettings(
                mode,
                endpoint,
                next,
                apiKey,
                streaming,
                intensity,
                maxRounds,
                maxResults,
                maxContextChars,
                timeoutSeconds
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
