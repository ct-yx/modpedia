package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.client.ChatMessage;
import io.ctyx.modpedia.client.ConversationSummary;
import io.ctyx.modpedia.client.MessageRole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** 会话文件、历史操作和 LangChain4j memory JSON 适配器的纯 Java 回归测试。 */
public final class ConversationStoreSelfTest {
    private ConversationStoreSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-conversations-");
        try {
            ConversationStore store = new ConversationStore(root);
            String first = store.activeId();
            store.appendMessage(first, new ChatMessage(MessageRole.USER, "压力容器怎么启动？", List.of()));
            store.appendMessage(first, new ChatMessage(MessageRole.ASSISTANT, "请先检查前置条件。", List.of()));
            store.updateMemoryMessages(first, "[memory-json]");

            ConversationRecord created = store.create();
            String second = created.id();
            store.rename(second, "第二个测试会话");
            check(store.select(first), "应能切换到第一个会话");
            check(store.active().messages().size() == 2, "切换后应恢复 UI 消息");
            check("[memory-json]".equals(store.memoryMessagesJson(first)), "应保存 ChatMemory 序列化内容");
            check(store.summaries().stream().map(ConversationSummary::title)
                    .anyMatch("第二个测试会话"::equals), "重命名应更新历史摘要");

            ConversationStore restored = new ConversationStore(root);
            check(first.equals(restored.activeId()), "重启后应恢复活动会话");
            check(restored.active().messages().size() == 2, "重启后应恢复会话消息");
            check("[memory-json]".equals(restored.memoryMessagesJson(first)), "重启后应恢复上下文 JSON");

            restored.delete(first);
            check(!first.equals(restored.activeId()), "删除活动会话后应切换到其他会话");
            restored.delete(second);
            check(!restored.activeId().isBlank(), "删除最后一个会话时应自动创建新会话");

            Files.writeString(root.resolve("conversation-corrupt.json"), "not-json");
            ConversationStore isolated = new ConversationStore(root);
            check(!isolated.activeId().isBlank(), "损坏的单个会话文件不应阻塞其他会话加载");
            System.out.println("ModPedia conversation store self-test passed");
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
