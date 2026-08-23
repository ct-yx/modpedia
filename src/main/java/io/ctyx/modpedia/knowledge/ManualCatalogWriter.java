package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 把来源索引原子写入当前实例的知识目录。 */
public final class ManualCatalogWriter {
    private ManualCatalogWriter() {
    }

    public static Path writeManifest(Path knowledgeRoot, ManualScanResult result) throws IOException {
        Files.createDirectories(knowledgeRoot);
        Path target = knowledgeRoot.resolve("manifest.json");
        Path temporary = knowledgeRoot.resolve("manifest.json.tmp");
        Files.write(temporary, json(result).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String json(ManualScanResult result) {
        StringBuilder output = new StringBuilder(4096);
        output.append("{\n")
                .append("  \"schema\": 1,\n")
                .append("  \"minecraft\": \"1.12.2\",\n")
                .append("  \"sources\": [\n");
        List<ManualSourceDescriptor> sources = result.getSources();
        for (int index = 0; index < sources.size(); index++) {
            ManualSourceDescriptor source = sources.get(index);
            output.append("    {")
                    .append("\"source_id\":\"").append(escape(source.getSourceId())).append("\",")
                    .append("\"collection_id\":\"").append(escape(source.getCollectionId())).append("\",")
                    .append("\"content_kind\":\"").append(source.getContentKind().getId()).append("\",")
                    .append("\"source_type\":\"").append(source.getSourceType().getId()).append("\",")
                    .append("\"origin_type\":\"").append(escape(source.getOriginType())).append("\",")
                    .append("\"title\":\"").append(escape(source.getTitle())).append("\",")
                    .append("\"language\":\"").append(escape(source.getLanguage())).append("\",")
                    .append("\"version\":\"").append(escape(source.getVersion())).append("\",")
                    .append("\"source_path\":\"").append(escape(source.getSourcePath())).append("\",")
                    .append("\"fingerprint\":\"").append(escape(source.getFingerprint())).append("\",")
                    .append("\"file_count\":").append(source.getFileCount())
                    .append("}");
            if (index + 1 < sources.size()) {
                output.append(',');
            }
            output.append("\n");
        }
        output.append("  ],\n")
                .append("  \"archive_count\": ").append(result.getArchiveCount()).append(",\n")
                .append("  \"warnings\": [\n");
        List<String> warnings = result.getWarnings();
        for (int index = 0; index < warnings.size(); index++) {
            output.append("    \"").append(escape(warnings.get(index))).append("\"");
            if (index + 1 < warnings.size()) {
                output.append(',');
            }
            output.append("\n");
        }
        output.append("  ],\n")
                .append("  \"generated_at\": ")
                .append(result.getScannedAt().getTime())
                .append("\n}\n");
        return output.toString();
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
