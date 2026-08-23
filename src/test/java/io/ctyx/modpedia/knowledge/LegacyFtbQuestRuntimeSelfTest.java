package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 运行时任务读取不落盘、不写知识库并生成变化时间线的回归测试。 */
public final class LegacyFtbQuestRuntimeSelfTest {
    private LegacyFtbQuestRuntimeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("modpedia-112-runtime");
        String player = "00000000-0000-0000-0000-000000000001";
        Path binary = world.resolve("data/ftb_lib/teams/singleplayer/ftbquests.dat");
        Files.createDirectories(binary.getParent());
        writeBinary(binary, 2L);
        Path file = world.resolve("ftbquests").resolve(player + ".snbt");
        Files.createDirectories(file.getParent());
        Files.write(file, "{started:{a:1L},completed:{b:2L},task_progress:{task:2}}"
                .getBytes(StandardCharsets.UTF_8));
        LegacyFtbQuestRuntimeReader.setWorldContext(world, player);
        JsonObject first = LegacyFtbQuestRuntimeReader.readSnapshot();
        assertTrue(first != null && first.get("available").getAsBoolean(), "snapshot available");
        assertTrue(first.getAsJsonObject("task_progress").has("b6245f0a"), "binary task progress");
        writeBinary(binary, 3L);
        JsonObject second = LegacyFtbQuestRuntimeReader.readSnapshot();
        assertTrue(second.getAsJsonObject("task_progress").get("b6245f0a").getAsDouble() == 3D,
                "binary progress update");
        assertTrue(second.getAsJsonArray("timeline").size() >= 1, "timeline update");

        JsonObject serverInitial = LegacyFtbQuestRuntimeReader.captureServerSnapshot(
                world, player, "player"
        );
        assertTrue(serverInitial != null && serverInitial.get("available").getAsBoolean(),
                "server snapshot available");
        JsonObject completed = LegacyFtbQuestRuntimeReader.addServerCompletion(
                world, player, "player", "Quest-1", 1234L
        );
        assertTrue(completed.getAsJsonArray("completed_quest_ids").toString()
                        .contains("quest-1"),
                "completion added");
        int timelineSize = completed.getAsJsonArray("timeline").size();
        JsonObject duplicate = LegacyFtbQuestRuntimeReader.addServerCompletion(
                world, player, "player", "quest-1", 5678L
        );
        assertTrue(duplicate.getAsJsonArray("timeline").size() == timelineSize,
                "duplicate completion is idempotent");
        JsonObject initialCompleted = LegacyFtbQuestRuntimeReader.mergeServerCompletions(
                world, player, "player", Arrays.asList("quest-2", "quest-1")
        );
        assertTrue(initialCompleted.getAsJsonArray("completed_quest_ids").toString()
                        .contains("quest-2"),
                "initial completed snapshot merge");
        assertTrue(initialCompleted.getAsJsonArray("timeline").size() == timelineSize,
                "initial merge does not create completion event");

        LegacyFtbQuestRuntimeReader.applyRemoteSnapshot(
                "server:test", duplicate
        );
        assertTrue(LegacyFtbQuestRuntimeReader.readSnapshot() != null,
                "remote snapshot available");
        LegacyFtbQuestRuntimeReader.clearRemoteSnapshot();
        LegacyFtbQuestRuntimeReader.clear();
        System.out.println("LegacyFtbQuestRuntimeSelfTest: OK");
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void writeBinary(Path path, long progress) throws Exception {
        NBTTagCompound tasks = new NBTTagCompound();
        tasks.setLong("b6245f0a", progress);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Tasks", tasks);
        try (OutputStream output = Files.newOutputStream(path)) {
            CompressedStreamTools.writeCompressed(root, output);
        }
    }
}
