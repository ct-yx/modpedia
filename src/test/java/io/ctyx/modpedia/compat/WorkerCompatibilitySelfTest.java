package io.ctyx.modpedia.compat;

import com.google.gson.JsonObject;
import io.ctyx.modpedia.protocol.WorkerProtocol;

/** Worker 基线、握手能力和不含敏感字段的诊断回归。 */
public final class WorkerCompatibilitySelfTest {
    private WorkerCompatibilitySelfTest() {
    }

    public static void main(String[] args) {
        JsonObject hello = WorkerProtocol.message(WorkerProtocol.HELLO, "compat-hello");
        WorkerCompatibility.addClientHello(hello);
        WorkerCompatibility.addClientOptionalCapability(
                hello, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY);
        check(WorkerCompatibility.isCompatibleClient(hello), "当前客户端声明应通过兼容检查");
        check(hello.has("client_capabilities"), "客户端握手应携带能力集合");
        check(WorkerCompatibility.supportsOptionalCapability(
                        hello, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY),
                "当前客户端应声明可选的运行时物品上下文能力");
        check(!WorkerCompatibility.describe(hello).contains("token"), "诊断信息不得包含 Token 字段");

        JsonObject ack = WorkerProtocol.message(WorkerProtocol.HELLO_ACK, "compat-hello");
        WorkerCompatibility.addWorkerAck(ack);
        check(WorkerCompatibility.isCompatibleAck(ack), "当前 Worker 响应应通过兼容检查");
        check(ack.has("worker_capabilities"), "Worker 握手应携带能力集合");
        check(WorkerCompatibility.supportsOptionalCapability(
                        ack, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY),
                "当前 Worker 应声明可选的运行时物品上下文能力");

        JsonObject oldClient = hello.deepCopy();
        oldClient.remove("client_optional_capabilities");
        check(WorkerCompatibility.isCompatibleClient(oldClient),
                "缺少可选能力的旧客户端仍应完成握手");
        check(!WorkerCompatibility.supportsOptionalCapability(
                        oldClient, WorkerProtocol.RUNTIME_ITEM_CONTEXT_CAPABILITY),
                "旧客户端缺少运行时物品上下文能力时应返回静态目录");

        JsonObject wrongBaseline = hello.deepCopy();
        wrongBaseline.addProperty("worker_baseline", "worker-baseline-999");
        check(!WorkerCompatibility.isCompatibleClient(wrongBaseline), "不同基线必须拒绝");

        JsonObject wrongApi = ack.deepCopy();
        wrongApi.addProperty("worker_api_level", WorkerCompatibility.API_LEVEL + 1);
        check(!WorkerCompatibility.isCompatibleAck(wrongApi), "不同 Worker API 必须拒绝");

        JsonObject missingCapabilities = hello.deepCopy();
        missingCapabilities.remove("client_capabilities");
        check(!WorkerCompatibility.isCompatibleClient(missingCapabilities), "缺少能力集合必须拒绝");

        System.out.println("Worker compatibility self-test passed: "
                + WorkerCompatibility.WORKER_LIBRARY_BASELINE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
