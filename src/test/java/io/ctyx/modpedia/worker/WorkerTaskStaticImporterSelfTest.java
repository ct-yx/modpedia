package io.ctyx.modpedia.worker;

import io.ctyx.modpedia.task.TaskSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Worker SNBT 任务导入夹具；不访问网络、不加载游戏类。 */
public final class WorkerTaskStaticImporterSelfTest {
    private WorkerTaskStaticImporterSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modpedia-task-import-");
        try {
            Path quests = root.resolve("quests");
            Path chapters = quests.resolve("chapters");
            Files.createDirectories(chapters);
            Files.createDirectories(quests.resolve("lang/zh_cn"));
            Files.writeString(quests.resolve("lang/en_us.snbt"), """
                    {
                      chapter.ABCD.title: "English Chapter"
                      quest.Q1.title: "English Quest"
                      quest.Q1.quest_desc: ["English description"]
                      task.T1.title: "English Task"
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(quests.resolve("lang/zh_cn.snbt"), """
                    {
                      chapter.ABCD.title: "中文章节"
                      quest.Q1.title: "中文任务"
                      quest.Q1.quest_desc: ["第一行" "第二行"]
                      task.T1.title: "收集材料"
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(chapters.resolve("example.snbt"), """
                    {
                      id: "ABCD"
                      filename: "example"
                      order_index: 2
                      quests: [
                        {
                          id: "Q1"
                          dependencies: ["ROOT"]
                          tasks: [
                            { id: "T1" type: "item" count: 4L item: { id: "example:ore" } }
                          ]
                          rewards: [
                            { id: "R1" type: "random" table_id: 123L }
                            { id: "R2" type: "item" item: { id: "example:ingot" count: 2 } }
                          ]
                          images: [{ image: "example:textures/guide.png" }]
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            WorkerTaskStaticImporter.ImportResult imported = new WorkerTaskStaticImporter()
                    .importDirectory(quests);
            check(imported.sourcePresent() && imported.complete(), "任务源应成功导入");
            check(imported.snapshots().size() == 1, "应生成一个章节快照");
            TaskSnapshot.TaskQuest quest = imported.snapshots().getFirst().quests().getFirst();
            check(quest.title().equals("中文任务"), "中文标题应优先于英文回退");
            check(quest.descriptionMarkdown().equals("第一行\n第二行"), "数组式描述应保留为多行文本");
            check(quest.dependencies().equals(List.of("ROOT")), "依赖应被导入");
            check(!quest.started() && !quest.completed(), "静态导入不得写入运行时状态");
            check(quest.tasks().getFirst().required() == 4D
                            && quest.tasks().getFirst().targetId().equals("example:ore")
                            && quest.tasks().getFirst().title().equals("收集材料"),
                    "物品任务的目标、数量和本地化标题应正确");
            check(quest.rewards().getFirst().candidates().equals(List.of("loot_table:123"))
                            && !quest.rewards().getFirst().guaranteed(),
                    "随机奖励应保留奖励表标识且不标记为保证获得");
            check(quest.rewards().get(1).candidates().equals(List.of("example:ingot"))
                            && quest.rewards().get(1).guaranteed(),
                    "物品奖励应保留物品 ID");
            check(imported.snapshots().getFirst().rawJson().contains("example:textures/guide.png"),
                    "未知图片节点应继续保留在原始 SNBT 中");
            System.out.println("ModPedia Worker task static importer self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
