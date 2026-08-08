package io.ctyx.modpedia.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** 将扫描结果写入本地知识库，并生成清单与关键词索引。 */
public final class KnowledgeCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final MarkdownDocumentConverter markdownConverter = new MarkdownDocumentConverter();
    private final JsonGuideDocumentConverter jsonConverter = new JsonGuideDocumentConverter();

    public CompileResult compile(Path configDirectory, LocalGuideScanner.ScanResult scanResult) throws IOException {
        return compile(configDirectory, scanResult, false);
    }

    /**
     * 构建知识库。
     *
     * @param forceRebuild true 时跳过来源指纹复用，但仍会清理已经移除的来源
     */
    public CompileResult compile(
            Path configDirectory,
            LocalGuideScanner.ScanResult scanResult,
            boolean forceRebuild
    ) throws IOException {
        Path knowledgeRoot = configDirectory.resolve("modpedia").resolve("knowledge");
        Path generatedRoot = knowledgeRoot.resolve("generated");
        Path customRoot = knowledgeRoot.resolve("custom");
        Path cacheRoot = knowledgeRoot.resolve("cache");
        Files.createDirectories(generatedRoot);
        Files.createDirectories(customRoot);
        Files.createDirectories(cacheRoot);

        List<String> warnings = new ArrayList<>();
        Map<String, String> previousFingerprints = loadPreviousFingerprints(
                knowledgeRoot.resolve("state.json"),
                warnings
        );
        Map<String, String> reusableFingerprints = forceRebuild ? Map.of() : previousFingerprints;
        Map<String, DocumentEntry> documents = new TreeMap<>();
        Map<String, String> currentSources = new TreeMap<>();
        int generatedCount = 0;
        int updatedCount = 0;
        int reusedCount = 0;

        for (ScannedResource source : scanResult.resources()) {
            String sourceKey = sourceKey(source);
            currentSources.put(sourceKey, source.fingerprint());
            String relativePath = generatedRelativePath(source);
            Path output = knowledgeRoot.resolve(relativePath);
            KnowledgeDocument document;

            if (source.fingerprint().equals(reusableFingerprints.get(sourceKey)) && Files.isRegularFile(output)) {
                try {
                    document = loadGeneratedDocument(source, output);
                    reusedCount++;
                } catch (IOException | RuntimeException exception) {
                    warnings.add("读取缓存知识失败，重新转换：" + source.path());
                    document = convert(source);
                    writeDocument(output, document);
                    updatedCount++;
                }
            } else {
                document = convert(source);
                writeDocument(output, document);
                updatedCount++;
            }

            documents.put(document.id(), new DocumentEntry(document, relativePath));
            generatedCount++;
        }

        int removedCount = removeRemovedGenerated(
                knowledgeRoot,
                previousFingerprints.keySet(),
                currentSources.keySet(),
                warnings
        );
        int customCount = loadCustomDocuments(customRoot, documents, warnings);
        writeManifest(knowledgeRoot, documents);
        writeKeywordIndex(knowledgeRoot, documents);
        writeState(knowledgeRoot, currentSources, documents);

        BuildReport report = new BuildReport(
                Instant.now().toString(),
                scanResult.resources().size(),
                generatedCount,
                updatedCount,
                reusedCount,
                removedCount,
                customCount,
                documents.size(),
                warnings
        );
        Files.writeString(cacheRoot.resolve("build-report.json"), GSON.toJson(report), StandardCharsets.UTF_8);
        return new CompileResult(knowledgeRoot, report);
    }

    private KnowledgeDocument convert(ScannedResource source) {
        return source.sourceType().endsWith("markdown")
                ? markdownConverter.convert(source)
                : jsonConverter.convert(source);
    }

    private KnowledgeDocument loadGeneratedDocument(ScannedResource source, Path output) throws IOException {
        String content = Files.readString(output, StandardCharsets.UTF_8);
        ScannedResource cachedSource = new ScannedResource(
                source.modId(),
                source.modName(),
                source.version(),
                source.path(),
                source.sourceType(),
                content,
                source.fingerprint(),
                source.translations()
        );
        return markdownConverter.convert(cachedSource);
    }

    private void writeDocument(Path output, KnowledgeDocument document) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, document.body(), StandardCharsets.UTF_8);
    }

    private int loadCustomDocuments(
            Path customRoot,
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
                } catch (IOException | RuntimeException exception) {
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

    private void writeState(
            Path root,
            Map<String, String> currentSources,
            Map<String, DocumentEntry> documents
    ) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema_version", 2);
        state.put("updated_at", Instant.now().toString());
        state.put("document_count", documents.size());
        state.put("source_count", currentSources.size());
        state.put("sources", currentSources);
        writeJson(root.resolve("state.json"), state);
    }

    private Map<String, String> loadPreviousFingerprints(Path statePath, List<String> warnings) {
        if (!Files.isRegularFile(statePath)) {
            return Map.of();
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(statePath, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("sources")
                    || !parsed.getAsJsonObject().get("sources").isJsonObject()) {
                throw new IllegalStateException("sources 不是对象");
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().getAsJsonObject("sources").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    result.put(entry.getKey(), value.getAsString());
                } else if (value.isJsonObject() && value.getAsJsonObject().has("fingerprint")) {
                    JsonElement fingerprint = value.getAsJsonObject().get("fingerprint");
                    if (fingerprint.isJsonPrimitive() && fingerprint.getAsJsonPrimitive().isString()) {
                        result.put(entry.getKey(), fingerprint.getAsString());
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            warnings.add("读取知识库状态失败，将重新转换全部来源");
            return Map.of();
        }
    }

    private int removeRemovedGenerated(
            Path knowledgeRoot,
            Set<String> previousSources,
            Set<String> currentSources,
            List<String> warnings
    ) {
        int removedCount = 0;
        for (String sourceKey : previousSources) {
            if (currentSources.contains(sourceKey)) {
                continue;
            }
            Path output = generatedPath(knowledgeRoot, sourceKey);
            if (output == null) {
                continue;
            }
            try {
                if (Files.deleteIfExists(output)) {
                    removedCount++;
                }
            } catch (IOException exception) {
                warnings.add("删除已移除知识失败：" + sourceKey);
            }
        }
        return removedCount;
    }

    private Path generatedPath(Path knowledgeRoot, String sourceKey) {
        int separator = sourceKey.indexOf(':');
        if (separator <= 0 || separator == sourceKey.length() - 1) {
            return null;
        }
        String modId = sourceKey.substring(0, separator);
        String sourcePath = sourceKey.substring(separator + 1);
        return knowledgeRoot.resolve(generatedRelativePath(modId, sourcePath));
    }

    private String generatedRelativePath(ScannedResource source) {
        return generatedRelativePath(source.modId(), source.path());
    }

    private String generatedRelativePath(String modId, String sourcePath) {
        return "generated/" + safeSegment(modId) + "/" + safeDocumentFileName(sourcePath);
    }

    private String sourceKey(ScannedResource source) {
        return source.modId() + ":" + source.path();
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
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
            int updatedCount,
            int reusedCount,
            int removedCount,
            int customCount,
            int documentCount,
            List<String> warnings
    ) {
    }

    private record DocumentEntry(KnowledgeDocument document, String relativePath) {
    }
}
