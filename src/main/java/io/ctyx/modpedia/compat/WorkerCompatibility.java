package io.ctyx.modpedia.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerProtocol;

import java.util.List;

/**
 * Worker 与客户端适配层之间的兼容性边界。
 *
 * <p>这里不引用 Minecraft、NeoForge 或任何客户端类。Minecraft 版本变化时，
 * 只要 Worker API、协议和运行库不变，就可以继续使用同一套 Worker 基线。</p>
 */
public final class WorkerCompatibility {
    /** Worker API 发生不兼容变化时递增。 */
    public static final int API_LEVEL = 1;
    /** 运行库发生变化时递增；必须与用户级共享 lib 目录一致。 */
    public static final String WORKER_LIBRARY_BASELINE = "worker-baseline-1";
    /** 当前客户端适配层标识，仅用于诊断，不决定 Worker 核心是否可复用。 */
    public static final String CLIENT_ADAPTER = "neoforge-1.21.1";

    /**
     * 当前 Worker 能力的稳定顺序。新增能力可以追加；删除或改变既有能力时应递增
     * API_LEVEL，并按规则更新基线说明。
     */
    public static final List<String> CAPABILITIES = List.of(
            "chat",
            "knowledge_rebuild",
            "knowledge_items_sync",
            "runtime_context",
            "recipe_query",
            "conversations",
            "ai_settings"
    );

    private WorkerCompatibility() {
    }

    /** 在客户端 hello 中写入 Worker 兼容性要求和当前适配层信息。 */
    public static void addClientHello(JsonObject hello) {
        hello.addProperty("worker_api_level", API_LEVEL);
        hello.addProperty("worker_baseline", WORKER_LIBRARY_BASELINE);
        hello.addProperty("client_adapter", CLIENT_ADAPTER);
        hello.addProperty("client_java", javaVersion());
        hello.add("client_capabilities", array(CAPABILITIES));
    }

    /** 在 Worker hello_ack 中写入实际运行的基线、Java 和能力集合。 */
    public static void addWorkerAck(JsonObject ack) {
        ack.addProperty("worker_api_level", API_LEVEL);
        ack.addProperty("worker_baseline", WORKER_LIBRARY_BASELINE);
        ack.addProperty("worker_java", javaVersion());
        ack.add("worker_capabilities", array(CAPABILITIES));
    }

    /** 检查客户端声明的 Worker API 和运行库基线是否与当前 Worker 一致。 */
    public static boolean isCompatibleClient(JsonObject hello) {
        return WorkerProtocol.integer(hello, "worker_api_level", -1) == API_LEVEL
                && WORKER_LIBRARY_BASELINE.equals(WorkerProtocol.string(hello, "worker_baseline"))
                && !WorkerProtocol.string(hello, "client_adapter").isBlank()
                && hasCapabilities(hello, "client_capabilities");
    }

    /** 检查 Worker 回报的 API、基线和最小能力集合是否满足当前客户端。 */
    public static boolean isCompatibleAck(JsonObject ack) {
        return WorkerProtocol.integer(ack, "worker_api_level", -1) == API_LEVEL
                && WORKER_LIBRARY_BASELINE.equals(WorkerProtocol.string(ack, "worker_baseline"))
                && !WorkerProtocol.string(ack, "worker_java").isBlank()
                && hasCapabilities(ack, "worker_capabilities");
    }

    /** 只返回不含 Token、密钥或请求正文的握手诊断信息。 */
    public static String describe(JsonObject message) {
        return "api=" + WorkerProtocol.integer(message, "worker_api_level", -1)
                + ", baseline=" + WorkerProtocol.string(message, "worker_baseline")
                + ", adapter=" + WorkerProtocol.string(message, "client_adapter")
                + ", worker_java=" + WorkerProtocol.string(message, "worker_java");
    }

    private static boolean hasCapabilities(JsonObject message, String field) {
        JsonElement value = message == null ? null : message.get(field);
        if (value == null || !value.isJsonArray()) {
            return false;
        }
        for (String required : CAPABILITIES) {
            boolean found = false;
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonPrimitive() && required.equals(item.getAsString())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static JsonArray array(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static String javaVersion() {
        return System.getProperty("java.specification.version", "");
    }
}
