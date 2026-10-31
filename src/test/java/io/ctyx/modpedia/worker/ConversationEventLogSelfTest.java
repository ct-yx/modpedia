package io.ctyx.modpedia.worker;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 会话增量窗口、过期回退和损坏日志回退回归。 */
public final class ConversationEventLogSelfTest {
    private ConversationEventLogSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-conversation-events-");
        try {
            ConversationEventLog log = new ConversationEventLog(root);
            for (int index = 0; index < ConversationEventLog.MAX_EVENTS_PER_CONVERSATION + 4; index++) {
                JsonObject event = WorkerProtocol.message(WorkerProtocol.COMPLETED, "request-" + index);
                event.addProperty("conversation_id", "conversation-one");
                event.addProperty("messages", "message-" + index);
                log.append("conversation-one", event);
            }
            ConversationEventLog.ReplayResult recent = log.replay(
                    "conversation-one", 4L);
            check(recent.available(), "保留窗口内的 v2 会话事件应可重放");
            check(recent.events().size() == ConversationEventLog.MAX_EVENTS_PER_CONVERSATION,
                    "事件重放应返回连续且有界的窗口");
            check(WorkerProtocol.longValue(recent.events().get(0), "revision", -1L) == 5L,
                    "事件重放应从 last_revision+1 开始");
            ConversationEventLog.ReplayResult expired = log.replay("conversation-one", 1L);
            check(!expired.available() && "revision_gap".equals(expired.reason()),
                    "超出保留窗口的历史必须受控回退快照");

            ConversationEventLog restarted = new ConversationEventLog(root);
            ConversationEventLog.ReplayResult afterRestart = restarted.replay(
                    "conversation-one", 8L);
            check(afterRestart.available(), "重启后事件日志应保持可重放");
            check(afterRestart.latestRevision() == ConversationEventLog.MAX_EVENTS_PER_CONVERSATION + 4L,
                    "重启后应恢复最新 revision");

            Path corrupt = root.resolve("events-corrupt.jsonl");
            Files.writeString(corrupt, "{not-json}\n");
            ConversationEventLog corruptLog = new ConversationEventLog(root);
            check(!corruptLog.replay("", 1L).available(), "损坏日志不得伪造可恢复事件");

            JsonObject huge = WorkerProtocol.message(WorkerProtocol.COMPLETED, "huge");
            huge.addProperty("conversation_id", "conversation-huge");
            huge.addProperty("answer", "x".repeat(ConversationEventLog.MAX_EVENT_BYTES));
            ConversationEventLog.ReplayResult beforeHuge = new ConversationEventLog(root)
                    .replay("conversation-huge", 0L);
            check(beforeHuge.available(), "未写入前的空会话窗口应可从零开始");
            new ConversationEventLog(root).append("conversation-huge", huge);
            ConversationEventLog.ReplayResult oversized = new ConversationEventLog(root)
                    .replay("conversation-huge", 0L);
            check(!oversized.available() && "event_too_large".equals(oversized.reason()),
                    "超大事件必须标记为不可重放并回退快照");
            System.out.println("Conversation event log self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
