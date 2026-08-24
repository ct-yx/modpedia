package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.api.MessageRole;
import io.ctyx.modpedia.api.SourceReference;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ctyx.modpedia.api.ChatMessage;
import io.ctyx.modpedia.api.ConversationSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** 本地会话仓库；AI 上下文窗口由 LangChain4j 管理，本类负责 ModPedia 的 UI 历史。 */
public final class ConversationStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String INDEX_FILE = "index.json";
    private static final int MAX_MESSAGE_COUNT = 200;
    private static final int MAX_MESSAGE_MARKDOWN_CHARS = 64_000;
    private static final int MAX_TRACE_COUNT = 200;
    private static final int MAX_TRACE_SOURCES = 16;
    private static final int MAX_MEMORY_MESSAGES_CHARS = 1_000_000;
    private static final long MAX_CONVERSATION_FILE_BYTES = 8L * 1024L * 1024L;

    private final Path root;
    private final Map<String, ConversationRecord> records = new LinkedHashMap<>();
    private String activeId;

    public ConversationStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        load();
    }

    // Worker 只接收显式路径；客户端运行时路径由 AiAssistantSession 适配层提供。
    public synchronized List<ConversationSummary> summaries() {
        return records.values().stream()
                .filter(record -> !isEmptyDraft(record))
                .sorted(Comparator.comparingLong(ConversationRecord::updatedAt).reversed())
                .map(record -> new ConversationSummary(
                        record.id(),
                        record.title(),
                        record.updatedAt(),
                        record.messages().size()
                ))
                .toList();
    }

    public synchronized String activeId() {
        ensureActive();
        return activeId;
    }

    public synchronized ConversationRecord active() {
        ensureActive();
        return records.get(activeId);
    }

    public synchronized ConversationRecord get(String id) {
        return records.get(id);
    }

    public synchronized ConversationRecord create() {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        ConversationRecord record = new ConversationRecord(
                id,
                "新会话",
                now,
                now,
                List.of(),
                List.of(),
                ""
        );
        records.put(id, record);
        activeId = id;
        // 新会话先作为内存草稿存在。首条用户消息写入后，replace() 才会创建文件
        // 和出现在历史摘要中，避免“新建但未修改”的空会话污染历史记录。
        persistIndex();
        return record;
    }

    public synchronized boolean select(String id) {
        if (!records.containsKey(id)) {
            return false;
        }
        activeId = id;
        persistIndex();
        return true;
    }

    public synchronized void rename(String id, String title) {
        ConversationRecord current = records.get(id);
        if (current == null) {
            return;
        }
        String nextTitle = title == null ? "" : title.strip();
        if (nextTitle.isBlank()) {
            nextTitle = "新会话";
        }
        replace(new ConversationRecord(
                current.id(),
                truncate(nextTitle, 48),
                current.createdAt(),
                System.currentTimeMillis(),
                current.messages(),
                current.searchTraces(),
                current.memoryMessagesJson()
        ));
    }

    public synchronized void delete(String id) {
        ConversationRecord removed = records.remove(id);
        if (removed == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException ignored) {
            // 列表已经更新，残留文件会在下一次加载时被忽略。
        }
        if (id.equals(activeId)) {
            activeId = records.values().stream()
                    .filter(record -> !isEmptyDraft(record))
                    .max(Comparator.comparingLong(ConversationRecord::updatedAt))
                    .map(ConversationRecord::id)
                    .orElse(null);
            if (activeId == null) {
                create();
                return;
            }
        }
        persistIndex();
    }

    public synchronized void appendMessage(String id, ChatMessage message) {
        ConversationRecord current = records.get(id);
        if (current == null || message == null) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(current.messages());
        messages.add(message);
        String title = current.title();
        if (title.equals("新会话") && message.role() == io.ctyx.modpedia.api.MessageRole.USER) {
            title = truncate(message.markdown().replaceAll("\\s+", " ").strip(), 48);
        }
        replace(new ConversationRecord(
                current.id(),
                title,
                current.createdAt(),
                System.currentTimeMillis(),
                messages,
                current.searchTraces(),
                current.memoryMessagesJson()
        ));
    }

    public synchronized void appendTrace(String id, SearchTrace trace) {
        ConversationRecord current = records.get(id);
        if (current == null || trace == null) {
            return;
        }
        List<SearchTrace> traces = new ArrayList<>(current.searchTraces());
        traces.add(trace);
        if (traces.size() > MAX_TRACE_COUNT) {
            traces = new ArrayList<>(traces.subList(traces.size() - MAX_TRACE_COUNT, traces.size()));
        }
        replace(new ConversationRecord(
                current.id(),
                current.title(),
                current.createdAt(),
                System.currentTimeMillis(),
                current.messages(),
                traces,
                current.memoryMessagesJson()
        ));
    }

    public synchronized void removeLastMessageIfRole(
            String id,
            io.ctyx.modpedia.api.MessageRole role
    ) {
        ConversationRecord current = records.get(id);
        if (current == null || current.messages().isEmpty()) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(current.messages());
        if (messages.get(messages.size() - 1).role() != role) {
            return;
        }
        messages.remove(messages.size() - 1);
        replace(new ConversationRecord(
                current.id(),
                current.title(),
                current.createdAt(),
                System.currentTimeMillis(),
                messages,
                current.searchTraces(),
                current.memoryMessagesJson()
        ));
    }

    public synchronized String memoryMessagesJson(String id) {
        ConversationRecord record = records.get(id);
        return record == null ? "" : record.memoryMessagesJson();
    }

    public synchronized void updateMemoryMessages(String id, String messagesJson) {
        ConversationRecord current = records.get(id);
        if (current == null) {
            return;
        }
        replace(new ConversationRecord(
                current.id(),
                current.title(),
                current.createdAt(),
                current.updatedAt(),
                current.messages(),
                current.searchTraces(),
                messagesJson
        ));
    }

    public Path root() {
        return root;
    }

    private void load() {
        try {
            Files.createDirectories(root);
            restrictDirectory(root);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "conversation-*.json")) {
                for (Path file : files) {
                    try {
                        ConversationRecord record = GSON.fromJson(
                                Files.readString(file, StandardCharsets.UTF_8),
                                ConversationRecord.class
                        );
                        if (record != null && !record.id().isBlank()) {
                            if (isEmptyDraft(record)) {
                                Files.deleteIfExists(file);
                                continue;
                            }
                            ConversationRecord migrated = migrateCitationMessages(record);
                            ConversationRecord bounded = fitForStorage(migrated);
                            records.put(bounded.id(), bounded);
                            if (!bounded.equals(record)) {
                                persist(bounded);
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 单个损坏会话隔离，其他会话继续可用。
                    }
                }
            }
            if (Files.isRegularFile(root.resolve(INDEX_FILE))) {
                IndexData index = GSON.fromJson(
                        Files.readString(root.resolve(INDEX_FILE), StandardCharsets.UTF_8),
                        IndexData.class
                );
                activeId = index == null ? null : index.activeId;
            }
        } catch (IOException | RuntimeException ignored) {
            // 首次启动或目录不可读时，在内存中创建新会话。
        }
        ensureActive();
    }

    private ConversationRecord migrateCitationMessages(ConversationRecord record) {
        List<ChatMessage> migratedMessages = new ArrayList<>();
        boolean changed = false;
        for (ChatMessage message : record.messages()) {
            if (message == null || message.role() != io.ctyx.modpedia.api.MessageRole.ASSISTANT
                    || message.sources() == null || !message.sources().isEmpty()
                    || SourceCitationParser.parse(message.markdown()).isEmpty()) {
                migratedMessages.add(message);
                continue;
            }
            List<io.ctyx.modpedia.api.SourceReference> sources = SourceCitationParser.selectSources(
                    record.searchTraces(),
                    message.markdown(),
                    5
            );
            // 旧会话也保留引用在正文中的原始位置。客户端会按行隐藏协议文本并绘制
            // 可点击标注；如果旧记录只有来源协议而没有可见正文，才使用兼容提示。
            String markdown = message.markdown();
            if (SourceCitationParser.removeCitationMarkup(markdown).isBlank() && !sources.isEmpty()) {
                markdown = "已根据本地手册整理，详细依据已标注在相关正文后。";
            }
            migratedMessages.add(new ChatMessage(
                    message.role(),
                    markdown,
                    sources,
                    message.followUpQuestions(),
                    message.taskSummary()
            ));
            changed = true;
        }
        return changed
                ? new ConversationRecord(
                        record.id(),
                        record.title(),
                        record.createdAt(),
                        record.updatedAt(),
                        migratedMessages,
                        record.searchTraces(),
                        record.memoryMessagesJson()
                )
                : record;
    }

    private void ensureActive() {
        if (activeId != null && records.containsKey(activeId)) {
            return;
        }
        activeId = records.values().stream()
                .max(Comparator.comparingLong(ConversationRecord::updatedAt))
                .map(ConversationRecord::id)
                .orElse(null);
        if (activeId == null) {
            create();
        }
    }

    private void replace(ConversationRecord record) {
        ConversationRecord bounded = fitForStorage(record);
        records.put(bounded.id(), bounded);
        if (isEmptyDraft(bounded)) {
            deleteRecordFile(bounded.id());
        } else {
            persist(bounded);
        }
        persistIndex();
    }

    private void persist(ConversationRecord record) {
        if (isEmptyDraft(record)) {
            deleteRecordFile(record.id());
            return;
        }
        try {
            Files.createDirectories(root);
            restrictDirectory(root);
            ConversationRecord bounded = fitForStorage(record);
            String json = GSON.toJson(bounded);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONVERSATION_FILE_BYTES) {
                return;
            }
            writeAtomically(fileFor(bounded.id()), json);
        } catch (IOException ignored) {
            // 会话写入失败时继续使用内存中的当前会话。
        }
    }

    private void persistIndex() {
        try {
            Files.createDirectories(root);
            restrictDirectory(root);
            writeAtomically(root.resolve(INDEX_FILE), GSON.toJson(new IndexData(activeId)));
        } catch (IOException ignored) {
            // 会话文件仍然保留，下一次启动可按更新时间恢复。
        }
    }

    private boolean isEmptyDraft(ConversationRecord record) {
        return record != null
                && record.messages().isEmpty()
                && (record.memoryMessagesJson() == null || record.memoryMessagesJson().isBlank());
    }

    private void deleteRecordFile(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException ignored) {
            // 下次加载时仍会过滤空草稿。
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(root, "conversation-", ".tmp");
        restrictFile(temporary);
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(target);
    }

    private ConversationRecord fitForStorage(ConversationRecord record) {
        if (record == null) {
            return new ConversationRecord("", "新会话", 0L, 0L, List.of(), List.of(), "");
        }
        ConversationRecord bounded = boundRecord(record);
        while (serializedBytes(bounded) > MAX_CONVERSATION_FILE_BYTES
                && bounded.messages().size() > 1) {
            bounded = new ConversationRecord(
                    bounded.id(),
                    bounded.title(),
                    bounded.createdAt(),
                    bounded.updatedAt(),
                    bounded.messages().subList(1, bounded.messages().size()),
                    bounded.searchTraces(),
                    bounded.memoryMessagesJson()
            );
        }
        if (serializedBytes(bounded) <= MAX_CONVERSATION_FILE_BYTES) {
            return bounded;
        }
        // 理论上 boundRecord 已经足够小；如果外部模型对象仍产生异常大的结构，
        // 保留最新一条消息和会话元数据，不让磁盘写入失控。
        ChatMessage latest = bounded.messages().isEmpty()
                ? null
                : bounded.messages().getLast();
        return new ConversationRecord(
                bounded.id(),
                bounded.title(),
                bounded.createdAt(),
                bounded.updatedAt(),
                latest == null ? List.of() : List.of(latest),
                List.of(),
                ""
        );
    }

    private long serializedBytes(ConversationRecord record) {
        return GSON.toJson(record).getBytes(StandardCharsets.UTF_8).length;
    }

    private ConversationRecord boundRecord(ConversationRecord record) {
        int messageStart = Math.max(0, record.messages().size() - MAX_MESSAGE_COUNT);
        List<ChatMessage> messages = record.messages().subList(messageStart, record.messages().size())
                .stream()
                .map(this::boundMessage)
                .toList();
        int traceStart = Math.max(0, record.searchTraces().size() - MAX_TRACE_COUNT);
        List<SearchTrace> traces = record.searchTraces().subList(traceStart, record.searchTraces().size())
                .stream()
                .map(this::boundTrace)
                .toList();
        String memory = record.memoryMessagesJson();
        if (memory.codePointCount(0, memory.length()) > MAX_MEMORY_MESSAGES_CHARS) {
            memory = "";
        }
        return new ConversationRecord(
                record.id(),
                truncate(record.title(), 48),
                record.createdAt(),
                record.updatedAt(),
                messages,
                traces,
                memory
        );
    }

    private ChatMessage boundMessage(ChatMessage message) {
        if (message == null) {
            return new ChatMessage(MessageRole.ASSISTANT, "", List.of());
        }
        return new ChatMessage(
                message.role(),
                truncate(message.markdown(), MAX_MESSAGE_MARKDOWN_CHARS),
                message.sources().stream().limit(MAX_TRACE_SOURCES).map(this::boundSource).toList(),
                message.followUpQuestions(),
                message.taskSummary()
        );
    }

    private SearchTrace boundTrace(SearchTrace trace) {
        if (trace == null) {
            return new SearchTrace("", "auto", "identify", 1, "", false, List.of(), 0L);
        }
        return new SearchTrace(
                truncate(trace.query(), 2_000),
                truncate(trace.language(), 32),
                truncate(trace.focus(), 64),
                trace.round(),
                truncate(trace.status(), 64),
                trace.hasMore(),
                trace.sources().stream().limit(MAX_TRACE_SOURCES).map(this::boundSource).toList(),
                trace.createdAt(),
                truncate(trace.tool(), 64)
        );
    }

    private SourceReference boundSource(SourceReference source) {
        if (source == null) {
            return new SourceReference("", "", "", "");
        }
        return new SourceReference(
                truncate(source.documentId(), 512),
                truncate(source.title(), 512),
                truncate(source.sourceMod(), 128),
                truncate(source.sourcePath(), 2_048),
                truncate(source.annotation(), 120)
        );
    }

    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows 和不支持 POSIX 权限的文件系统使用系统默认用户权限。
        }
    }

    private static void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows 和不支持 POSIX 权限的文件系统使用系统默认用户权限。
        }
    }

    private Path fileFor(String id) {
        return root.resolve("conversation-" + id + ".json");
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        int length = value.codePointCount(0, value.length());
        if (length <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end) + "…";
    }

    private static final class IndexData {
        private String activeId;

        private IndexData(String activeId) {
            this.activeId = activeId;
        }
    }
}
