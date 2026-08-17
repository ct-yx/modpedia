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
            AiSettingsStore store = new AiSettingsStore(file, "test-machine-a");
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
            String persistedJson = Files.readString(file);
            check(persistedJson.contains("api_key_encrypted"), "API Key 应使用密文节点保存");
            check(!persistedJson.contains("secret-value"), "配置文件不得包含 API Key 明文");
            check(!persistedJson.contains("\"apiKey\""), "配置文件不得包含 apiKey 明文字段");
            AiSettings restored = new AiSettingsStore(file, "test-machine-a").load();
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

            Files.writeString(file, "{\"endpoint\":\"https://legacy.invalid/v1\",\"model\":\"legacy\",\"apiKey\":\"legacy-secret\"}");
            AiSettings migrated = new AiSettingsStore(file, "test-machine-a").load();
            check("legacy-secret".equals(migrated.apiKey()), "旧版明文 API Key 应能读取一次并迁移");
            String migratedJson = Files.readString(file);
            check(migratedJson.contains("api_key_encrypted"), "旧版明文 API Key 应迁移为密文节点");
            check(!migratedJson.contains("legacy-secret"), "迁移后配置文件不得保留旧版明文 API Key");

            Files.writeString(file, "{\"endpoint\":\"https://legacy.invalid/v1\",\"model\":\"legacy\"}");
            check(new AiSettingsStore(file, "test-machine-a").load().mode() == AssistantMode.AI,
                    "缺少 mode 的旧配置应默认使用 AI 模式");

            store = new AiSettingsStore(file, "test-machine-a");
            store.save(restored.withMode(AssistantMode.SEARCH_ONLY));
            check(new AiSettingsStore(file, "test-machine-a").load().mode() == AssistantMode.SEARCH_ONLY,
                    "仅搜索模式应可持久化");

            AiSettingsStore mismatchSource = new AiSettingsStore(file, "test-machine-a");
            mismatchSource.save(restored);
            AiSettings mismatched = new AiSettingsStore(file, "test-machine-b").load();
            check(mismatched.apiKey().isBlank(), "系统标识变化后 API Key 应从内存设置中清除");
            String purgedJson = Files.readString(file);
            check(!purgedJson.contains("api_key_encrypted"), "系统标识变化后应删除密钥密文节点");
            check(!purgedJson.contains("apiKey"), "系统标识变化后不得留下 apiKey 字段");

            AiSettingsStore cacheStore = new AiSettingsStore(file, "test-machine-b");
            cacheStore.save(restored.withMode(AssistantMode.SEARCH_ONLY));
            AiSettings firstLoad = cacheStore.load();
            Files.writeString(file, "{\"endpoint\":\"https://external.invalid/v1\",\"model\":\"external\"}");
            check(firstLoad.equals(cacheStore.load()), "同一进程应复用已解密的内存缓存");

            AiSettings standard = restored.withIntensity(SearchIntensity.STANDARD);
            check(standard.effectiveMaxRounds() == 3, "标准档位应使用 3 轮预算");
            check(standard.effectiveMaxResults() == 8, "标准档位应使用 8 条结果预算");
            check(standard.effectiveMaxContextChars() == 16_000, "标准档位应使用 16000 字符预算");
            migrateConfiguredFileIfRequested();
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

    private static void migrateConfiguredFileIfRequested() {
        String configured = System.getProperty("modpedia.aiSettingsFile", "").strip();
        if (configured.isBlank()) {
            return;
        }
        AiSettings settings = new AiSettingsStore(Path.of(configured)).load();
        check(settings != null, "指定的共享 AI 配置应可读取");
        System.out.println("Shared AI settings loaded: path=" + Path.of(configured).toAbsolutePath()
                + ", apiKeyPresent=" + !settings.apiKey().isBlank()
                + ", apiKeyLength=" + settings.apiKey().length());
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
