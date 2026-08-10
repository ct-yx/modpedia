package io.ctyx.modpedia.client;

import io.ctyx.modpedia.search.RetrievalService;
import io.ctyx.modpedia.search.SearchLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
}
