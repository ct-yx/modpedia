package io.ctyx.modpedia.worker;

import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import java.net.URL;
import java.security.CodeSource;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker 运行时依赖诊断。
 *
 * <p>这里只记录类来源、推断出的构件版本、词表资源是否存在和初始化错误类型；不读取
 * AI 配置、不记录 API Key、请求正文或会话正文。它也不依赖游戏类加载器，方便在独立
 * Worker JVM 和发布 JAR 自测中复用。</p>
 */
final class WorkerRuntimeDiagnostics {
    private static final Logger LOG = Logger.getLogger("ModPediaWorker");
    private WorkerRuntimeDiagnostics() {
    }

    static Snapshot inspect() {
        ClassLoader loader = WorkerRuntimeDiagnostics.class.getClassLoader();
        Class<?> estimatorClass = loadWithoutInitialization(
                "dev.langchain4j.model.openai.OpenAiTokenCountEstimator", loader
        );
        Class<?> jtokkitClass = loadWithoutInitialization(
                "com.knuddels.jtokkit.Encodings", loader
        );
        String estimatorSource = codeSource(estimatorClass);
        String jtokkitSource = codeSource(jtokkitClass);
        String estimatorVersion = artifactVersion(estimatorSource, "langchain4j-open-ai");
        String jtokkitVersion = artifactVersion(jtokkitSource, "jtokkit");
        boolean o200k = resource(loader, "o200k_base.tiktoken");
        boolean cl100k = resource(loader, "cl100k_base.tiktoken");
        boolean p50k = resource(loader, "p50k_base.tiktoken");
        boolean r50k = resource(loader, "r50k_base.tiktoken");
        boolean legacy54k = resource(loader, "5.4k_base.tiktoken");

        boolean estimatorInitialized = false;
        String estimatorFailureType = "";
        try {
            // gpt-5 走 o200k_base；这里只做本地初始化，不发起网络请求。
            new OpenAiTokenCountEstimator("gpt-5");
            estimatorInitialized = true;
        } catch (RuntimeException | LinkageError failure) {
            estimatorFailureType = failure.getClass().getSimpleName();
        }
        return new Snapshot(
                estimatorSource,
                estimatorVersion,
                jtokkitSource,
                jtokkitVersion,
                o200k,
                cl100k,
                p50k,
                r50k,
                legacy54k,
                estimatorInitialized,
                estimatorFailureType
        );
    }

    static void logStartup() {
        Snapshot snapshot = inspect();
        LOG.info(() -> "WORKER_DEPENDENCY " + snapshot.summary());
        if (!snapshot.requiredTokenizerResourcesPresent()) {
            LOG.warning(() -> "WORKER_DEPENDENCY_WARNING tokenizer resources incomplete"
                    + " required=o200k_base,cl100k_base,p50k_base,r50k_base");
        }
        if (!snapshot.estimatorInitialized()) {
            LOG.log(Level.WARNING,
                    "WORKER_DEPENDENCY_WARNING OpenAiTokenCountEstimator initialization failed"
                            + " error_type={0}; AI requests will use approximate token counting",
                    snapshot.estimatorFailureType());
        }
    }

    private static Class<?> loadWithoutInitialization(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static String codeSource(Class<?> type) {
        if (type == null) {
            return "<unavailable>";
        }
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null
                    ? "<unknown>"
                    : source.getLocation().toExternalForm();
        } catch (SecurityException exception) {
            return "<restricted>";
        }
    }

    private static String artifactVersion(String source, String artifact) {
        if (source == null || source.isBlank() || source.startsWith("<")) {
            return "<unknown>";
        }
        String file = source;
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        if (slash >= 0) {
            file = file.substring(slash + 1);
        }
        String prefix = artifact + "-";
        int start = file.indexOf(prefix);
        if (start < 0) {
            return "<unknown>";
        }
        String version = file.substring(start + prefix.length());
        if (version.endsWith(".jar")) {
            version = version.substring(0, version.length() - 4);
        }
        return version.isBlank() ? "<unknown>" : version;
    }

    private static boolean resource(ClassLoader loader, String fileName) {
        if (loader == null) {
            return false;
        }
        URL value = loader.getResource("com/knuddels/jtokkit/" + fileName);
        return value != null;
    }

    record Snapshot(
            String estimatorCodeSource,
            String estimatorVersion,
            String jtokkitCodeSource,
            String jtokkitVersion,
            boolean o200kBase,
            boolean cl100kBase,
            boolean p50kBase,
            boolean r50kBase,
            boolean legacy54k,
            boolean estimatorInitialized,
            String estimatorFailureType
    ) {
        boolean requiredTokenizerResourcesPresent() {
            return o200kBase && cl100kBase && p50kBase && r50kBase;
        }

        String summary() {
            return "langchain_openai_code_source=" + estimatorCodeSource
                    + " langchain_openai_version=" + estimatorVersion
                    + " jtokkit_code_source=" + jtokkitCodeSource
                    + " jtokkit_version=" + jtokkitVersion
                    + " tokenizer_o200k_base=" + o200kBase
                    + " tokenizer_cl100k_base=" + cl100kBase
                    + " tokenizer_p50k_base=" + p50kBase
                    + " tokenizer_r50k_base=" + r50kBase
                    + " tokenizer_5_4k_base=" + legacy54k
                    + " estimator_gpt5=" + estimatorInitialized
                    + " estimator_error_type=" + Objects.toString(estimatorFailureType, "");
        }
    }
}
