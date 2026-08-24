package io.ctyx.modpedia.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 验证用户级共享路径与当前实例级数据路径的边界。 */
public final class UserModPediaPathsSelfTest {
    private UserModPediaPathsSelfTest() {
    }

    public static void main(String[] args) {
        Path config = Paths.get("build", "path-self-test", "config").toAbsolutePath().normalize();
        UserModPediaPaths paths = UserModPediaPaths.resolve(config);
        Path expectedPayload = config.resolve("modpedia").resolve("runtime")
                .resolve("worker").resolve("payloads")
                .toAbsolutePath().normalize();
        check(expectedPayload.equals(paths.workerPayloadRoot()),
                "客户端载荷目录必须与 Worker 使用 config/modpedia/runtime/worker/payloads");
        check(!paths.workerPayloadRoot().toString().endsWith("/payload"),
                "旧的单数 payload 目录不得再作为生产路径");

        Path expectedConversations = config.resolve("modpedia").resolve("runtime")
                .resolve("conversations")
                .toAbsolutePath().normalize();
        check(expectedConversations.equals(paths.instanceConversations()),
                "会话目录必须位于当前游戏实例的 config/modpedia/runtime/conversations");
        check(!paths.instanceConversations().equals(paths.userRoot().resolve("conversations")),
                "会话目录不得继续使用用户级 .modpedia/conversations");

        Path otherConfig = Paths.get("build", "path-self-test-other", "config")
                .toAbsolutePath().normalize();
        UserModPediaPaths other = UserModPediaPaths.resolve(otherConfig);
        check(!paths.instanceConversations().equals(other.instanceConversations()),
                "不同游戏实例必须使用独立会话目录");
        check(paths.aiSettings().equals(other.aiSettings()),
                "AI 配置继续使用用户级共享路径");
        check(paths.workerLibraryRoot().equals(other.workerLibraryRoot()),
                "Worker lib 继续使用用户级共享路径");
        System.out.println("UserModPediaPathsSelfTest: OK");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
