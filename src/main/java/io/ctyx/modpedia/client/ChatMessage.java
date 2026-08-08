package io.ctyx.modpedia.client;

import java.util.List;

public record ChatMessage(
        MessageRole role,
        String markdown,
        List<SourceReference> sources
) {
    public ChatMessage {
        sources = List.copyOf(sources);
    }
}
