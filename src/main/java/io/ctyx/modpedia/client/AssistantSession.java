package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.ConversationSummary;
import io.ctyx.modpedia.knowledge.BuiltInGuide;
import java.util.List;
import java.util.function.Consumer;

public interface AssistantSession {
    AssistantUiState state();

    void submit(String prompt);

    /** 直接显示内置说明，不发起 AI 请求。 */
    default void showBuiltInGuide(String documentId) {
    }

    void cancel();

    void retry();

    void clear();

    void addListener(Consumer<AssistantUiState> listener);

    void removeListener(Consumer<AssistantUiState> listener);

    /** 历史会话接口由真实 AI 会话实现；模拟会话保留默认空实现。 */
    default List<ConversationSummary> conversations() {
        return List.of();
    }

    default String activeConversationId() {
        return "";
    }

    default String activeConversationTitle() {
        return "";
    }

    default void newConversation() {
        clear();
    }

    default void selectConversation(String conversationId) {
    }

    default void renameConversation(String conversationId, String title) {
    }

    default void deleteConversation(String conversationId) {
    }

    default boolean isLoading() {
        return state() instanceof AssistantUiState.Loading;
    }
}
