package io.ctyx.modpedia.client;

import java.util.List;

public sealed interface AssistantUiState
        permits AssistantUiState.Conversation, AssistantUiState.Loading, AssistantUiState.Error {
    List<ChatMessage> messages();

    record Conversation(List<ChatMessage> messages, boolean noResult) implements AssistantUiState {
        public Conversation {
            messages = List.copyOf(messages);
        }
    }

    record Loading(List<ChatMessage> messages, String phase, String assistantDraft) implements AssistantUiState {
        public Loading(List<ChatMessage> messages) {
            this(messages, "", "");
        }

        public Loading {
            messages = List.copyOf(messages);
            phase = phase == null ? "" : phase;
            assistantDraft = assistantDraft == null ? "" : assistantDraft;
        }
    }

    record Error(List<ChatMessage> messages, String message) implements AssistantUiState {
        public Error {
            messages = List.copyOf(messages);
        }
    }
}
