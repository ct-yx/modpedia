package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 发现并导入 sources/ 下的 Wiki 集合。 */
public final class WikiSourceLoader {
    private final List<KnowledgeSourceImporter> importers = List.of(new MarkdownKnowledgeSourceImporter());

    public List<KnowledgeSourceImporter.ImportedKnowledgeDocument> load(
            Path knowledgeRoot,
            List<String> warnings
    ) throws IOException {
        Path sourcesRoot = knowledgeRoot.resolve("sources");
        Files.createDirectories(sourcesRoot);
        List<KnowledgeSourceImporter.ImportedKnowledgeDocument> result = new ArrayList<>();
        try (Stream<Path> directories = Files.list(sourcesRoot)) {
            for (Path sourceDirectory : directories.filter(Files::isDirectory).sorted().toList()) {
                KnowledgeSourceDescriptor descriptor = readDescriptor(sourceDirectory, warnings);
                if (descriptor == null) {
                    continue;
                }
                KnowledgeSourceImporter importer = importers.stream()
                        .filter(candidate -> candidate.supports(descriptor))
                        .findFirst()
                        .orElse(null);
                if (importer == null) {
                    warnings.add("没有可用的 Wiki 导入器：" + descriptor.sourceType());
                    continue;
                }
                result.addAll(importer.importDocuments(sourceDirectory, descriptor));
            }
        }
        return List.copyOf(result);
    }

    private KnowledgeSourceDescriptor readDescriptor(Path sourceDirectory, List<String> warnings) {
        Path descriptorPath = sourceDirectory.resolve("source.json");
        if (!Files.isRegularFile(descriptorPath)) {
            String id = sourceDirectory.getFileName().toString();
            return new KnowledgeSourceDescriptor(
                    id,
                    id,
                    KnowledgeContentKind.WIKI,
                    "wiki_markdown",
                    "local",
                    id,
                    "neutral",
                    "unknown",
                    "",
                    "documents",
                    0,
                    "{}"
            );
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(descriptorPath, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("source.json 必须是对象");
            }
            JsonObject object = parsed.getAsJsonObject();
            String sourceId = text(object, "source_id", sourceDirectory.getFileName().toString());
            String collectionId = text(object, "collection_id", sourceId);
            return new KnowledgeSourceDescriptor(
                    sourceId,
                    collectionId,
                    KnowledgeContentKind.parse(text(object, "content_kind", "wiki")),
                    text(object, "source_type", "wiki_markdown"),
                    text(object, "origin_type", "local"),
                    text(object, "title", sourceId),
                    text(object, "language", "neutral"),
                    text(object, "version", "unknown"),
                    text(object, "origin_uri", ""),
                    text(object, "documents_root", "documents"),
                    integer(object, "priority", 0),
                    object.toString()
            );
        } catch (IOException | RuntimeException exception) {
            warnings.add("解析 Wiki source.json 失败：" + descriptorPath);
            return null;
        }
    }

    private String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()
                ? value.getAsString().strip()
                : fallback;
    }

    private int integer(JsonObject object, String key, int fallback) {
        try {
            return Integer.parseInt(text(object, key, Integer.toString(fallback)));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
