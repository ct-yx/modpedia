package io.ctyx.modpedia.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.UUID;

/**
 * ModPedia Worker 的最小 JSONL 协议工具。
 *
 * <p>协议故意只传 JSON 对象，不把 LangChain4j 或 Minecraft 类型暴露到 IPC
 * 边界。游戏进程和 Worker 进程都只依赖这里定义的字段。</p>
 */
public final class WorkerProtocol {
    public static final int VERSION = 1;

    public static final String HELLO = "hello";
    public static final String HELLO_ACK = "hello_ack";
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final String CHAT_START = "chat.start";
    public static final String CHAT_CANCEL = "chat.cancel";
    public static final String RUNTIME_CONTEXT_REQUEST = "runtime_context_request";
    public static final String RUNTIME_CONTEXT_RESPONSE = "runtime_context_response";
    public static final String RECIPE_QUERY_REQUEST = "recipe_query_request";
    public static final String RECIPE_QUERY_RESPONSE = "recipe_query_response";
    public static final String KNOWLEDGE_REBUILD = "knowledge.rebuild";
    /** 请求被后续知识库构建合并，不能被当作真正的构建完成事件。 */
    public static final String KNOWLEDGE_REBUILD_COALESCED = "knowledge.rebuild.coalesced";
    public static final String KNOWLEDGE_ITEMS_SYNC = "knowledge.items.sync";
    public static final String TASK_STATIC_SYNC = "task.static.sync";
    public static final String TASK_WIKI_SYNC = "task.wiki.sync";
    public static final String SETTINGS_LOAD = "settings.load";
    public static final String SETTINGS_SAVE = "settings.save";
    public static final String AI_MODELS_FETCH = "ai.models.fetch";
    public static final String AI_CONNECTION_TEST = "ai.connection.test";
    public static final String AI_COMPATIBILITY_TEST = "ai.compatibility.test";
    public static final String CONVERSATION_LIST = "conversation.list";
    public static final String CONVERSATION_NEW = "conversation.new";
    public static final String CONVERSATION_SELECT = "conversation.select";
    public static final String CONVERSATION_RENAME = "conversation.rename";
    public static final String CONVERSATION_DELETE = "conversation.delete";
    public static final String CONVERSATION_CLEAR = "conversation.clear";
    public static final String CONVERSATION_STATE = "conversation.state";
    public static final String STATUS = "status";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String TEXT_DELTA = "text_delta";
    public static final String COMPLETED = "completed";
    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";
    public static final String SHUTDOWN = "shutdown";

    private WorkerProtocol() {
    }

    public static JsonObject message(String type, String requestId) {
        JsonObject message = new JsonObject();
        message.addProperty("protocol_version", VERSION);
        message.addProperty("type", type == null ? "" : type);
        message.addProperty("request_id", requestId == null ? UUID.randomUUID().toString() : requestId);
        // 所有 JSONL 消息都带有这个字段。非对话操作使用空字符串，聊天和
        // 对话管理请求由调用方覆盖为实际会话 ID；这样协议边界始终可追踪。
        message.addProperty("conversation_id", "");
        return message;
    }

    public static boolean isCurrentVersion(JsonObject message) {
        return integer(message, "protocol_version", -1) == VERSION;
    }

    public static JsonObject parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("空的 Worker 协议消息");
        }
        JsonElement parsed = JsonParser.parseString(line);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Worker 协议消息必须是 JSON 对象");
        }
        return parsed.getAsJsonObject();
    }

    public static void write(BufferedWriter writer, JsonObject message) throws IOException {
        writer.write(message.toString());
        writer.newLine();
        writer.flush();
    }

    public static JsonObject read(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line == null ? null : parse(line);
    }

    public static int integer(JsonObject object, String name, int fallback) {
        JsonElement value = object == null ? null : object.get(name);
        try {
            return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public static long longValue(JsonObject object, String name, long fallback) {
        JsonElement value = object == null ? null : object.get(name);
        try {
            return value == null || !value.isJsonPrimitive() ? fallback : value.getAsLong();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement value = object == null ? null : object.get(name);
        try {
            return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber() || primitive.isBoolean()
                ? primitive.getAsString()
                : "";
    }
}
