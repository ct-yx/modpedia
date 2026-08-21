package io.ctyx.modpedia.ai;


import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.search.SearchResponse;
import io.ctyx.modpedia.search.SearchResult;
import io.ctyx.modpedia.search.SearchStatus;
import io.ctyx.modpedia.search.ItemCatalogEntry;

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
        io.ctyx.modpedia.api.ChatMessage formatted = LocalSearchMessageFormatter.format(
                "压力容器",
                new SearchResponse(SearchStatus.READY, "压力容器", List.of(result), "")
        );
        ChatMessageAssertions.assertMessage(
                formatted,
                "完整 Markdown 段落应原样保留",
                "```text\npressure = ready\n```"
        );
        if (!formatted.markdown().contains("[来源: demo:pressure | 标注: 压力容器]")) {
            throw new AssertionError("仅搜索结果应把来源标记放在对应结果标题中");
        }
        ChatMessageAssertions.assertSource(
                formatted,
                "demo:pressure"
        );
        io.ctyx.modpedia.api.ChatMessage withItem = LocalSearchMessageFormatter.format(
                "[[item:demo:pressure|压力容器]] 怎么使用",
                new SearchResponse(SearchStatus.NO_MATCH, "", List.of(), ""),
                List.of(new ItemCatalogEntry(
                        "demo:pressure",
                        "zh_cn",
                        "压力容器",
                        "- 需要能源\n- 需要控制器",
                        "demo",
                        "item-v1"
                ))
        );
        ChatMessageAssertions.assertMessage(withItem, "仅搜索模式应显示物品目录简介", "需要能源");
        ChatMessageAssertions.assertMessage(withItem, "物品上下文应使用可渲染令牌", "[[item:demo:pressure|压力容器]]");
        if (!withItem.sources().isEmpty()) {
            throw new AssertionError("物品目录内容不应生成手册来源卡片");
        }
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
                io.ctyx.modpedia.api.ChatMessage message,
                String failure,
                String expected
        ) {
            if (message == null || !message.markdown().contains(expected)) {
                throw new AssertionError(failure + ": " + (message == null ? "null" : message.markdown()));
            }
        }

        private static void assertSource(
                io.ctyx.modpedia.api.ChatMessage message,
                String expectedDocumentId
        ) {
            if (message.sources().stream().noneMatch(source -> expectedDocumentId.equals(source.documentId()))) {
                throw new AssertionError("来源卡片应保留文档 ID");
            }
        }
    }
}
