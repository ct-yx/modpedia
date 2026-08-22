package io.ctyx.modpedia.worker;

import dev.langchain4j.model.TokenCountEstimator;

import java.util.concurrent.atomic.AtomicInteger;

/** Worker 词表资源异常时的 Token 估算降级回归。 */
public final class WorkerTokenEstimatorSelfTest {
    private WorkerTokenEstimatorSelfTest() {
    }

    public static void main(String[] args) {
        WorkerRuntimeDiagnostics.Snapshot runtime = WorkerRuntimeDiagnostics.inspect();
        check(runtime.estimatorCodeSource().contains("langchain4j-open-ai"),
                "OpenAiTokenCountEstimator CodeSource 不可识别");
        check(runtime.jtokkitCodeSource().contains("jtokkit"),
                "JTokkit CodeSource 不可识别");
        check("1.18.1".equals(runtime.estimatorVersion()),
                "LangChain4j OpenAI 实际版本不为 1.18.1：" + runtime.estimatorVersion());
        check("1.1.0".equals(runtime.jtokkitVersion()),
                "JTokkit 实际版本不为 1.1.0：" + runtime.jtokkitVersion());
        check(runtime.requiredTokenizerResourcesPresent(),
                "JTokkit 必需 tokenizer 资源不完整：" + runtime.summary());
        check(runtime.estimatorInitialized(),
                "OpenAiTokenCountEstimator 初始化失败：" + runtime.estimatorFailureType());
        System.out.println("Worker token runtime diagnostics: " + runtime.summary());

        for (String model : new String[]{"gpt-5", "gpt-5.6-luna", "gpt-4o", "o200k_base"}) {
            TokenCountEstimator exact = WorkerAiSupport.tokenCountEstimator(model);
            check(!(exact instanceof WorkerAiSupport.ApproximateTokenCountEstimator),
                    "模型应使用 JTokkit 精确估算器：" + model);
            check(exact.estimateTokenCountInText("中文与 English token test") > 0,
                    "精确估算器应返回有效结果：" + model);
        }

        TokenCountEstimator fallback = WorkerAiSupport.tokenCountEstimator(
                () -> {
                    throw new ExceptionInInitializerError("missing tiktoken fixture");
                }
        );
        check(fallback instanceof WorkerAiSupport.ApproximateTokenCountEstimator,
                "LinkageError 应降级到近似 Token 估算");
        check(fallback.estimateTokenCountInText("中文测试") > 0,
                "近似 Token 估算应继续可用");

        TokenCountEstimator runtimeFallback = WorkerAiSupport.tokenCountEstimator(
                () -> {
                    throw new NoClassDefFoundError("jtokkit fixture");
                }
        );
        check(runtimeFallback instanceof WorkerAiSupport.ApproximateTokenCountEstimator,
                "NoClassDefFoundError 应降级到近似 Token 估算");
        AtomicInteger retries = new AtomicInteger();
        for (int index = 0; index < 3; index++) {
            WorkerAiSupport.tokenCountEstimator(() -> {
                retries.incrementAndGet();
                throw new NoClassDefFoundError("jtokkit retry fixture");
            });
        }
        check(retries.get() == 3, "初始化失败后每次新请求都应重新尝试或使用降级实现");
        System.out.println("Worker token estimator self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
