package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.client.SourceReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析模型回答中的来源标记，不把模型未检索过的 ID 直接交给客户端跳转。 */
final class SourceCitationParser {
    private static final Pattern PATTERN = Pattern.compile(
            "\\[(?:来源|source)\\s*[:：]\\s*([^\\]|]+?)"
                    + "(?:\\s*[|｜]\\s*(?:(?:标注|说明|label|annotation)\\s*[:：]\\s*)?([^\\]]+?))?\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private SourceCitationParser() {
    }

    static List<Citation> parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Matcher matcher = PATTERN.matcher(answer);
        List<Citation> citations = new ArrayList<>();
        while (matcher.find()) {
            String documentId = cleanDocumentId(matcher.group(1));
            if (documentId.isBlank()) {
                continue;
            }
            String annotation = matcher.group(2) == null ? "" : matcher.group(2).strip();
            citations.add(new Citation(documentId, annotation));
        }
        return List.copyOf(citations);
    }

    /**
     * 移除已经被客户端转换为来源卡片的引用标记，避免正文中再次显示一串不可点击的
     * Markdown 链接。没有解析到引用时原样返回，保留模型回答内容。
     */
    static String removeCitationMarkup(String answer) {
        if (answer == null || answer.isBlank() || parse(answer).isEmpty()) {
            return answer == null ? "" : answer;
        }
        String result = PATTERN.matcher(answer).replaceAll("");
        result = result.replaceAll(
                "(?im)^\\s*(?:[*_`#>\\-+ ]*)(?:来源|sources?)(?:\\s*:)?\\s*(?:[*_`#>\\-+ ]*)$",
                ""
        );
        result = result.replaceAll("(?m)^\\s*[-*+]\\s*$", "");
        return result.replaceAll("\\n{3,}", "\\n\\n").strip();
    }

    static List<SourceReference> selectSources(
            List<SearchTrace> traces,
            String answer,
            int limit
    ) {
        Map<String, SourceReference> all = new LinkedHashMap<>();
        if (traces != null) {
            for (SearchTrace trace : traces) {
                if (trace == null) {
                    continue;
                }
                for (SourceReference source : trace.sources()) {
                    if (source != null) {
                        all.putIfAbsent(source.documentId(), source);
                    }
                }
            }
        }
        Map<String, SourceReference> selected = new LinkedHashMap<>();
        int max = Math.max(1, limit);
        for (Citation citation : parse(answer)) {
            SourceReference source = all.get(citation.documentId());
            if (source == null) {
                continue;
            }
            SourceReference annotated = citation.annotation().isBlank()
                    ? source
                    : source.withAnnotation(citation.annotation());
            selected.putIfAbsent(citation.documentId(), annotated);
            if (selected.size() >= max) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }

    private static String cleanDocumentId(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().replaceAll("^[`*_~]+|[`*_~]+$", "").strip();
    }

    record Citation(String documentId, String annotation) {
        Citation {
            documentId = documentId == null ? "" : documentId.strip();
            annotation = annotation == null ? "" : annotation.strip();
        }
    }
}
