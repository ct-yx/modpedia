package io.ctyx.modpedia.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Worker 不可用时的轻量搜索回退；不打开 SQLite，不阻塞游戏线程。 */
public final class LegacyLocalSearch {
    private LegacyLocalSearch() {
    }

    public static String search(Path knowledgeRoot, String query) {
        if (knowledgeRoot == null || query == null || query.trim().isEmpty()) {
            return "没有可搜索的本地内容。";
        }
        final String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        List<Result> results = new ArrayList<Result>();
        Path[] roots = {
                knowledgeRoot.resolve("sources"),
                knowledgeRoot.resolve("custom"),
                knowledgeRoot.resolve("generated")
        };
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(LegacyLocalSearch::isMarkdown).collect(Collectors.toList())) {
                    String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    String lower = text.toLowerCase(Locale.ROOT);
                    int score = score(lower, normalizedQuery);
                    if (score > 0) {
                        results.add(new Result(score, path, text));
                    }
                }
            } catch (IOException ignored) {
                // 单个来源读取失败不影响其余来源。
            }
        }
        Collections.sort(results, new Comparator<Result>() {
            @Override
            public int compare(Result left, Result right) {
                return right.score - left.score;
            }
        });
        if (results.isEmpty()) {
            return "本地知识库中没有找到匹配内容。\n\n你可以换用物品 ID、任务 ID 或更具体的关键词。";
        }
        StringBuilder answer = new StringBuilder("## 本地搜索结果\n\n");
        int limit = Math.min(5, results.size());
        for (int index = 0; index < limit; index++) {
            Result result = results.get(index);
            answer.append("### ").append(title(result.text, result.path)).append("\n")
                    .append("来源：").append(result.path.toString()).append("\n\n")
                    .append(excerpt(result.text, normalizedQuery)).append("\n\n");
        }
        return answer.toString().trim();
    }

    private static int score(String text, String query) {
        int score = 0;
        for (String term : query.split("\\s+")) {
            if (term.length() > 0) {
                int position = text.indexOf(term);
                while (position >= 0) {
                    score++;
                    position = text.indexOf(term, position + term.length());
                }
            }
        }
        return score;
    }

    private static String excerpt(String text, String query) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int index = normalized.toLowerCase(Locale.ROOT).indexOf(query);
        int start = index < 0 ? 0 : Math.max(0, index - 160);
        int end = Math.min(normalized.length(), start + 700);
        return normalized.substring(start, end).trim();
    }

    private static String title(String text, Path path) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("#")) {
                return line.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return path.getFileName().toString();
    }

    private static boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private static final class Result {
        private final int score;
        private final Path path;
        private final String text;

        private Result(int score, Path path, String text) {
            this.score = score;
            this.path = path;
            this.text = text;
        }
    }
}
