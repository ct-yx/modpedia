package io.ctyx.modpedia.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.network.LegacyFtbQuestSnapshotMessage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1.12.2 FTBQ 可选事件适配层。
 *
 * <p>不直接链接 FTBQ 类，缺少 FTBQ 时本类仍可被 Dedicated Server 加载。任务定义和
 * 进度文件由现有读取器负责；这里仅在进入世界时捕获一次，并在任务完成事件后追加
 * completed ID 和 timeline，然后通过 Forge 网络同步给客户端。</p>
 */
public final class LegacyFtbQuestEventBridge {
    private static final String FTBQ_MOD_ID = "ftbquests";
    private static final String QUEST_COMPLETED_EVENT =
            "com.feed_the_beast.ftbquests.events.ObjectCompletedEvent$QuestEvent";

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        syncPlayer(event.player);
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        syncPlayer(event.player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        syncPlayer(event.player);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clearPlayer(event.player);
    }

    /** FTBQ 的 EventBase 最终发布到 Forge EventBus；通过类名反射保持可选依赖。 */
    @SubscribeEvent
    public void onForgeEvent(Event event) {
        if (!isFtbQuestsLoaded() || event == null
                || !QUEST_COMPLETED_EVENT.equals(event.getClass().getName())) {
            return;
        }
        String questId = questId(event);
        if (questId.isEmpty()) {
            return;
        }
        for (EntityPlayerMP player : eventPlayers(event)) {
            addCompletionAndSend(player, questId);
        }
    }

    private void syncPlayer(EntityPlayer player) {
        if (!isFtbQuestsLoaded() || !(player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP serverPlayer = (EntityPlayerMP) player;
        Path worldRoot = worldRoot(serverPlayer);
        if (worldRoot == null) {
            return;
        }
        JsonObject snapshot = LegacyFtbQuestRuntimeReader.captureServerSnapshot(
                worldRoot, serverPlayer.getUniqueID().toString(), serverPlayer.getName()
        );
        Set<String> completed = completedFromRuntime(serverPlayer);
        if (!completed.isEmpty()) {
            snapshot = LegacyFtbQuestRuntimeReader.mergeServerCompletions(
                    worldRoot,
                    serverPlayer.getUniqueID().toString(),
                    serverPlayer.getName(),
                    completed
            );
        }
        send(serverPlayer, snapshot);
    }

    private void addCompletionAndSend(EntityPlayerMP player, String questId) {
        Path worldRoot = worldRoot(player);
        if (worldRoot == null) {
            return;
        }
        JsonObject snapshot = LegacyFtbQuestRuntimeReader.addServerCompletion(
                worldRoot,
                player.getUniqueID().toString(),
                player.getName(),
                questId,
                System.currentTimeMillis()
        );
        send(player, snapshot);
    }

    private void clearPlayer(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        Path worldRoot = worldRoot((EntityPlayerMP) player);
        if (worldRoot != null) {
            LegacyFtbQuestRuntimeReader.clearServerSnapshot(
                    worldRoot, player.getUniqueID().toString()
            );
        }
    }

    private void send(EntityPlayerMP player, JsonObject snapshot) {
        String scope = "";
        if (snapshot != null && snapshot.has("scope_key")) {
            JsonElement value = snapshot.get("scope_key");
            if (value != null && value.isJsonPrimitive()) {
                scope = value.getAsString();
            }
        }
        ModPedia.NETWORK.sendTo(new LegacyFtbQuestSnapshotMessage(scope, snapshot), player);
    }

    private Path worldRoot(EntityPlayerMP player) {
        try {
            return player.world.getSaveHandler().getWorldDirectory()
                    .toPath().toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isFtbQuestsLoaded() {
        return Loader.isModLoaded(FTBQ_MOD_ID);
    }

    private String questId(Object event) {
        Object quest = invoke(event, "getQuest");
        if (quest == null) {
            quest = invoke(event, "getObject");
        }
        return questCode(quest);
    }

    private String questCode(Object quest) {
        if (quest == null) {
            return "";
        }
        String value = text(invoke(quest, "getCodeString"));
        if (value.isEmpty()) {
            value = text(invoke(quest, "getId"));
        }
        if (value.isEmpty() && quest != null) {
            value = text(field(quest, "id"));
        }
        return normalizeId(value);
    }

    /** 通过 FTBQ 已加载的 ServerQuestData 补齐进入世界前已经完成的任务。 */
    private Set<String> completedFromRuntime(EntityPlayerMP player) {
        Set<String> result = new LinkedHashSet<String>();
        try {
            Class<?> universeType = Class.forName(
                    "com.feed_the_beast.ftblib.lib.data.Universe"
            );
            Object universe = universeType.getMethod("get").invoke(null);
            Object forgePlayer = universeType.getMethod("getPlayer", java.util.UUID.class)
                    .invoke(universe, player.getUniqueID());
            Object team = field(forgePlayer, "team");
            if (team == null) {
                return result;
            }
            Class<?> serverDataType = Class.forName(
                    "com.feed_the_beast.ftbquests.util.ServerQuestData"
            );
            Object data = serverDataType.getMethod("get", team.getClass()).invoke(null, team);
            Object taskData = field(data, "taskData");
            Object values = invoke(taskData, "values");
            if (!(values instanceof Iterable)) {
                return result;
            }
            for (Object entry : (Iterable) values) {
                Object complete = invoke(entry, "isComplete");
                if (!Boolean.TRUE.equals(complete)) {
                    continue;
                }
                Object task = field(entry, "task");
                Object quest = field(task, "quest");
                if (quest == null) {
                    quest = invoke(task, "getQuest");
                }
                String id = questCode(quest);
                if (!id.isEmpty()) {
                    result.add(id);
                }
            }
        } catch (Throwable ignored) {
            // FTBQ/FTBLib 的运行时结构因版本不同而变化时，文件快照仍可用。
        }
        return result;
    }

    private List<EntityPlayerMP> eventPlayers(Object event) {
        Map<String, EntityPlayerMP> players = new LinkedHashMap<String, EntityPlayerMP>();
        addPlayers(players, invoke(event, "getOnlineMembers"));
        if (players.isEmpty()) {
            addPlayers(players, invoke(event, "getNotifiedPlayers"));
        }
        if (players.isEmpty()) {
            Object data = invoke(event, "getData");
            addPlayers(players, invoke(data, "getOnlineMembers"));
        }
        return new ArrayList<EntityPlayerMP>(players.values());
    }

    private void addPlayers(Map<String, EntityPlayerMP> target, Object value) {
        if (value instanceof Iterable) {
            for (Object item : (Iterable) value) {
                addPlayer(target, item);
            }
        } else if (value instanceof EntityPlayerMP) {
            addPlayer(target, value);
        }
    }

    private void addPlayer(Map<String, EntityPlayerMP> target, Object value) {
        if (value instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) value;
            target.put(player.getUniqueID().toString(), player);
        }
    }

    private Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object field(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getField(fieldName);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
