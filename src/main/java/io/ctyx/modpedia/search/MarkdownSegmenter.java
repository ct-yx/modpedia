package io.ctyx.modpedia.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将统一 Markdown 拆成可返回的完整段落，同时保留标题和代码块边界。 */
final class MarkdownSegmenter {
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    private MarkdownSegmenter() {
    }

    static List<MarkdownSegment> split(String markdown) {
        String body = stripFrontMatter(markdown == null ? "" : markdown);
        if (body.isBlank()) {
            return List.of();
        }

        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<MarkdownSegment> segments = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        String pendingHeading = null;
        StringBuilder current = new StringBuilder();
        boolean fenced = false;
        int segmentIndex = 0;

        for (String line : lines) {
            if (isFence(line)) {
                if (current.isEmpty() && pendingHeading != null) {
                    current.append(pendingHeading).append("\n\n");
                    pendingHeading = null;
                }
                current.append(line).append('\n');
                fenced = !fenced;
                continue;
            }

            if (fenced) {
                current.append(line).append('\n');
                continue;
            }

            Matcher heading = HEADING_PATTERN.matcher(line);
            if (heading.matches()) {
                segmentIndex = appendSegment(segments, current, headingPath(headingStack), segmentIndex);
                int level = heading.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(cleanHeading(heading.group(2)));
                pendingHeading = line;
                continue;
            }

            if (line.isBlank()) {
                segmentIndex = appendSegment(segments, current, headingPath(headingStack), segmentIndex);
                continue;
            }

            if (current.isEmpty() && pendingHeading != null) {
                current.append(pendingHeading).append("\n\n");
                pendingHeading = null;
            }
            current.append(line).append('\n');
        }

        segmentIndex = appendSegment(segments, current, headingPath(headingStack), segmentIndex);
        if (segments.isEmpty() && pendingHeading != null) {
            segments.add(new MarkdownSegment(pendingHeading.trim(), headingPath(headingStack), segmentIndex));
        }
        return List.copyOf(segments);
    }

    private static int appendSegment(
            List<MarkdownSegment> segments,
            StringBuilder current,
            String headingPath,
            int segmentIndex
    ) {
        String value = current.toString().trim();
        current.setLength(0);
        if (value.isBlank()) {
            return segmentIndex;
        }
        segments.add(new MarkdownSegment(value, headingPath, segmentIndex));
        return segmentIndex + 1;
    }

    private static String stripFrontMatter(String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            return normalized.trim();
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return normalized.trim();
        }
        int bodyStart = end + "\n---".length();
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return normalized.substring(bodyStart).trim();
    }

    private static boolean isFence(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static String cleanHeading(String heading) {
        return heading.replaceAll("\\s+#+$", "").trim();
    }

    private static String headingPath(List<String> headings) {
        return String.join(" > ", headings);
    }

    record MarkdownSegment(String markdown, String headingPath, int index) {
    }
}
