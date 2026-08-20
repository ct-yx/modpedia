package io.ctyx.modpedia.ai;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** AI 客户端设置；设置页填写的密钥优先，留空时才回退到环境变量。 */
public record AiSettings(
        AssistantMode mode,
        AiApiFormat apiFormat,
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
    /** 兼容旧版调用方：没有协议字段时继续使用原来的 Chat Completions。 */
    public AiSettings(
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
        this(
                mode,
                AiApiFormat.CHAT_COMPLETIONS,
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

    public AiSettings {
        mode = mode == null ? AssistantMode.AI : mode;
        apiFormat = apiFormat == null ? AiApiFormat.CHAT_COMPLETIONS : apiFormat;
        endpoint = normalizeEndpoint(endpoint, apiFormat);
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
                AiApiFormat.CHAT_COMPLETIONS,
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
        return normalizeEndpoint(endpoint, AiApiFormat.CHAT_COMPLETIONS);
    }

    /** 按协议处理完整端点误填和不同服务的默认版本路径。 */
    public static String normalizeEndpoint(String endpoint, AiApiFormat apiFormat) {
        String value = stripTrailingSlash(endpoint == null ? "" : endpoint.strip());
        if (value.isBlank()) {
            return "";
        }
        // API Key 统一放在请求头；用户从文档复制带 query 的完整端点时，不能让
        // query 阻止下面的协议后缀识别，也不能把它带入模型列表和聊天 URL。
        int queryStart = value.indexOf('?');
        if (queryStart >= 0) {
            value = value.substring(0, queryStart);
        }
        AiApiFormat format = apiFormat == null ? AiApiFormat.CHAT_COMPLETIONS : apiFormat;
        List<String> suffixes = switch (format) {
            case NATIVE_MESSAGES -> List.of("/messages", "/models");
            case RESPONSES -> List.of("/responses", "/models");
            case GENERATE_CONTENT -> List.of(":streamGenerateContent", ":generateContent", "/models");
            case CHAT_COMPLETIONS -> List.of("/chat/completions", "/models");
        };
        boolean removedProtocolSuffix = false;
        for (String suffix : suffixes) {
            if (value.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
                value = stripTrailingSlash(value.substring(0, value.length() - suffix.length()));
                removedProtocolSuffix = true;
                break;
            }
        }
        if (format == AiApiFormat.GENERATE_CONTENT && removedProtocolSuffix) {
            // Gemini 的完整调用地址是 /v1beta/models/<model>:generateContent；
            // 归一化后只保留 /v1beta，否则请求会再次拼出 /models/<model>。
            String lower = value.toLowerCase(Locale.ROOT);
            int modelsPath = lower.lastIndexOf("/models/");
            if (modelsPath >= 0) {
                value = stripTrailingSlash(value.substring(0, modelsPath));
            }
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (uri.isAbsolute() && (path == null || path.isBlank() || "/".equals(path))) {
                return value + (format == AiApiFormat.GENERATE_CONTENT ? "/v1beta" : "/v1");
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
                apiFormat,
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
                apiFormat,
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
                apiFormat,
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

    public AiSettings withApiFormat(AiApiFormat next) {
        AiApiFormat actual = next == null ? AiApiFormat.CHAT_COMPLETIONS : next;
        return new AiSettings(
                mode,
                actual,
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
