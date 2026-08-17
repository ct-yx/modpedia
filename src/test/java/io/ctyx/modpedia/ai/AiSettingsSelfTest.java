package io.ctyx.modpedia.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** AI 设置的边界归一化、持久化和搜索预算回归测试。 */
public final class AiSettingsSelfTest {
    private AiSettingsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-ai-settings-");
        try {
            Path file = root.resolve("ai.json");
            AiSettingsStore store = new AiSettingsStore(file);
            AiSettings settings = new AiSettings(
                    AssistantMode.AI,
                    " https://example.invalid/v1 ",
                    " test-model ",
                    " secret-value ",
                    false,
                    SearchIntensity.CUSTOM,
                    99,
                    0,
                    100_000,
                    1
            );
            check(store.save(settings), "设置保存后应能回读校验");
            AiSettings restored = store.load();
            check("https://example.invalid/v1".equals(restored.endpoint()), "API 地址应去除首尾空白");
            check("https://example.invalid/v1".equals(
                            AiSettings.normalizeEndpoint("https://example.invalid/v1/chat/completions")),
                    "完整 Chat Completions 地址应归一化为 API 根地址");
            check("test-model".equals(restored.model()), "模型名称应去除首尾空白");
            check(restored.maxRounds() == 8, "最大轮数应限制到 8");
            check(restored.maxResults() == 1, "每轮结果应限制到 1");
            check(restored.maxContextChars() == 64_000, "上下文字符数应限制到 64000");
            check(restored.timeoutSeconds() == 10, "超时应限制到 10 秒");
            check(restored.effectiveMaxRounds() == 8, "自定义搜索预算应使用自定义轮数");
            check("secret-value".equals(restored.apiKey()),
                    "API Key 应去除复制时可能带入的首尾空白");
            check("secret-value".equals(AiSettings.resolveApiKey("secret-value", "stale-environment-key")),
                    "设置页填写的 Key 应优先于旧环境变量");
            check("environment-key".equals(AiSettings.resolveApiKey("", " environment-key ")),
                    "设置页为空时应回退到环境变量");
            check(!new AiSettings(
                            AssistantMode.AI,
                            "https://example.invalid/v1",
                            "test-model",
                            "",
                            false,
                            SearchIntensity.FAST,
                            1,
                            4,
                            8_000,
                            10
                    ).requestConfigured(),
                    "没有配置 API Key 时不能进入模型请求链路");
            check(restored.mode() == AssistantMode.AI, "旧版默认设置应使用 AI 模式");

            Files.writeString(file, "{\"endpoint\":\"https://legacy.invalid/v1\",\"model\":\"legacy\"}");
            check(store.load().mode() == AssistantMode.AI, "缺少 mode 的旧配置应默认使用 AI 模式");

            store.save(restored.withMode(AssistantMode.SEARCH_ONLY));
            check(store.load().mode() == AssistantMode.SEARCH_ONLY, "仅搜索模式应可持久化");

            AiSettings standard = restored.withIntensity(SearchIntensity.STANDARD);
            check(standard.effectiveMaxRounds() == 3, "标准档位应使用 3 轮预算");
            check(standard.effectiveMaxResults() == 8, "标准档位应使用 8 条结果预算");
            check(standard.effectiveMaxContextChars() == 16_000, "标准档位应使用 16000 字符预算");
            System.out.println("ModPedia AI settings self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
