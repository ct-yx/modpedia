package io.ctyx.modpedia.client;

import java.util.function.Consumer;

public interface AssistantSession {
    AssistantUiState state();

    void submit(String prompt);

    void cancel();

    void retry();

    void clear();

    void addListener(Consumer<AssistantUiState> listener);

    void removeListener(Consumer<AssistantUiState> listener);

    default boolean isLoading() {
        return state() instanceof AssistantUiState.Loading;
    }
}
