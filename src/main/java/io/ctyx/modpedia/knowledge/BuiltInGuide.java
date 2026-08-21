package io.ctyx.modpedia.knowledge;

import io.ctyx.modpedia.api.SourceReference;
import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 欢迎页可以直接打开的 ModPedia 内置说明，不经过 AI 请求。 */
public final class BuiltInGuide {
    public static final String ASSISTANT_USAGE_DOCUMENT_ID = "modpedia:guide/assistant-usage";
    private static final String ASSISTANT_USAGE_RESOURCE =
            "/assets/modpedia/guides/modpedia/guide/assistant-usage.md";
    private static final String PATCHOULI_SOURCE_PATH =
            "assets/modpedia/patchouli_books/modpedia/entries/introduction.json";

    private BuiltInGuide() {
    }

    public static boolean isSupported(String documentId) {
        return ASSISTANT_USAGE_DOCUMENT_ID.equals(documentId);
    }

    /**
     * 识别没有指定其它内容模组的“如何开始使用”问题。
     *
     * <p>这类问题不应该交给模型猜测“这个模组”指谁，否则欢迎页之外的自然语言输入
     * 可能被错误检索到整合包中的任意内容模组。只匹配明确的通用短句，带有命名空间
     * ID 或额外实体描述时继续走普通知识检索。</p>
     */
    public static boolean isUsageQuestion(String query) {
        String compact = compact(query)
                .replaceFirst("^(?:请问|告诉我|我想知道|我想了解)", "");
        if (compact.isBlank() || compact.matches(".*[a-z0-9_]+:[a-z0-9_./-]+.*")) {
            return false;
        }
        Set<String> exact = Set.of(
                "如何开始使用这个模组",
                "怎么开始使用这个模组",
                "如何使用这个模组",
                "这个模组怎么用",
                "这个模组如何使用",
                "如何开始使用本模组",
                "怎么开始使用本模组",
                "本模组怎么用",
                "如何开始使用这个助手",
                "怎么开始使用这个助手",
                "这个助手怎么用",
                "这个助手如何使用",
                "如何使用modpedia",
                "怎么使用modpedia",
                "modpedia怎么用",
                "modpedia如何使用",
                "howtousethismod",
                "howdoiusethismod",
                "howtogetstartedwiththismod",
                "howtousethisassistant",
                "howdoiusethisassistant",
                "howtousemodpedia",
                "howtogetstartedwithmodpedia"
        );
        if (exact.contains(compact)) {
            return true;
        }
        return compact.matches("(?:如何|怎么)(?:开始)?使用(?:这个|本)模组")
                || compact.matches("(?:这个|本)模组(?:怎么|如何)(?:使用|用)")
                || compact.matches("(?:如何|怎么)(?:开始)?使用(?:这个|本)助手")
                || compact.matches("(?:这个|本)助手(?:怎么|如何)(?:使用|用)")
                || compact.matches("(?:如何|怎么)(?:开始)?使用modpedia")
                || compact.matches("modpedia(?:怎么|如何)(?:使用|用)");
    }

    /** 优先读取当前 JAR 内的事实源，资源不可用时再回退到 SQLite。 */
    public static Optional<String> readMarkdown(
            String documentId,
            RetrievalService retrievalService,
            SearchLanguage language
    ) {
        if (!isSupported(documentId)) {
            return Optional.empty();
        }
        try (InputStream stream = BuiltInGuide.class.getResourceAsStream(ASSISTANT_USAGE_RESOURCE)) {
            if (stream != null) {
                return Optional.of(renderableMarkdown(new String(
                        stream.readAllBytes(),
                        StandardCharsets.UTF_8
                )));
            }
        } catch (IOException ignored) {
            // 继续尝试读取 SQLite 派生库。
        }
        if (retrievalService == null) {
            return Optional.empty();
        }
        retrievalService.setLanguage(language);
        return retrievalService.readMarkdown(documentId).map(BuiltInGuide::renderableMarkdown);
    }

    public static String prompt() {
        return "查看 ModPedia 助手使用说明";
    }

    public static SourceReference source() {
        return new SourceReference(
                ASSISTANT_USAGE_DOCUMENT_ID,
                "ModPedia 助手使用说明",
                "modpedia",
                PATCHOULI_SOURCE_PATH
        );
    }

    /** 去掉知识库元数据，只把正文交给现有 Markdown 渲染器。 */
    public static String renderableMarkdown(String markdown) {
        String normalized = markdown == null
                ? ""
                : markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            return normalized.strip();
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return normalized.strip();
        }
        int bodyStart = end + "\n---".length();
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return normalized.substring(bodyStart).strip();
    }

    private static String compact(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "")
                .strip();
    }
}
