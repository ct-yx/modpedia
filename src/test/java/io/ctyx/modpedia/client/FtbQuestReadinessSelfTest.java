package io.ctyx.modpedia.client;

import java.lang.reflect.InvocationTargetException;

/** FTBQ 任务文件尚未同步时的反射异常识别回归。 */
public final class FtbQuestReadinessSelfTest {
    private FtbQuestReadinessSelfTest() {
    }

    public static void main(String[] args) {
        check(FtbQuestsClientAdapter.isQuestFileNotReady(
                        new InvocationTargetException(new NullPointerException("quest file"))),
                "getQuestFile 的包装 NPE 应识别为尚未就绪");
        check(FtbQuestsClientAdapter.isQuestFileNotReady(
                        new RuntimeException("wrapper", new NullPointerException())),
                "嵌套包装 NPE 应识别为尚未就绪");
        check(FtbQuestsClientAdapter.isQuestFileNotReady(
                        new IllegalStateException("quest file is not loaded")),
                "任务文件未加载状态应识别为尚未就绪");
        check(!FtbQuestsClientAdapter.isQuestFileNotReady(
                        new IllegalStateException("network connection failed")),
                "无关异常不能被误判为任务文件尚未就绪");
        System.out.println("ModPedia FTBQ readiness self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
