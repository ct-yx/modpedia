package io.ctyx.modpedia.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 验证客户端与 Worker 共享同一个批量物品载荷目录。 */
public final class UserModPediaPathsSelfTest {
    private UserModPediaPathsSelfTest() {
    }

    public static void main(String[] args) {
        Path config = Paths.get("build", "path-self-test", "config").toAbsolutePath().normalize();
        UserModPediaPaths paths = UserModPediaPaths.resolve(config);
        Path expected = config.resolve("modpedia").resolve("runtime")
                .resolve("worker").resolve("payloads")
                .toAbsolutePath().normalize();
        check(expected.equals(paths.workerPayloadRoot()),
                "客户端载荷目录必须与 Worker 使用 config/modpedia/runtime/worker/payloads");
        check(!paths.workerPayloadRoot().toString().endsWith("/payload"),
                "旧的单数 payload 目录不得再作为生产路径");
        System.out.println("UserModPediaPathsSelfTest: OK");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
