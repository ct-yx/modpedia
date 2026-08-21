package io.ctyx.modpedia.ai;

import io.ctyx.modpedia.api.SourceReference;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析模型回答中的来源标记，不把模型未检索过的 ID 直接交给客户端跳转。 */
public final class SourceCitationParser {
    private static final Pattern SOURCE_TOKEN = Pattern.compile(
            "\\[\\[source:([^|\\]]+)(?:\\|([^\\]]*))?\\]\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern PATTERN = Pattern.compile(
            "\\[(?:来源|source)\\s*[:：]\\s*(\\[[^\\]]+\\]\\([^)]*\\)|[^\\]|]+?)"
                    + "(?:\\s*[|｜]\\s*(?:(?:标注|说明|label|annotation)\\s*[:：]\\s*)?([^\\]]+?))?\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private SourceCitationParser() {
    }

    public static List<Citation> parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Matcher matcher = PATTERN.matcher(answer);
        List<LocatedCitation> located = new ArrayList<>();
        List<int[]> citationRanges = new ArrayList<>();
        Matcher sourceToken = SOURCE_TOKEN.matcher(answer);
        while (sourceToken.find()) {
            String documentId = cleanDocumentId(sourceToken.group(1));
            if (!documentId.isBlank()) {
                located.add(new LocatedCitation(
                        sourceToken.start(),
                        new Citation(documentId, sourceToken.group(2) == null
                                ? ""
                                : sourceToken.group(2).strip())
                ));
            }
            citationRanges.add(new int[]{sourceToken.start(), sourceToken.end()});
        }
        while (matcher.find()) {
            if (insideAny(matcher.start(), matcher.end(), citationRanges)) {
                continue;
            }
            String rawDocument = matcher.group(1);
            String documentId = cleanDocumentId(rawDocument);
            if (documentId.isBlank()) {
                continue;
            }
            String annotation = matcher.group(2) == null ? "" : matcher.group(2).strip();
            LinkParts link = linkParts(rawDocument);
            if (annotation.isBlank() && link != null && !link.label().equalsIgnoreCase(documentId)) {
                annotation = link.label();
            }
            located.add(new LocatedCitation(
                    matcher.start(),
                    new Citation(documentId, annotation)
            ));
            citationRanges.add(new int[]{matcher.start(), matcher.end()});
        }

        // 兼容模型偶尔直接输出 [标题](document_id) 的 Markdown 引用；只接受像
        // 本地文档 ID 的目标，外部网页链接不会被当作可跳转来源。
        Matcher linkMatcher = MARKDOWN_LINK.matcher(answer);
        while (linkMatcher.find()) {
            if (insideAny(linkMatcher.start(), linkMatcher.end(), citationRanges)) {
                continue;
            }
            String label = linkMatcher.group(1).strip();
            String target = cleanLinkTarget(linkMatcher.group(2));
            String documentId = looksLikeDocumentId(target) ? target
                    : looksLikeDocumentId(label) ? cleanDocumentId(label) : "";
            if (documentId.isBlank()) {
                continue;
            }
            located.add(new LocatedCitation(
                    linkMatcher.start(),
                    new Citation(
                            documentId,
                            looksLikeDocumentId(target) && !label.equalsIgnoreCase(documentId) ? label : ""
                    )
            ));
        }
        located.sort(java.util.Comparator.comparingInt(LocatedCitation::start));
        return located.stream().map(LocatedCitation::citation).toList();
    }

    /**
     * 移除玩家不需要直接看到的来源协议标记，但保留其所在文本行的位置。
     *
     * <p>客户端会在调用本方法前解析引用，并把解析到的来源标记渲染成正文内的可点击
     * 标注。因此这里不能把整段回答交给一个全局清理后再由 UI 重新拼来源。</p>
     */
    public static String removeCitationMarkup(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        String result = SOURCE_TOKEN.matcher(answer).replaceAll("");
        result = PATTERN.matcher(result).replaceAll("");
        result = result.replaceAll(
                "(?im)^\\s*(?:[*_`#>\\-+ ]*)(?:来源|sources?)(?:\\s*[:：])?\\s*(?:[*_`#>\\-+ ]*)$",
                ""
        );
        result = removeLocalMarkdownLinks(result);
        result = result.replaceAll("(?m)^\\s*[-*+]\\s*$", "");
        return result.replaceAll("\\n{3,}", "\\n\\n").strip();
    }

    public static List<SourceReference> selectSources(
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
                        all.putIfAbsent(documentKey(source.documentId()), source);
                    }
                }
            }
        }
        Map<String, SourceReference> selected = new LinkedHashMap<>();
        int max = Math.max(1, limit);
        for (Citation citation : parse(answer)) {
            SourceReference source = all.get(documentKey(citation.documentId()));
            if (source == null) {
                continue;
            }
            SourceReference annotated = citation.annotation().isBlank()
                    ? source
                    : source.withAnnotation(citation.annotation());
            selected.putIfAbsent(documentKey(citation.documentId()), annotated);
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
        String cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        LinkParts link = linkParts(cleaned);
        if (link != null) {
            String target = cleanLinkTarget(link.target());
            cleaned = looksLikeDocumentId(target) ? target : link.label();
        }
        cleaned = cleaned.replaceAll("^[`*_~]+|[`*_~]+$", "").strip();
        return cleaned.replaceAll("[，。；、,:：.!?！？]+$", "").strip();
    }

    private static LinkParts linkParts(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = MARKDOWN_LINK.matcher(value.strip());
        return matcher.matches()
                ? new LinkParts(matcher.group(1).strip(), matcher.group(2).strip())
                : null;
    }

    private static String cleanLinkTarget(String value) {
        String target = value == null ? "" : value.strip();
        int fragment = target.indexOf('#');
        return fragment >= 0 ? target.substring(0, fragment) : target;
    }

    private static boolean looksLikeDocumentId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]+:[^\\s]+")
                && !value.startsWith("http:") && !value.startsWith("https:");
    }

    private static boolean insideAny(int start, int end, List<int[]> ranges) {
        return ranges.stream().anyMatch(range -> start >= range[0] && end <= range[1]);
    }

    private static String removeLocalMarkdownLinks(String value) {
        Matcher matcher = MARKDOWN_LINK.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1);
            String target = cleanLinkTarget(matcher.group(2));
            if (looksLikeDocumentId(target) || looksLikeDocumentId(label)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(label));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 供客户端按同一规则匹配模型引用与搜索轨迹中的文档 ID。 */
    public static String normalizeDocumentId(String value) {
        return documentKey(value);
    }

    private static String documentKey(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[`*_~\\s]+", "")
                .strip();
    }

    public record Citation(String documentId, String annotation) {
        public Citation {
            documentId = documentId == null ? "" : documentId.strip();
            annotation = annotation == null ? "" : annotation.strip();
        }
    }

    private record LinkParts(String label, String target) {
    }

    private record LocatedCitation(int start, Citation citation) {
    }
}
