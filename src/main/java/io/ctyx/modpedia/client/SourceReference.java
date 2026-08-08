package io.ctyx.modpedia.client;

public record SourceReference(
        String documentId,
        String title,
        String sourceMod,
        String sourcePath
) {
}
