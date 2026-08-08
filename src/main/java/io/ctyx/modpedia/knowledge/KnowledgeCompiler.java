package io.ctyx.modpedia.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** 将扫描结果写入本地知识库，并生成清单与关键词索引。 */
public final class KnowledgeCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final MarkdownDocumentConverter markdownConverter = new MarkdownDocumentConverter();
    private final JsonGuideDocumentConverter jsonConverter = new JsonGuideDocumentConverter();

    public CompileResult compile(Path configDirectory, LocalGuideScanner.ScanResult scanResult) throws IOException {
        Path knowledgeRoot = configDirectory.resolve("modpedia").resolve("knowledge");
        Path generatedRoot = knowledgeRoot.resolve("generated");
        Path customRoot = knowledgeRoot.resolve("custom");
        Path cacheRoot = knowledgeRoot.resolve("cache");
        Files.createDirectories(generatedRoot);
        Files.createDirectories(customRoot);
        Files.createDirectories(cacheRoot);
        clearGenerated(generatedRoot);

        Map<String, DocumentEntry> documents = new TreeMap<>();
        int generatedCount = 0;
        for (ScannedResource source : scanResult.resources()) {
            KnowledgeDocument document = convert(source);
            String relativePath = "generated/" + safeSegment(source.modId()) + "/" + safeDocumentFileName(source.path());
            Path output = knowledgeRoot.resolve(relativePath);
            Files.createDirectories(output.getParent());
            Files.writeString(output, document.body(), StandardCharsets.UTF_8);
            documents.put(document.id(), new DocumentEntry(document, relativePath));
            generatedCount++;
        }

        int customCount = loadCustomDocuments(customRoot, knowledgeRoot, documents, scanResult.warnings());
        writeManifest(knowledgeRoot, documents);
        writeKeywordIndex(knowledgeRoot, documents);
        writeState(knowledgeRoot, scanResult, documents);

        BuildReport report = new BuildReport(
                Instant.now().toString(),
                scanResult.resources().size(),
                generatedCount,
                customCount,
                documents.size(),
                scanResult.warnings()
        );
        Files.writeString(cacheRoot.resolve("build-report.json"), GSON.toJson(report), StandardCharsets.UTF_8);
        return new CompileResult(knowledgeRoot, report);
    }

    private KnowledgeDocument convert(ScannedResource source) {
        return source.sourceType().endsWith("markdown")
                ? markdownConverter.convert(source)
                : jsonConverter.convert(source);
    }

    private int loadCustomDocuments(
            Path customRoot,
            Path knowledgeRoot,
            Map<String, DocumentEntry> documents,
            List<String> warnings
    ) throws IOException {
        int count = 0;
        try (Stream<Path> paths = Files.walk(customRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isMarkdown).sorted().toList()) {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    KnowledgeDocument document = markdownConverter.convertCustom(customRoot.relativize(path), content);
                    String relativePath = "custom/" + customRoot.relativize(path).toString().replace('\\', '/');
                    documents.put(document.id(), new DocumentEntry(document, relativePath));
                    count++;
                } catch (RuntimeException exception) {
                    warnings.add("解析自定义知识失败：" + customRoot.relativize(path));
                }
            }
        }
        return count;
    }

    private void writeManifest(Path root, Map<String, DocumentEntry> documents) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DocumentEntry entry : documents.values()) {
            KnowledgeDocument document = entry.document();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", document.id());
            value.put("path", entry.relativePath());
            value.put("source_mod", document.sourceMod());
            value.put("source_type", document.sourceType());
            value.put("title", document.title());
            value.put("category", document.category());
            value.put("keywords", document.keywords());
            value.put("source_version", document.sourceVersion());
            value.put("source_path", document.sourcePath());
            entries.add(value);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", 1);
        manifest.put("generated_at", Instant.now().toString());
        manifest.put("document_count", entries.size());
        manifest.put("documents", entries);
        writeJson(root.resolve("manifest.json"), manifest);
    }

    private void writeKeywordIndex(Path root, Map<String, DocumentEntry> documents) throws IOException {
        Map<String, List<String>> index = new TreeMap<>();
        for (DocumentEntry entry : documents.values()) {
            for (String keyword : entry.document().keywords()) {
                String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
                List<String> documentIds = index.computeIfAbsent(normalizedKeyword, ignored -> new ArrayList<>());
                if (!documentIds.contains(entry.document().id())) {
                    documentIds.add(entry.document().id());
                }
            }
        }
        writeJson(root.resolve("keyword-index.json"), index);
    }

    private void writeState(Path root, LocalGuideScanner.ScanResult scanResult, Map<String, DocumentEntry> documents) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema_version", 1);
        state.put("updated_at", Instant.now().toString());
        state.put("document_count", documents.size());
        state.put("sources", scanResult.resources().stream().collect(LinkedHashMap::new,
                (map, source) -> map.put(source.modId() + ":" + source.path(), source.fingerprint()),
                Map::putAll));
        writeJson(root.resolve("state.json"), state);
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    private void clearGenerated(Path generatedRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(generatedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(generatedRoot)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(generatedRoot);
    }

    private boolean isMarkdown(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".md");
    }

    private String safeSegment(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeFileName(String value) {
        return value.replace('\\', '_').replace('/', '_').replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeDocumentFileName(String value) {
        String fileName = safeFileName(value);
        if (fileName.endsWith(".md") || fileName.endsWith(".json")) {
            fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        }
        return fileName + ".md";
    }

    public record CompileResult(Path knowledgeRoot, BuildReport report) {
    }

    public record BuildReport(
            String generatedAt,
            int sourceCount,
            int generatedCount,
            int customCount,
            int documentCount,
            List<String> warnings
    ) {
    }

    private record DocumentEntry(KnowledgeDocument document, String relativePath) {
    }
}
