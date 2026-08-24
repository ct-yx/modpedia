package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Worker markdown/content 字段和完成事件回退规则回归。 */
public final class LegacyConversationPayloadSelfTest {
    private LegacyConversationPayloadSelfTest() {
    }

    public static void main(String[] args) {
        JsonObject markdown = new JsonObject();
        markdown.addProperty("role", "assistant");
        markdown.addProperty("markdown", "# 完整回答");
        markdown.addProperty("content", "旧字段不应覆盖新字段");
        check("# 完整回答".equals(LegacyConversationPayload.messageText(markdown)),
                "Worker markdown 正文未读取");

        JsonObject legacy = new JsonObject();
        legacy.addProperty("role", "assistant");
        legacy.addProperty("content", "旧版回答");
        check("旧版回答".equals(LegacyConversationPayload.messageText(legacy)),
                "旧 content 字段未兼容");

        JsonObject event = new JsonObject();
        event.addProperty("answer", "完成事件回答");
        check("完成事件回答".equals(LegacyConversationPayload.answerText(event, "流式草稿")),
                "完成事件 answer 未优先使用");
        check("流式草稿".equals(LegacyConversationPayload.answerText(
                new JsonObject(), "流式草稿")), "流式草稿回退失败");

        JsonArray messages = new JsonArray();
        messages.add(markdown);
        check(LegacyConversationPayload.hasAssistantText(messages),
                "助手 markdown 消息未识别");

        check(LegacyAssistantScreen.closeExitsAssistant(LegacyAssistantScreen.Panel.MAIN),
                "主页面关闭应退出助手");
        check(!LegacyAssistantScreen.closeExitsAssistant(LegacyAssistantScreen.Panel.SETTINGS),
                "设置页关闭应返回助手");
        check(!LegacyAssistantScreen.closeExitsAssistant(LegacyAssistantScreen.Panel.HISTORY),
                "历史页关闭应返回助手");
        System.out.println("LegacyConversationPayloadSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
