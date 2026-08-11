package io.ctyx.modpedia.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.ModPedia;
import io.ctyx.modpedia.task.TaskKnowledgeStore;
import io.ctyx.modpedia.task.TaskSnapshot;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 可选任务模组的客户端适配器。
 *
 * <p>任务模组不进入 ModPedia 的硬依赖。这里只通过公开 API 的反射入口读取任务定义，
 * 读取失败时保留旧快照，不影响助手和 Dedicated Server 加载。</p>
 */
public final class FtbQuestsClientAdapter {
    private static final String MOD_ID = "ftbquests";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final TaskKnowledgeStore store;
    private int ticksUntilRefresh;
    private String lastFingerprint = "";
    private boolean warnedUnavailable;

    public FtbQuestsClientAdapter(TaskKnowledgeStore store) {
        this.store = store;
    }

    public void tick(Minecraft minecraft) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            lastFingerprint = "";
            return;
        }
        if (ticksUntilRefresh-- > 0) {
            return;
        }
        ticksUntilRefresh = 40;
        try {
            TaskSnapshot snapshot = readSnapshot(minecraft);
            if (snapshot == null || snapshot.quests().isEmpty()) {
                return;
            }
            if (snapshot.fingerprint().equals(lastFingerprint)) {
                return;
            }
            store.syncSnapshot(snapshot);
            lastFingerprint = snapshot.fingerprint();
            ModPedia.LOGGER.info(
                    "任务知识快照已同步：quests={}, scope={}",
                    snapshot.quests().size(),
                    snapshot.scopeKey()
            );
        } catch (Throwable failure) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                ModPedia.LOGGER.warn(
                        "读取可选任务模组快照失败，继续使用旧任务数据：{}",
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
                );
            }
        }
    }

    private TaskSnapshot readSnapshot(Minecraft minecraft) throws Exception {
        Class<?> apiClass = Class.forName("dev.ftb.mods.ftbquests.api.FTBQuestsAPI");
        Object api = invokeStatic(apiClass, "api");
        Object questFile = invoke(api, "getQuestFile", true);
        if (questFile == null) {
            return null;
        }

        List<Object> quests = new ArrayList<>();
        collectQuests(questFile, quests);
        quests.sort(Comparator.comparing(value -> text(invokeNoArg(value, "getId"))));

        Object teamData = findTeamData(questFile, minecraft.player.getUUID(), minecraft.player);
        JsonObject raw = new JsonObject();
        JsonArray rawQuests = new JsonArray();
        List<TaskSnapshot.TaskQuest> snapshotQuests = new ArrayList<>();
        for (int index = 0; index < quests.size(); index++) {
            Object quest = quests.get(index);
            JsonObject rawQuest = questJson(quest, teamData);
            rawQuests.add(rawQuest);
            snapshotQuests.add(questSnapshot(quest, teamData, index, rawQuest));
        }
        raw.add("quests", rawQuests);

        UUID playerId = minecraft.player.getUUID();
        String scopeKey = "player:" + playerId;
        String sourceKey = "ftbquests:" + worldKey(minecraft);
        String rawJson = JSON.toJson(raw);
        return new TaskSnapshot(
                sha256(rawJson + "|" + scopeKey),
                sourceKey,
                sha256(rawJson),
                scopeKey,
                modVersion(),
                rawJson,
                snapshotQuests
        );
    }

    private void collectQuests(Object questFile, List<Object> result) throws Exception {
        Method method = findMethod(questFile.getClass(), "forAllQuests", 1);
        if (method == null) {
            return;
        }
        Class<?> parameter = method.getParameterTypes()[0];
        Object callback;
        if (parameter.isAssignableFrom(java.util.function.Consumer.class)) {
            callback = (java.util.function.Consumer<Object>) result::add;
        } else if (parameter.isInterface()) {
            callback = Proxy.newProxyInstance(
                    parameter.getClassLoader(),
                    new Class<?>[]{parameter},
                    (proxy, invoked, args) -> {
                        if (args != null && args.length > 0 && args[0] != null) {
                            result.add(args[0]);
                        }
                        return null;
                    }
            );
        } else {
            return;
        }
        method.setAccessible(true);
        method.invoke(questFile, callback);
    }

    private TaskSnapshot.TaskQuest questSnapshot(
            Object quest,
            Object teamData,
            int index,
            JsonObject rawQuest
    ) {
        String questId = text(invokeNoArg(quest, "getId"));
        String parentId = text(firstInvoke(quest, "getParentID", "getParentId", "getParent"));
        String title = firstText(quest, "getRawTitle", "getTitle", "getName");
        if (title.isBlank()) {
            title = questId;
        }
        String subtitle = firstText(quest, "getRawSubtitle", "getSubtitle");
        String description = firstText(quest, "getRawDescription", "getDescription");
        boolean completed = booleanValue(invokeOne(teamData, "isCompleted", quest),
                booleanValue(firstInvoke(quest, "isCompleted", "getCompleted"), false));
        boolean started = booleanValue(invokeOne(teamData, "isStarted", quest),
                booleanValue(firstInvoke(quest, "isStarted", "getStarted"), false));
        boolean optional = booleanValue(firstInvoke(quest, "isOptional", "getOptional"), false);
        boolean visible = !booleanValue(firstInvoke(quest, "isHidden", "getHidden"), false);
        List<String> dependencies = ids(firstInvoke(quest, "getDependencies", "getDependencyIds"));
        List<TaskSnapshot.TaskRequirement> tasks = taskRequirements(quest, teamData);
        List<TaskSnapshot.TaskReward> rewards = taskRewards(quest);
        return new TaskSnapshot.TaskQuest(
                questId,
                parentId,
                title,
                subtitle,
                description,
                optional,
                visible,
                started,
                completed,
                index,
                dependencies,
                tasks,
                rewards,
                JSON.toJson(rawQuest)
        );
    }

    private List<TaskSnapshot.TaskRequirement> taskRequirements(Object quest, Object teamData) {
        List<TaskSnapshot.TaskRequirement> result = new ArrayList<>();
        int index = 0;
        for (Object task : values(firstInvoke(quest, "getTasks", "tasks"))) {
            JsonObject raw = valueJson(task);
            String taskId = text(firstInvoke(task, "getId", "getTaskId"));
            if (taskId.isBlank()) {
                taskId = "task_" + (++index);
            }
            String type = firstText(task, "getType", "getTaskType");
            if (type.isBlank()) {
                type = task.getClass().getSimpleName();
            }
            String target = firstText(task, "getTargetId", "getItemId", "getBlockId", "getEntityId", "getTag");
            String title = firstText(task, "getTitle", "getName");
            double required = number(firstInvoke(task, "getRequired", "getRequiredCount", "getCount"), 1);
            double current = number(invokeOne(teamData, "getProgress", task),
                    number(firstInvoke(task, "getProgress", "getCurrent"), 0));
            boolean completed = booleanValue(invokeOne(teamData, "isCompleted", task),
                    booleanValue(firstInvoke(task, "isCompleted", "getCompleted"), current >= required && required > 0));
            result.add(new TaskSnapshot.TaskRequirement(
                    taskId,
                    type,
                    title.isBlank() ? target : title,
                    target,
                    current,
                    required,
                    completed,
                    JSON.toJson(raw)
            ));
        }
        return List.copyOf(result);
    }

    private List<TaskSnapshot.TaskReward> taskRewards(Object quest) {
        List<TaskSnapshot.TaskReward> result = new ArrayList<>();
        int index = 0;
        for (Object reward : values(firstInvoke(quest, "getRewards", "rewards"))) {
            JsonObject raw = valueJson(reward);
            String rewardId = text(firstInvoke(reward, "getId", "getRewardId"));
            if (rewardId.isBlank()) {
                rewardId = "reward_" + (++index);
            }
            String type = firstText(reward, "getType", "getRewardType");
            if (type.isBlank()) {
                type = reward.getClass().getSimpleName();
            }
            String title = firstText(reward, "getTitle", "getName");
            List<String> candidates = ids(firstInvoke(
                    reward,
                    "getCandidates",
                    "getPossibleRewards",
                    "getEntries",
                    "getItems"
            ));
            boolean random = candidates.size() > 1
                    || type.toLowerCase(java.util.Locale.ROOT).contains("random")
                    || type.toLowerCase(java.util.Locale.ROOT).contains("loot")
                    || title.contains("随机")
                    || title.contains("箱");
            result.add(new TaskSnapshot.TaskReward(
                    rewardId,
                    type,
                    title.isBlank() ? type : title,
                    !random && booleanValue(firstInvoke(reward, "isGuaranteed", "getGuaranteed"), true),
                    candidates,
                    JSON.toJson(raw)
            ));
        }
        return List.copyOf(result);
    }

    private JsonObject questJson(Object quest, Object teamData) {
        JsonObject value = valueJson(quest);
        value.addProperty("runtime_started", booleanValue(invokeOne(teamData, "isStarted", quest), false));
        value.addProperty("runtime_completed", booleanValue(invokeOne(teamData, "isCompleted", quest), false));
        return value;
    }

    private Object findTeamData(Object questFile, UUID playerId, Object player) {
        for (String name : List.of("getTeamData", "getData", "getPlayerData")) {
            Object value = invokeOne(questFile, name, playerId);
            if (value == null) {
                value = invokeOne(questFile, name, player);
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String modVersion() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(container -> String.valueOf(container.getModInfo().getVersion()))
                    .orElse("unknown");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private String worldKey(Minecraft minecraft) {
        try {
            return minecraft.level.dimension().location().toString();
        } catch (RuntimeException ignored) {
            return "local";
        }
    }

    private Object invokeStatic(Class<?> type, String name, Object... arguments) throws Exception {
        Method method = findCompatibleMethod(type, name, arguments);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private Object invoke(Object target, String name, Object... arguments) throws Exception {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, arguments);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private Object firstInvoke(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name, 0);
                if (method != null) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 公开 API 在不同小版本间可能只保留其中一个别名。
            }
        }
        return null;
    }

    private Object invokeNoArg(Object target, String name) {
        return firstInvoke(target, name);
    }

    private Object invokeOne(Object target, String name, Object argument) {
        try {
            return invoke(target, name, argument);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(Object target, String... names) {
        for (String name : names) {
            Object value = firstInvoke(target, name);
            String text = text(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        try {
            Method getString = findMethod(value.getClass(), "getString", 0);
            if (getString != null) {
                Object string = getString.invoke(value);
                if (string != null) {
                    return string.toString().strip();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return value.toString().strip();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private List<?> values(Object value) {
        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return List.copyOf(map.values());
        }
        return List.of();
    }

    private List<String> ids(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String id = text(firstInvoke(item, "getId", "getQuestId", "getItemId"));
                if (id.isBlank()) {
                    id = text(item);
                }
                if (!id.isBlank()) {
                    result.add(id);
                }
            }
        } else if (value != null) {
            String id = text(value);
            if (!id.isBlank()) {
                result.add(id);
            }
        }
        return result.stream().distinct().toList();
    }

    private JsonObject valueJson(Object value) {
        JsonObject result = new JsonObject();
        if (value != null) {
            result.addProperty("runtime_type", value.getClass().getName());
            result.addProperty("value", text(value));
        }
        return result;
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private Method findCompatibleMethod(Class<?> type, String name, Object[] arguments) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                    continue;
                }
                boolean compatible = true;
                Class<?>[] parameters = method.getParameterTypes();
                for (int index = 0; index < parameters.length; index++) {
                    if (arguments[index] != null && !wrap(parameters[index]).isInstance(arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    return method;
                }
            }
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
