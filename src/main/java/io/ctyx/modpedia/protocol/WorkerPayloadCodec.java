package io.ctyx.modpedia.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.client.ChatMessage;
import io.ctyx.modpedia.client.ConversationSummary;
import io.ctyx.modpedia.client.MessageRole;
import io.ctyx.modpedia.client.SourceReference;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AssistantMode;
import io.ctyx.modpedia.ai.SearchIntensity;
import io.ctyx.modpedia.search.ItemCatalogEntry;
import io.ctyx.modpedia.task.TaskQuery;
import io.ctyx.modpedia.task.TaskQueryMode;
import io.ctyx.modpedia.task.TaskRuntimeFileDescriptor;
import io.ctyx.modpedia.task.TaskRuntimeSnapshot;
import io.ctyx.modpedia.task.TaskSearchSummary;
import io.ctyx.modpedia.task.TaskTimelineEntry;
import io.ctyx.modpedia.task.TaskTimelineEventType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Worker JSONL 边界上的稳定数据转换，不在协议中传输业务对象实例。 */
public final class WorkerPayloadCodec {
    private WorkerPayloadCodec() {
    }

    public static JsonObject chatMessage(ChatMessage message) {
        JsonObject value = new JsonObject();
        value.addProperty("role", message == null || message.role() == null
                ? MessageRole.ASSISTANT.name().toLowerCase()
                : message.role().name().toLowerCase());
        value.addProperty("markdown", message == null ? "" : message.markdown());
        JsonArray sources = new JsonArray();
        if (message != null) {
            message.sources().forEach(source -> sources.add(source(source)));
        }
        value.add("sources", sources);
        JsonArray followUps = new JsonArray();
        if (message != null) {
            message.followUpQuestions().forEach(followUps::add);
        }
        value.add("follow_up_questions", followUps);
        if (message != null && message.taskSummary() != null) {
            value.add("task_summary", taskSummary(message.taskSummary()));
        }
        return value;
    }

    public static ChatMessage chatMessage(JsonObject value) {
        if (value == null) {
            return new ChatMessage(MessageRole.ASSISTANT, "", List.of());
        }
        MessageRole role;
        try {
            role = MessageRole.valueOf(WorkerProtocol.string(value, "role").toUpperCase());
        } catch (RuntimeException exception) {
            role = MessageRole.ASSISTANT;
        }
        List<SourceReference> sources = new ArrayList<>();
        JsonArray sourceArray = array(value, "sources");
        for (JsonElement element : sourceArray) {
            if (element.isJsonObject()) {
                sources.add(source(element.getAsJsonObject()));
            }
        }
        List<String> followUps = strings(array(value, "follow_up_questions"));
        TaskSearchSummary taskSummary = value.has("task_summary")
                && value.get("task_summary").isJsonObject()
                ? taskSummary(value.getAsJsonObject("task_summary"))
                : null;
        return new ChatMessage(
                role,
                WorkerProtocol.string(value, "markdown"),
                sources,
                followUps,
                taskSummary
        );
    }

    public static JsonObject taskSummary(TaskSearchSummary summary) {
        JsonObject value = new JsonObject();
        if (summary == null) {
            return value;
        }
        value.addProperty("task_definition_count", summary.taskDefinitionCount());
        value.addProperty("runtime_state_count", summary.runtimeStateCount());
        value.addProperty("progress_item_count", summary.progressItemCount());
        value.addProperty("timeline_entry_count", summary.timelineEntryCount());
        value.addProperty("runtime_progress_available", summary.runtimeProgressAvailable());
        return value;
    }

    public static TaskSearchSummary taskSummary(JsonObject value) {
        if (value == null) {
            return null;
        }
        return new TaskSearchSummary(
                WorkerProtocol.integer(value, "task_definition_count", 0),
                WorkerProtocol.integer(value, "runtime_state_count", 0),
                WorkerProtocol.integer(value, "progress_item_count", 0),
                WorkerProtocol.bool(value, "runtime_progress_available", false),
                WorkerProtocol.integer(value, "timeline_entry_count", 0)
        );
    }

    public static JsonObject source(SourceReference source) {
        JsonObject value = new JsonObject();
        if (source == null) {
            return value;
        }
        value.addProperty("document_id", source.documentId());
        value.addProperty("title", source.title());
        value.addProperty("source_mod", source.sourceMod());
        value.addProperty("source_path", source.sourcePath());
        value.addProperty("annotation", source.annotation());
        return value;
    }

    public static SourceReference source(JsonObject value) {
        return new SourceReference(
                WorkerProtocol.string(value, "document_id"),
                WorkerProtocol.string(value, "title"),
                WorkerProtocol.string(value, "source_mod"),
                WorkerProtocol.string(value, "source_path"),
                WorkerProtocol.string(value, "annotation")
        );
    }

    public static JsonObject summary(ConversationSummary summary) {
        JsonObject value = new JsonObject();
        if (summary == null) {
            return value;
        }
        value.addProperty("id", summary.id());
        value.addProperty("title", summary.title());
        value.addProperty("updated_at", summary.updatedAt());
        value.addProperty("message_count", summary.messageCount());
        return value;
    }

    public static ConversationSummary summary(JsonObject value) {
        return new ConversationSummary(
                WorkerProtocol.string(value, "id"),
                WorkerProtocol.string(value, "title"),
                WorkerProtocol.longValue(value, "updated_at", 0L),
                WorkerProtocol.integer(value, "message_count", 0)
        );
    }

    public static JsonObject item(ItemCatalogEntry entry) {
        JsonObject value = new JsonObject();
        if (entry == null) {
            return value;
        }
        value.addProperty("item_id", entry.itemId());
        value.addProperty("language", entry.language());
        value.addProperty("display_name", entry.displayName());
        value.addProperty("description_markdown", entry.descriptionMarkdown());
        value.addProperty("source_mod", entry.sourceMod());
        value.addProperty("fingerprint", entry.fingerprint());
        return value;
    }

    public static ItemCatalogEntry item(JsonObject value) {
        return new ItemCatalogEntry(
                WorkerProtocol.string(value, "item_id"),
                WorkerProtocol.string(value, "language"),
                WorkerProtocol.string(value, "display_name"),
                WorkerProtocol.string(value, "description_markdown"),
                WorkerProtocol.string(value, "source_mod"),
                WorkerProtocol.string(value, "fingerprint")
        );
    }

    /** AI 设置通过本地 IPC 传输；API Key 只存在于请求/设置数据，不写入日志。 */
    public static JsonObject aiSettings(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        JsonObject value = new JsonObject();
        value.addProperty("mode", actual.mode().name());
        value.addProperty("endpoint", actual.endpoint());
        value.addProperty("model", actual.model());
        value.addProperty("api_key", actual.apiKey());
        value.addProperty("streaming", actual.streaming());
        value.addProperty("intensity", actual.intensity().name());
        value.addProperty("max_rounds", actual.maxRounds());
        value.addProperty("max_results", actual.maxResults());
        value.addProperty("max_context_chars", actual.maxContextChars());
        value.addProperty("timeout_seconds", actual.timeoutSeconds());
        return value;
    }

    public static AiSettings aiSettings(JsonObject value) {
        AssistantMode mode;
        SearchIntensity intensity;
        try {
            mode = AssistantMode.valueOf(WorkerProtocol.string(value, "mode").toUpperCase());
        } catch (RuntimeException exception) {
            mode = AssistantMode.AI;
        }
        try {
            intensity = SearchIntensity.valueOf(WorkerProtocol.string(value, "intensity").toUpperCase());
        } catch (RuntimeException exception) {
            intensity = SearchIntensity.STANDARD;
        }
        return new AiSettings(
                mode,
                WorkerProtocol.string(value, "endpoint"),
                WorkerProtocol.string(value, "model"),
                WorkerProtocol.string(value, "api_key"),
                WorkerProtocol.bool(value, "streaming", true),
                intensity,
                WorkerProtocol.integer(value, "max_rounds", intensity.rounds()),
                WorkerProtocol.integer(value, "max_results", intensity.results()),
                WorkerProtocol.integer(value, "max_context_chars", intensity.contextChars()),
                WorkerProtocol.integer(value, "timeout_seconds", 90)
        );
    }

    public static JsonObject taskQuery(TaskQuery query) {
        JsonObject value = new JsonObject();
        if (query == null) {
            return value;
        }
        value.addProperty("mode", query.mode().name());
        value.addProperty("text", query.text());
        value.addProperty("quest_id", query.questId());
        value.addProperty("limit", query.limit());
        JsonArray collections = new JsonArray();
        query.collectionIds().forEach(collections::add);
        value.add("collection_ids", collections);
        return value;
    }

    public static TaskQuery taskQuery(JsonObject value) {
        TaskQueryMode mode;
        try {
            mode = TaskQueryMode.valueOf(WorkerProtocol.string(value, "mode").toUpperCase());
        } catch (RuntimeException exception) {
            mode = TaskQueryMode.SEARCH;
        }
        return new TaskQuery(
                mode,
                WorkerProtocol.string(value, "text"),
                WorkerProtocol.string(value, "quest_id"),
                WorkerProtocol.integer(value, "limit", TaskQuery.DEFAULT_LIMIT),
                strings(array(value, "collection_ids"))
        );
    }

    public static JsonObject runtimeSnapshot(TaskRuntimeSnapshot snapshot) {
        JsonObject value = new JsonObject();
        if (snapshot == null) {
            value.addProperty("available", false);
            return value;
        }
        value.addProperty("available", true);
        value.addProperty("source_key", snapshot.sourceKey());
        value.addProperty("scope_key", snapshot.scopeKey());
        value.addProperty("version", snapshot.version());
        value.add("started_quest_ids", array(snapshot.startedQuestIds()));
        value.add("completed_quest_ids", array(snapshot.completedQuestIds()));
        JsonObject progress = new JsonObject();
        snapshot.taskProgress().forEach(progress::addProperty);
        value.add("task_progress", progress);
        value.add("timeline", timeline(snapshot.timeline()));
        return value;
    }

    public static TaskRuntimeSnapshot runtimeSnapshot(JsonObject value) {
        if (value == null || !WorkerProtocol.bool(value, "available", false)) {
            return null;
        }
        JsonObject progressObject = value.get("task_progress") != null
                && value.get("task_progress").isJsonObject()
                ? value.getAsJsonObject("task_progress")
                : new JsonObject();
        Map<String, Double> progress = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : progressObject.entrySet()) {
            try {
                progress.put(entry.getKey(), entry.getValue().getAsDouble());
            } catch (RuntimeException ignored) {
                // 忽略单个损坏的进度值，其他任务仍可查询。
            }
        }
        List<TaskTimelineEntry> timeline = timeline(array(value, "timeline"));
        return new TaskRuntimeSnapshot(
                WorkerProtocol.string(value, "source_key"),
                WorkerProtocol.string(value, "scope_key"),
                WorkerProtocol.string(value, "version"),
                strings(array(value, "started_quest_ids")),
                strings(array(value, "completed_quest_ids")),
                progress,
                timeline
        );
    }

    public static JsonArray timeline(Collection<TaskTimelineEntry> entries) {
        JsonArray result = new JsonArray();
        if (entries == null) {
            return result;
        }
        for (TaskTimelineEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            JsonObject value = new JsonObject();
            value.addProperty("entry_id", entry.questId());
            value.addProperty("quest_id", entry.questId());
            value.addProperty("event_type", entry.eventType().name());
            value.addProperty("timestamp_epoch_ms", entry.timestampEpochMillis());
            value.addProperty("timestamp_known", entry.hasKnownTimestamp());
            if (entry.hasKnownTimestamp()) {
                value.addProperty("timestamp_iso", java.time.Instant.ofEpochMilli(
                        entry.timestampEpochMillis()).toString());
            }
            if (entry.previousProgress() != null) {
                value.addProperty("previous_progress", entry.previousProgress());
            }
            if (entry.currentProgress() != null) {
                value.addProperty("current_progress", entry.currentProgress());
            }
            result.add(value);
        }
        return result;
    }

    public static List<TaskTimelineEntry> timeline(JsonArray values) {
        List<TaskTimelineEntry> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (JsonElement element : values) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject value = element.getAsJsonObject();
            TaskTimelineEventType eventType;
            try {
                eventType = TaskTimelineEventType.valueOf(
                        WorkerProtocol.string(value, "event_type").toUpperCase()
                );
            } catch (RuntimeException exception) {
                eventType = TaskTimelineEventType.DETECTED;
            }
            Double previous = optionalDouble(value, "previous_progress");
            Double current = optionalDouble(value, "current_progress");
            String id = WorkerProtocol.string(value, "quest_id");
            if (id.isBlank()) {
                id = WorkerProtocol.string(value, "entry_id");
            }
            if (id.isBlank()) {
                id = WorkerProtocol.string(value, "task_id");
            }
            result.add(new TaskTimelineEntry(
                    id,
                    eventType,
                    WorkerProtocol.longValue(value, "timestamp_epoch_ms", 0L),
                    previous,
                    current
            ));
        }
        return List.copyOf(result);
    }

    private static Double optionalDouble(JsonObject value, String name) {
        JsonElement element = value == null ? null : value.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static JsonObject runtimeFileDescriptor(TaskRuntimeFileDescriptor descriptor) {
        JsonObject value = new JsonObject();
        if (descriptor == null || !descriptor.usable()) {
            value.addProperty("available", false);
            return value;
        }
        value.addProperty("available", true);
        value.addProperty("world_root", descriptor.worldRoot());
        value.addProperty("player_uuid", descriptor.playerUuid());
        value.addProperty("source_key", descriptor.sourceKey());
        value.addProperty("scope_key", descriptor.scopeKey());
        value.addProperty("version", descriptor.version());
        return value;
    }

    public static TaskRuntimeFileDescriptor runtimeFileDescriptor(JsonObject value) {
        if (value == null || !WorkerProtocol.bool(value, "available", false)) {
            return null;
        }
        TaskRuntimeFileDescriptor descriptor = new TaskRuntimeFileDescriptor(
                WorkerProtocol.string(value, "world_root"),
                WorkerProtocol.string(value, "player_uuid"),
                WorkerProtocol.string(value, "source_key"),
                WorkerProtocol.string(value, "scope_key"),
                WorkerProtocol.string(value, "version")
        );
        return descriptor.usable() ? descriptor : null;
    }

    public static JsonArray array(Collection<?> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            values.forEach(value -> {
                if (value != null) {
                    array.add(String.valueOf(value));
                }
            });
        }
        return array;
    }

    public static JsonArray array(JsonObject value, String name) {
        JsonElement element = value == null ? null : value.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    public static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (JsonElement value : values) {
            if (value != null && value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }
}
