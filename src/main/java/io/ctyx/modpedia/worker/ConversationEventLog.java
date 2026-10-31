package io.ctyx.modpedia.worker;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 侧每会话增量事件日志。
 *
 * <p>日志只保存协议事件的有限窗口，避免断线恢复依赖无限增长的
 * {@code conversation.state}。单个损坏、缺号或超大事件会把该会话标为不可重放，
 * 调用方随后必须返回受控快照。</p>
 */
public final class ConversationEventLog {
    /** 新客户端一次断线恢复最多需要的事件窗口。 */
    public static final int MAX_EVENTS_PER_CONVERSATION = 128;
    /** 单条持久化事件上限，远小于 JSONL 行上限。 */
    public static final int MAX_EVENT_BYTES = 256 * 1024;
    /** 单会话日志总大小上限。 */
    public static final int MAX_LOG_BYTES = 1024 * 1024;

    private final Path root;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ConversationEventLog(Path root) {
        this.root = root.toAbsolutePath().normalize();
        load();
    }

    /** 为事件分配单调 revision，并持久化到有界窗口。 */
    public synchronized JsonObject append(String conversationId, JsonObject event) {
        String id = normalizeId(conversationId);
        if (id.isBlank() || event == null) {
            return event;
        }
        State state = states.computeIfAbsent(id, ignored -> new State());
        long revision = state.latestRevision + 1L;
        JsonObject recorded = event.deepCopy();
        recorded.addProperty("conversation_id", id);
        recorded.addProperty("revision", revision);
        state.latestRevision = revision;
        int bytes = WorkerProtocol.utf8Length(recorded.toString());
        if (state.corrupt || bytes > MAX_EVENT_BYTES) {
            state.corrupt = true;
            state.oversized = bytes > MAX_EVENT_BYTES;
            persistMarker(id, state);
            return recorded;
        }
        state.events.addLast(recorded);
        trim(state);
        if (!persist(id, state)) {
            state.corrupt = true;
        }
        return recorded;
    }

    public synchronized ReplayResult replay(String conversationId, long afterRevision) {
        String id = normalizeId(conversationId);
        State state = states.get(id);
        if (state == null) {
            return afterRevision <= 0L
                    ? ReplayResult.available(List.of(), 0L)
                    : ReplayResult.unavailable(0L, "history_missing");
        }
        if (state.corrupt || afterRevision < 0L) {
            return ReplayResult.unavailable(state.latestRevision,
                    state.oversized ? "event_too_large" : "event_log_corrupt");
        }
        long expected = afterRevision + 1L;
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject event : state.events) {
            long revision = WorkerProtocol.longValue(event, "revision", -1L);
            if (revision < expected) {
                continue;
            }
            if (revision != expected) {
                return ReplayResult.unavailable(state.latestRevision, "revision_gap");
            }
            if (WorkerProtocol.utf8Length(event.toString()) > MAX_EVENT_BYTES) {
                return ReplayResult.unavailable(state.latestRevision, "event_too_large");
            }
            result.add(event.deepCopy());
            expected++;
        }
        if (expected != state.latestRevision + 1L) {
            return ReplayResult.unavailable(state.latestRevision, "history_expired");
        }
        return ReplayResult.available(result, state.latestRevision);
    }

    public synchronized long latestRevision(String conversationId) {
        State state = states.get(normalizeId(conversationId));
        return state == null ? 0L : state.latestRevision;
    }

    /** 删除会话时同步删除其事件窗口。 */
    public synchronized void delete(String conversationId) {
        String id = normalizeId(conversationId);
        if (id.isBlank()) {
            return;
        }
        states.remove(id);
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException ignored) {
            // 下一次加载时仍会忽略已删除会话的残留事件文件。
        }
    }

    private void load() {
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (var files = Files.newDirectoryStream(root, "events-*.jsonl")) {
                for (Path file : files) {
                    loadFile(file);
                }
            }
        } catch (IOException ignored) {
            // 单个日志不可读时，恢复请求会回退快照，不阻断 Worker 启动。
        }
    }

    private void loadFile(Path file) {
        State state = null;
        String id = "";
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (WorkerProtocol.utf8Length(line) > MAX_EVENT_BYTES) {
                    if (!id.isBlank()) {
                        state = states.computeIfAbsent(id, ignored -> new State());
                    }
                    if (state != null) {
                        state.corrupt = true;
                        state.oversized = true;
                    }
                    return;
                }
                JsonObject event;
                try {
                    event = WorkerProtocol.parse(line);
                } catch (RuntimeException exception) {
                    if (state != null) {
                        state.corrupt = true;
                    }
                    return;
                }
                String eventId = normalizeId(WorkerProtocol.string(event, "conversation_id"));
                if (!eventId.isBlank()) {
                    id = eventId;
                    state = states.computeIfAbsent(id, ignored -> new State());
                }
                if (state == null || WorkerProtocol.bool(event, "event_log_corrupt", false)) {
                    if (state != null) {
                        state.corrupt = true;
                        state.oversized = "event_too_large".equals(
                                WorkerProtocol.string(event, "reason"));
                        state.latestRevision = Math.max(state.latestRevision,
                                WorkerProtocol.longValue(event, "revision", 0L));
                    }
                    continue;
                }
                long revision = WorkerProtocol.longValue(event, "revision", -1L);
                boolean firstRetainedEvent = state.events.isEmpty() && state.latestRevision == 0L;
                if (revision <= 0L || (!firstRetainedEvent && revision != state.latestRevision + 1L)) {
                    state.corrupt = true;
                    state.latestRevision = Math.max(state.latestRevision, revision);
                    continue;
                }
                state.latestRevision = revision;
                state.events.addLast(event);
            }
            if (state != null) {
                trim(state);
            }
        } catch (IOException | RuntimeException exception) {
            if (state != null) {
                state.corrupt = true;
            }
        }
    }

    private boolean persist(String id, State state) {
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, "events-", ".tmp");
            StringBuilder content = new StringBuilder();
            for (JsonObject event : state.events) {
                content.append(event).append('\n');
            }
            Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8);
            moveAtomically(temporary, fileFor(id));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void persistMarker(String id, State state) {
        // Marker 保持很小，重启后仍能知道窗口已失效；正文不会被再次发送。
        JsonObject marker = new JsonObject();
        marker.addProperty("conversation_id", id);
        marker.addProperty("revision", state.latestRevision);
        marker.addProperty("event_log_corrupt", true);
        marker.addProperty("reason", state.oversized ? "event_too_large" : "event_log_corrupt");
        try {
            Files.createDirectories(root);
            Files.writeString(fileFor(id), marker + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 内存状态仍会使本次运行回退快照。
        }
    }

    private static void trim(State state) {
        int bytes = 0;
        for (JsonObject event : state.events) {
            bytes += WorkerProtocol.utf8Length(event.toString()) + 1;
        }
        while (!state.events.isEmpty()
                && (state.events.size() > MAX_EVENTS_PER_CONVERSATION || bytes > MAX_LOG_BYTES)) {
            JsonObject removed = state.events.removeFirst();
            bytes -= WorkerProtocol.utf8Length(removed.toString()) + 1;
        }
    }

    private Path fileFor(String id) {
        return root.resolve("events-" + digest(id) + ".jsonl");
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.strip();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class State {
        private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
        private long latestRevision;
        private boolean corrupt;
        private boolean oversized;
    }

    public record ReplayResult(
            List<JsonObject> events,
            long latestRevision,
            boolean available,
            String reason
    ) {
        public ReplayResult {
            events = events == null ? List.of() : List.copyOf(events);
            reason = reason == null ? "" : reason;
        }

        static ReplayResult available(List<JsonObject> events, long latestRevision) {
            return new ReplayResult(events, latestRevision, true, "");
        }

        static ReplayResult unavailable(long latestRevision, String reason) {
            return new ReplayResult(List.of(), latestRevision, false, reason);
        }
    }
}
