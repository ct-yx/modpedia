package io.ctyx.modpedia.worker;

import dev.langchain4j.model.TokenCountEstimator;

/** Worker 词表资源异常时的 Token 估算降级回归。 */
public final class WorkerTokenEstimatorSelfTest {
    private WorkerTokenEstimatorSelfTest() {
    }

    public static void main(String[] args) {
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
        System.out.println("Worker token estimator self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
