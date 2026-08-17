package io.ctyx.modpedia.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.storage.ModPediaPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final AppGuideDocumentConverter appConverter = new AppGuideDocumentConverter();

    public CompileResult compile(Path configDirectory, KnowledgeScanResult scanResult) throws IOException {
        return compile(configDirectory, scanResult, false);
    }

    /**
     * 构建知识库。
     *
     * @param forceRebuild true 时跳过来源指纹复用，但仍会清理已经移除的来源
     */
    public CompileResult compile(
            Path configDirectory,
            KnowledgeScanResult scanResult,
            boolean forceRebuild
    ) throws IOException {
        ModPediaPaths paths = ModPediaPaths.forConfig(configDirectory);
        paths.migrateLegacy();
        return compile(paths.contentRoot(), paths.runtimeKnowledgeRoot(), scanResult, forceRebuild);
    }

    /**
     * 使用分离后的事实源目录和运行时派生目录构建知识库。
     *
     * @param contentRoot 随整合包保留的 {@code config/modpedia/knowledge}
     * @param runtimeKnowledgeRoot 可删除并可从事实源重建的运行时知识目录
     */
    public CompileResult compile(
            Path contentRoot,
            Path runtimeKnowledgeRoot,
            KnowledgeScanResult scanResult,
            boolean forceRebuild
    ) throws IOException {
        contentRoot = contentRoot.toAbsolutePath().normalize();
        runtimeKnowledgeRoot = runtimeKnowledgeRoot.toAbsolutePath().normalize();
        Path generatedRoot = runtimeKnowledgeRoot.resolve("generated");
        Path customRoot = contentRoot.resolve("custom");
        Path sourcesRoot = contentRoot.resolve("sources");
        Path cacheRoot = runtimeKnowledgeRoot.resolve("cache");
        Files.createDirectories(generatedRoot);
        Files.createDirectories(customRoot);
        Files.createDirectories(sourcesRoot);
        Files.createDirectories(cacheRoot);

        List<String> warnings = new ArrayList<>();
        Map<String, SourceState> previousSources = loadPreviousSources(
                runtimeKnowledgeRoot.resolve("state.json"),
                warnings
        );
        Map<String, String> previousFingerprints = previousSources.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().fingerprint(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, String> reusableFingerprints = forceRebuild ? Map.of() : previousFingerprints;
        Map<String, DocumentEntry> documents = new TreeMap<>();
        Map<String, SourceState> currentSources = new TreeMap<>();
        Map<String, KnowledgeDatabase.DocumentInput> databaseInputs = new LinkedHashMap<>();
        // 即使是 F9 强制重建，也要保留这份缓存作为非法自定义文档的回退来源；
        // 强制重建只禁止“未变化直接复用”，不应让一次格式错误删除上一份有效内容。
        Map<String, KnowledgeDatabase.CachedDocument> cachedCustomDocuments =
                KnowledgeDatabase.readCachedCustomDocuments(KnowledgeDatabase.path(runtimeKnowledgeRoot));
        int generatedCount = 0;
        int updatedCount = 0;
        int reusedCount = 0;

        for (ScannedResource source : scanResult.resources()) {
            String sourceKey = sourceKey(source);
            SourceState previous = previousSources.get(sourceKey);
            List<DocumentEntry> converted;
            boolean reused = source.fingerprint().equals(reusableFingerprints.get(sourceKey))
                    && previous != null
                    && !previous.outputPaths().isEmpty();
            if (reused) {
                try {
                    converted = loadGeneratedDocuments(source, runtimeKnowledgeRoot, previous.outputPaths());
                    reused = !converted.isEmpty();
                } catch (IOException | RuntimeException exception) {
                    warnings.add("读取缓存知识失败，重新转换：" + source.path());
                    converted = List.of();
                    reused = false;
                }
            } else {
                converted = List.of();
            }

            if (!reused) {
                List<KnowledgeDocument> generated = convertAll(source);
                List<String> outputPaths = outputPaths(source, generated);
                deleteObsoleteOutputs(runtimeKnowledgeRoot, previous, outputPaths, warnings);
                List<DocumentEntry> entries = new ArrayList<>(generated.size());
                for (int index = 0; index < generated.size(); index++) {
                    KnowledgeDocument document = generated.get(index);
                    String relativePath = outputPaths.get(index);
                    writeDocument(runtimeKnowledgeRoot.resolve(relativePath), document);
                    entries.add(new DocumentEntry(document, relativePath));
                }
                converted = List.copyOf(entries);
                updatedCount += converted.size();
                currentSources.put(sourceKey, new SourceState(source.fingerprint(), outputPaths));
            } else {
                reusedCount += converted.size();
                currentSources.put(sourceKey, new SourceState(
                        source.fingerprint(),
                        converted.stream().map(DocumentEntry::relativePath).toList()
                ));
            }

            for (DocumentEntry entry : converted) {
                KnowledgeDocument document = entry.document();
                documents.put(document.id(), entry);
                String databaseSourceKey = databaseSourceKey(sourceKey, document, converted.size());
                databaseInputs.put(
                        databaseSourceKey,
                        new KnowledgeDatabase.DocumentInput(
                                databaseSourceKey,
                                source.fingerprint(),
                                entry.relativePath(),
                                languageOf(document.sourcePath()),
                                source.priority(),
                                document
                        )
                );
                generatedCount++;
            }
        }

        loadWikiSources(contentRoot, documents, databaseInputs, warnings);

        int removedCount = removeRemovedGenerated(
                runtimeKnowledgeRoot,
                previousSources,
                currentSources.keySet(),
                warnings
        );
        int customCount = loadCustomDocuments(
                customRoot,
                documents,
                databaseInputs,
                cachedCustomDocuments,
                forceRebuild,
                warnings
        );
        boolean databaseSynchronized = true;
        try {
            KnowledgeDatabase.sync(runtimeKnowledgeRoot, databaseInputs.values(), forceRebuild);
        } catch (IOException | RuntimeException exception) {
            // SQLite 是派生搜索库；同步失败时保留旧数据库，JSON/Markdown 仍可用于迁移和诊断。
            databaseSynchronized = false;
            warnings.add("SQLite 知识库同步失败，保留上一版本：" + messageOf(exception));
        }

        // 只有 SQLite 替换成功后才提交这些派生清单。否则下一次启动会看到
        // “新 manifest + 旧 knowledge.db”的混合状态，日志也会误导诊断。
        if (databaseSynchronized) {
            writeManifest(runtimeKnowledgeRoot, documents);
            writeKeywordIndex(runtimeKnowledgeRoot, documents);
            writeState(runtimeKnowledgeRoot, currentSources, documents);
        }

        BuildReport report = new BuildReport(
                Instant.now().toString(),
                countDocumentSources(documents),
                generatedCount,
                updatedCount,
                reusedCount,
                removedCount,
                customCount,
                documents.size(),
                warnings
        );
        Files.writeString(cacheRoot.resolve("build-report.json"), GSON.toJson(report), StandardCharsets.UTF_8);
        return new CompileResult(runtimeKnowledgeRoot, report, databaseSynchronized);
    }

    private List<KnowledgeDocument> convertAll(ScannedResource source) {
        if ("app_json".equals(source.sourceType())) {
            return appConverter.convertAll(source);
        }
        return source.sourceType().endsWith("markdown")
                ? markdownConverter.convertAll(source)
                : jsonConverter.convertAll(source);
    }

    private List<DocumentEntry> loadGeneratedDocuments(
            ScannedResource source,
            Path knowledgeRoot,
            List<String> outputPaths
    ) throws IOException {
        List<DocumentEntry> result = new ArrayList<>();
        for (String relativePath : outputPaths) {
            Path output = knowledgeRoot.resolve(relativePath);
            if (!Files.isRegularFile(output)) {
                return List.of();
            }
            String content = Files.readString(output, StandardCharsets.UTF_8);
            ScannedResource cachedSource = new ScannedResource(
                    source.modId(),
                    source.modName(),
                    source.version(),
                    relativePath,
                    source.sourceType(),
                    content,
                    source.fingerprint(),
                    source.translations(),
                    source.contentKind(),
                    source.sourceId(),
                    source.collectionId(),
                    source.priority(),
                    source.originType(),
                    source.metadataJson()
            );
            result.add(new DocumentEntry(markdownConverter.convert(cachedSource), relativePath));
        }
        return List.copyOf(result);
    }

    private void writeDocument(Path output, KnowledgeDocument document) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, document.body(), StandardCharsets.UTF_8);
    }

    private List<String> outputPaths(ScannedResource source, List<KnowledgeDocument> documents) {
        String base = generatedRelativePath(source);
        if (documents.size() <= 1) {
            return List.of(base);
        }
        String stem = base.endsWith(".md") ? base.substring(0, base.length() - 3) : base;
        List<String> result = new ArrayList<>(documents.size());
        for (KnowledgeDocument document : documents) {
            result.add(stem + "__" + safeFileName(document.id()) + ".md");
        }
        return List.copyOf(result);
    }

    private void deleteObsoleteOutputs(
            Path knowledgeRoot,
            SourceState previous,
            List<String> currentOutputs,
            List<String> warnings
    ) {
        if (previous == null) {
            return;
        }
        for (String oldOutput : previous.outputPaths()) {
            if (currentOutputs.contains(oldOutput)) {
                continue;
            }
            try {
                Files.deleteIfExists(knowledgeRoot.resolve(oldOutput));
            } catch (IOException exception) {
                warnings.add("删除旧版知识文件失败：" + oldOutput);
            }
        }
    }

    private String databaseSourceKey(String sourceKey, KnowledgeDocument document, int documentCount) {
        return documentCount <= 1 ? sourceKey : sourceKey + "#" + document.id();
    }

    private int loadCustomDocuments(
            Path customRoot,
            Map<String, DocumentEntry> documents,
            Map<String, KnowledgeDatabase.DocumentInput> databaseInputs,
            Map<String, KnowledgeDatabase.CachedDocument> cachedDocuments,
            boolean forceRebuild,
            List<String> warnings
    ) throws IOException {
        int count = 0;
        try (Stream<Path> paths = Files.walk(customRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isMarkdown).sorted().toList()) {
                String relativePath = "custom/" + customRoot.relativize(path).toString().replace('\\', '/');
                try {
                    byte[] bytes = readLimited(path, 8L * 1024L * 1024L);
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    String fingerprint = sha256(bytes);
                    KnowledgeDatabase.CachedDocument cached = cachedDocuments.get(relativePath);
                    if (!forceRebuild && cached != null && fingerprint.equals(cached.input().fingerprint())) {
                        KnowledgeDatabase.DocumentInput input = cached.input();
                        documents.put(input.document().id(), new DocumentEntry(input.document(), relativePath));
                        databaseInputs.put(input.sourceKey(), input);
                        count++;
                        continue;
                    }

                    MarkdownDocumentConverter.CustomMetadata metadata = markdownConverter.inspectCustom(content);
                    if (!metadata.validFrontMatter() || metadata.id().isBlank()) {
                        if (cached != null) {
                            KnowledgeDatabase.DocumentInput input = cached.input();
                            documents.put(input.document().id(), new DocumentEntry(input.document(), relativePath));
                            databaseInputs.put(input.sourceKey(), input);
                        }
                        warnings.add("自定义知识缺少有效 Front Matter 或稳定 id，保留上一版本：" + relativePath);
                        if (cached != null) {
                            count++;
                        }
                        continue;
                    }

                    KnowledgeDocument document = markdownConverter.convertCustom(customRoot.relativize(path), content);
                    documents.put(document.id(), new DocumentEntry(document, relativePath));
                    String sourceKey = "custom:" + relativePath;
                    databaseInputs.put(
                            sourceKey,
                            new KnowledgeDatabase.DocumentInput(
                                sourceKey,
                                fingerprint,
                                relativePath,
                                metadata.language(),
                                metadata.priority(),
                                document
                            )
                    );
                    count++;
                } catch (IOException | RuntimeException exception) {
                    KnowledgeDatabase.CachedDocument cached = cachedDocuments.get(relativePath);
                    if (cached != null) {
                        KnowledgeDatabase.DocumentInput input = cached.input();
                        documents.put(input.document().id(), new DocumentEntry(input.document(), relativePath));
                        databaseInputs.put(input.sourceKey(), input);
                        count++;
                        warnings.add("解析自定义知识失败，保留上一版本：" + relativePath);
                    } else {
                        warnings.add("解析自定义知识失败：" + relativePath);
                    }
                }
            }
        }
        return count;
    }

    private void loadWikiSources(
            Path knowledgeRoot,
            Map<String, DocumentEntry> documents,
            Map<String, KnowledgeDatabase.DocumentInput> databaseInputs,
            List<String> warnings
    ) throws IOException {
        WikiSourceLoader.LoadResult loadResult = new WikiSourceLoader().load(knowledgeRoot, warnings);
        if (!loadResult.complete()) {
            // Wiki 输入是可编辑的本地事实源。任何一个来源损坏时必须让本次
            // 编译失败，而不是拿不完整的输入删除上一版索引；KnowledgeDatabase
            // 仍负责保留正式库，下一次启动会再次尝试读取修复后的来源。
            throw new IOException("Wiki 来源不完整，已取消本次知识库替换；上一版本仍保留");
        }
        for (KnowledgeSourceImporter.ImportedKnowledgeDocument entry : loadResult.documents()) {
            KnowledgeDocument document = entry.document();
            documents.put(document.id(), new DocumentEntry(document, entry.relativePath()));
            String sourceKey = "wiki:" + document.sourceId() + ":" + entry.relativePath();
            databaseInputs.put(
                    sourceKey,
                    new KnowledgeDatabase.DocumentInput(
                            sourceKey,
                            entry.fingerprint(),
                            entry.relativePath(),
                            entry.language(),
                            entry.priority(),
                            document
                    )
            );
        }
    }

    private String languageOf(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/zh_cn/") || normalized.contains("/_zh_cn/")) {
            return "zh_cn";
        }
        if (normalized.contains("/en_us/") || normalized.contains("/_en_us/")) {
            return "en_us";
        }
        return "neutral";
    }

    private String sha256(byte[] bytes) {
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

    private byte[] readLimited(Path path, long limit) throws IOException {
        long size = Files.size(path);
        if (size > limit) {
            throw new IOException("自定义 Markdown 超过大小上限：" + path);
        }
        try (var input = Files.newInputStream(path);
             var output = new java.io.ByteArrayOutputStream((int) Math.min(size, 8192L))) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("自定义 Markdown 超过大小上限：" + path);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private int countDocumentSources(Map<String, DocumentEntry> documents) {
        return (int) documents.values().stream()
                .map(entry -> {
                    KnowledgeDocument document = entry.document();
                    String sourceId = document.sourceId();
                    if (sourceId == null || sourceId.isBlank()) {
                        return document.sourceMod();
                    }
                    return document.sourceType() + ":" + sourceId;
                })
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .count();
    }

    private String messageOf(Exception exception) {
        StringBuilder message = new StringBuilder();
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth++ < 4) {
            if (message.length() > 0) {
                message.append(" <- ");
            }
            message.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return message.toString();
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
            value.put("content_kind", document.contentKind().id());
            value.put("source_id", document.sourceId());
            value.put("collection_id", document.collectionId());
            value.put("origin_type", document.originType());
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
            Map<String, SourceState> currentSources,
            Map<String, DocumentEntry> documents
    ) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema_version", 2);
        state.put("updated_at", Instant.now().toString());
        state.put("document_count", documents.size());
        state.put("source_count", countDocumentSources(documents));
        Map<String, Object> sources = new TreeMap<>();
        currentSources.forEach((sourceKey, source) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("fingerprint", source.fingerprint());
            value.put("documents", source.outputPaths());
            sources.put(sourceKey, value);
        });
        state.put("sources", sources);
        writeJson(root.resolve("state.json"), state);
    }

    private Map<String, SourceState> loadPreviousSources(Path statePath, List<String> warnings) {
        if (!Files.isRegularFile(statePath)) {
            return Map.of();
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(statePath, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("sources")
                    || !parsed.getAsJsonObject().get("sources").isJsonObject()) {
                throw new IllegalStateException("sources 不是对象");
            }

            Map<String, SourceState> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().getAsJsonObject("sources").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    result.put(entry.getKey(), new SourceState(
                            value.getAsString(),
                            List.of(generatedPathString(entry.getKey()))
                    ));
                } else if (value.isJsonObject() && value.getAsJsonObject().has("fingerprint")) {
                    JsonElement fingerprint = value.getAsJsonObject().get("fingerprint");
                    if (fingerprint.isJsonPrimitive() && fingerprint.getAsJsonPrimitive().isString()) {
                        List<String> outputPaths = new ArrayList<>();
                        JsonElement documents = value.getAsJsonObject().get("documents");
                        if (documents != null && documents.isJsonArray()) {
                            for (JsonElement document : documents.getAsJsonArray()) {
                                if (document.isJsonPrimitive() && document.getAsJsonPrimitive().isString()) {
                                    outputPaths.add(document.getAsString());
                                }
                            }
                        }
                        if (outputPaths.isEmpty()) {
                            outputPaths.add(generatedPathString(entry.getKey()));
                        }
                        result.put(entry.getKey(), new SourceState(fingerprint.getAsString(), outputPaths));
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
            Map<String, SourceState> previousSources,
            Set<String> currentSources,
            List<String> warnings
    ) {
        int removedCount = 0;
        for (Map.Entry<String, SourceState> entry : previousSources.entrySet()) {
            String sourceKey = entry.getKey();
            if (currentSources.contains(sourceKey)) {
                continue;
            }
            for (String outputPath : entry.getValue().outputPaths()) {
                try {
                    if (Files.deleteIfExists(knowledgeRoot.resolve(outputPath))) {
                        removedCount++;
                    }
                } catch (IOException exception) {
                    warnings.add("删除已移除知识失败：" + sourceKey);
                }
            }
        }
        return removedCount;
    }

    private String generatedPathString(String sourceKey) {
        int separator = sourceKey.indexOf(':');
        if (separator <= 0 || separator == sourceKey.length() - 1) {
            return "";
        }
        String modId = sourceKey.substring(0, separator);
        String sourcePath = sourceKey.substring(separator + 1);
        return generatedRelativePath(modId, sourcePath);
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

    public record CompileResult(
            Path knowledgeRoot,
            BuildReport report,
            boolean databaseSynchronized
    ) {
        /** SQLite 同步失败时，report 仍用于诊断，但本次构建不能作为完成状态发布。 */
        public boolean successful() {
            return databaseSynchronized;
        }
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

    private record SourceState(String fingerprint, List<String> outputPaths) {
        private SourceState {
            fingerprint = fingerprint == null ? "" : fingerprint;
            outputPaths = outputPaths == null
                    ? List.of()
                    : outputPaths.stream().filter(path -> path != null && !path.isBlank()).toList();
        }
    }
}
