package io.ctyx.modpedia.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 1.12.2 一文件一任务到 Worker 静态任务输入的回归测试。 */
public final class LegacyFtbQuestNormalizerSelfTest {
    private LegacyFtbQuestNormalizerSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path instance = Files.createTempDirectory("modpedia-112-ftbq");
        try {
            Path file = instance.resolve("config/ftbquests/adventure/chapters/abc/quest.snbt");
            Files.createDirectories(file.getParent());
            String raw = "{title:\"{pack.quest.title}\",description:\"说明\",dependencies:[\"prev\"],"
                    + "tasks:[{uid:\"task1\",type:\"item\",items:[{item:\"minecraft:wool 1 14\"}]}],"
                    + "rewards:[{uid:\"reward1\",type:\"loot\",table:2}]}";
            Files.write(file, raw.getBytes(StandardCharsets.UTF_8));
            LegacyFtbQuestNormalizer.Result result = new LegacyFtbQuestNormalizer().normalize(
                    instance, instance.resolve("config/modpedia/knowledge")
            );
            assertEquals(1, result.getImportedQuests(), "imported quests");
            Path generated = instance.resolve("config/ftbquests/quests/modpedia_legacy");
            List<Path> outputs;
            try (Stream<Path> stream = Files.walk(generated)) {
                outputs = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            }
            assertEquals(1, outputs.size(), "generated files");
            Map<String, Object> chapter = LegacySnbtParser.compound(
                    LegacySnbtParser.parse(new String(Files.readAllBytes(outputs.get(0)), StandardCharsets.UTF_8))
            );
            List<Object> quests = LegacySnbtParser.list(chapter.get("quests"));
            assertEquals(1, quests.size(), "worker quest count");
            Map<String, Object> quest = LegacySnbtParser.compound(quests.get(0));
            assertEquals("quest", LegacySnbtParser.text(quest.get("id")), "quest id");
            assertTrue(LegacySnbtParser.text(quest.get("raw_legacy_source")).contains("quest.snbt"),
                    "source path");
            System.out.println("LegacyFtbQuestNormalizerSelfTest: OK");
        } finally {
            deleteTree(instance);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
