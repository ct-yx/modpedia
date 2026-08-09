package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;

import java.util.List;

/** 仅搜索模式的 Markdown 完整段落和来源卡片格式回归测试。 */
public final class LocalSearchMessageFormatterSelfTest {
    private LocalSearchMessageFormatterSelfTest() {
    }

    public static void main(String[] args) {
        SearchResult result = new SearchResult(
                "demo:pressure",
                "压力容器",
                "Demo Mod",
                "patchouli_json",
                "机器",
                "1.21.1",
                "demo:entries/pressure",
                "机器 / 压力容器",
                "## 启动步骤\n\n1. 安装控制器。\n2. 接入能源。\n\n```text\npressure = ready\n```",
                120,
                List.of("压力容器")
        );
        ChatMessageAssertions.assertMessage(
                LocalSearchMessageFormatter.format(
                        "压力容器",
                        new SearchResponse(SearchStatus.READY, "压力容器", List.of(result), "")
                ),
                "完整 Markdown 段落应原样保留",
                "```text\npressure = ready\n```"
        );
        ChatMessageAssertions.assertSource(
                LocalSearchMessageFormatter.format(
                        "压力容器",
                        new SearchResponse(SearchStatus.READY, "压力容器", List.of(result), "")
                ),
                "demo:pressure"
        );
        ChatMessageAssertions.assertMessage(
                LocalSearchMessageFormatter.format(
                        "未知内容",
                        new SearchResponse(SearchStatus.NO_MATCH, "未知内容", List.of(), "")
                ),
                "无结果应返回明确提示",
                "未找到"
        );
        System.out.println("ModPedia local search formatter self-test passed");
    }

    private static final class ChatMessageAssertions {
        private static void assertMessage(
                io.ctyx.modpedia.client.ChatMessage message,
                String failure,
                String expected
        ) {
            if (message == null || !message.markdown().contains(expected)) {
                throw new AssertionError(failure + ": " + (message == null ? "null" : message.markdown()));
            }
        }

        private static void assertSource(
                io.ctyx.modpedia.client.ChatMessage message,
                String expectedDocumentId
        ) {
            if (message.sources().stream().noneMatch(source -> expectedDocumentId.equals(source.documentId()))) {
                throw new AssertionError("来源卡片应保留文档 ID");
            }
        }
    }
}
