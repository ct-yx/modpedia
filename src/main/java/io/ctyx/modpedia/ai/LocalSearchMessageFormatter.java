package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.client.ChatMessage;
import io.ctyx.modpedia.client.MessageRole;
import io.ctyx.modpedia.client.SourceReference;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;

import java.util.List;

/** 将本地搜索响应转换为玩家可读的完整 Markdown 消息。 */
public final class LocalSearchMessageFormatter {
    private LocalSearchMessageFormatter() {
    }

    public static ChatMessage format(String query, SearchResponse response) {
        SearchResponse actual = response == null
                ? new SearchResponse(SearchStatus.INDEX_ERROR, query, List.of(), "搜索响应为空")
                : response;
        List<SourceReference> sources = actual.results().stream()
                .map(LocalSearchMessageFormatter::sourceOf)
                .toList();

        String markdown = switch (actual.status()) {
            case READY -> readyMarkdown(query, actual.results());
            case EMPTY_QUERY -> "请输入要搜索的模组、物品、机器或手册关键词。";
            case NO_MATCH -> "未找到与 **" + safe(query) + "** 直接匹配的手册段落。\n\n"
                    + "可以尝试使用模组 ID、物品 ID、机器名，或换一种中文/英文关键词。";
            case INDEX_NOT_READY -> "本地知识库尚未生成。请等待启动扫描完成，或按 **F9** 重建后重试。";
            case INDEX_ERROR -> "本地知识库读取失败："
                    + (actual.error().isBlank() ? "请按 **F9** 重建后重试。" : actual.error());
        };
        return new ChatMessage(MessageRole.ASSISTANT, markdown, sources);
    }

    private static String readyMarkdown(String query, List<SearchResult> results) {
        StringBuilder markdown = new StringBuilder()
                .append("本地搜索命中 ")
                .append(results.size())
                .append(" 条完整段落。\n\n")
                .append("查询：**")
                .append(safe(query))
                .append("**\n\n");
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            String title = result.title().isBlank() ? result.documentId() : result.title();
            markdown.append("### ")
                    .append(index + 1)
                    .append(". ")
                    .append(title)
                    .append(" [来源: ")
                    .append(result.documentId())
                    .append(" | 标注: ")
                    .append(citationLabel(title))
                    .append("]")
                    .append("\n");
            if (!result.headingPath().isBlank()) {
                markdown.append("位置：").append(result.headingPath()).append("\n");
            }
            markdown.append("匹配分：").append(result.score());
            if (!result.matchedTerms().isEmpty()) {
                markdown.append(" · 命中：")
                        .append(String.join(", ", result.matchedTerms()));
            }
            markdown.append("\n\n")
                    .append(result.segmentMarkdown())
                    .append("\n\n");
        }
        return markdown.toString().strip();
    }

    private static SourceReference sourceOf(SearchResult result) {
        String title = result.title().isBlank() ? result.documentId() : result.title();
        return new SourceReference(result.documentId(), title, result.sourceMod(), result.sourcePath());
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ").strip();
    }

    private static String citationLabel(String value) {
        return safe(value).replace("]", "）").replace("|", "·");
    }
}
