package io.ctyx.modpedia.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析模型回答末尾的后续问题协议，并从玩家可见正文中移除协议标记。 */
public final class FollowUpQuestionParser {
    private static final Pattern TAGGED_BLOCK = Pattern.compile(
            "(?is)<(?:modpedia_)?follow_up_questions>\\s*(.*?)\\s*</(?:modpedia_)?follow_up_questions>"
    );
    private static final Pattern HEADING_BLOCK = Pattern.compile(
            "(?ims)^\\s{0,3}#{1,3}\\s*(?:可能继续询问|你可能还会问|后续问题|suggested follow[- ]?up questions|follow[- ]?up questions)\\s*$\\n?(.*?)(?=^\\s{0,3}#{1,3}\\s+|\\z)"
    );
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:[-*+•]|\\d+[.)])\\s+(.*?)\\s*$"
    );

    private FollowUpQuestionParser() {
    }

    public static Parsed parse(String markdown) {
        String value = markdown == null ? "" : markdown;
        Matcher tagged = TAGGED_BLOCK.matcher(value);
        if (tagged.find()) {
            return new Parsed(
                    value.substring(0, tagged.start()) + value.substring(tagged.end()),
                    questions(tagged.group(1))
            );
        }

        Matcher heading = HEADING_BLOCK.matcher(value);
        if (heading.find()) {
            return new Parsed(
                    value.substring(0, heading.start()) + value.substring(heading.end()),
                    questions(heading.group(1))
            );
        }
        return new Parsed(value, List.of());
    }

    private static List<String> questions(String block) {
        List<String> result = new ArrayList<>();
        if (block == null) {
            return List.of();
        }
        for (String line : block.replace("\\r", "").split("\\n")) {
            Matcher item = LIST_ITEM.matcher(line);
            if (!item.matches()) {
                continue;
            }
            String question = clean(item.group(1));
            if (!question.isBlank() && !result.contains(question)) {
                result.add(question);
            }
            if (result.size() == 3) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String result = value.strip();
        if (result.startsWith("`")) {
            result = result.replaceAll("^`+|`+$", "").strip();
        }
        return result.replaceAll("\\s+", " ").strip();
    }

    public record Parsed(String markdown, List<String> questions) {
        public Parsed {
            markdown = markdown == null ? "" : markdown.strip();
            questions = questions == null ? List.of() : questions.stream()
                    .filter(question -> question != null && !question.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(3)
                    .toList();
        }
    }
}
