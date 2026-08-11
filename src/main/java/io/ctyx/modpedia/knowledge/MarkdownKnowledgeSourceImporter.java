package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** 导入可扩展 sources/<id>/documents 下的 Markdown Wiki 来源。 */
public final class MarkdownKnowledgeSourceImporter implements KnowledgeSourceImporter {
    private final MarkdownDocumentConverter converter = new MarkdownDocumentConverter();

    @Override
    public boolean supports(KnowledgeSourceDescriptor source) {
        return source != null && "wiki_markdown".equalsIgnoreCase(source.sourceType());
    }

    @Override
    public List<ImportedKnowledgeDocument> importDocuments(
            Path sourceRoot,
            KnowledgeSourceDescriptor source
    ) throws IOException {
        Path documentsRoot = sourceRoot.resolve(source.localRoot().isBlank() ? "documents" : source.localRoot());
        if (!Files.isDirectory(documentsRoot)) {
            return List.of();
        }

        List<ImportedKnowledgeDocument> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(documentsRoot)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(this::isMarkdown)
                    .sorted()
                    .toList()) {
                byte[] bytes = Files.readAllBytes(path);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                ScannedResource scanned = new ScannedResource(
                        source.sourceId(),
                        source.title(),
                        source.version(),
                        relative,
                        source.sourceType(),
                        content,
                        sha256(bytes),
                        Map.of(),
                        source.contentKind(),
                        source.sourceId(),
                        source.collectionId(),
                        source.priority(),
                        source.originType(),
                        source.metadataJson()
                );
                KnowledgeDocument document = converter.convert(scanned);
                result.add(new ImportedKnowledgeDocument(
                        document,
                        relative,
                        sha256(bytes),
                        source.priority(),
                        languageOf(content, source.language())
                ));
            }
        }
        return List.copyOf(result);
    }

    private boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private String languageOf(String content, String fallback) {
        MarkdownDocumentConverter.CustomMetadata metadata = converter.inspectCustom(content);
        return metadata.validFrontMatter() && !metadata.language().isBlank()
                ? metadata.language()
                : fallback;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
