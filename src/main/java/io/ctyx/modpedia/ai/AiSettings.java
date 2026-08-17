package io.ctyx.modpedia.ai;

import java.net.URI;
import java.util.List;
import java.util.Locale;

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
        endpoint = normalizeEndpoint(endpoint);
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

    /** 模型请求真正需要的完整配置；仅搜索模式不调用此检查。 */
    public boolean requestConfigured() {
        return configured() && !effectiveApiKey().isBlank();
    }

    /** 统一设置页、连接测试、模型列表和真实对话使用的 API 根地址。 */
    public static String normalizeEndpoint(String endpoint) {
        String value = stripTrailingSlash(endpoint == null ? "" : endpoint.strip());
        if (value.isBlank()) {
            return "";
        }
        for (String suffix : List.of("/chat/completions", "/models")) {
            if (value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                value = stripTrailingSlash(value.substring(0, value.length() - suffix.length()));
                break;
            }
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (uri.isAbsolute() && (path == null || path.isBlank() || "/".equals(path))) {
                return value + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
            // 保留原值，让真正请求链路给出地址格式错误。
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
