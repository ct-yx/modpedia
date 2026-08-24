package io.ctyx.modpedia.client;

/** 发送、流式草稿、失败和重试的纯 Java 回归。 */
public final class LegacyConversationStateSelfTest {
    private LegacyConversationStateSelfTest() {
    }

    public static void main(String[] args) {
        LegacyConversationState state = new LegacyConversationState();
        state.submit("第一个问题", false);
        check(state.messages().size() == 1, "发送应先加入用户消息");
        state.appendDraft("回答");
        state.fail(true);
        check(state.messages().size() == 2, "失败时应保留部分回答");
        check(state.retryAvailable(), "失败后应允许重试");
        state.submit("第一个问题", true);
        check(state.messages().size() == 2, "重试不应重复加入用户消息");
        state.complete("完整回答", null, null);
        check(state.messages().size() == 3, "完成后应固化完整回答");
        check(!state.retryAvailable(), "完成后不应保留重试状态");
        state.clear();
        check(state.messages().isEmpty() && state.lastPrompt().isEmpty(), "清空应重置状态");
        System.out.println("LegacyConversationStateSelfTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
