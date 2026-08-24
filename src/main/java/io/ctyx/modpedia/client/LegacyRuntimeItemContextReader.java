package io.ctyx.modpedia.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.knowledge.LegacyItemIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1.12.2 客户端按需读取物品 Tooltip 的适配器。
 *
 * <p>它只在 Worker 明确请求时运行，而且只在 Minecraft 客户端线程执行。启动期
 * 物品目录只保存基础名称；Shift/Ctrl/Cmd、JEI/Jade 覆盖层和世界状态相关的
 * 内容按需捕获后通过 IPC 返回，由 Worker 缓存到 item_catalog。</p>
 */
public final class LegacyRuntimeItemContextReader {
    private static final Object KEYBOARD_LOCK = new Object();
    /** 单次 AI 工具查询的物品上限；总查询次数由 Worker 的搜索强度限制。 */
    private static final int MAX_ITEMS = 5;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_TEXT_CHARS = 12_000;
    private static volatile Field keyDownBuffer;

    private LegacyRuntimeItemContextReader() {
    }

    public static JsonObject read(JsonObject request) {
        String requestId = value(request, "request_id");
        JsonObject response = base(requestId);
        response.addProperty("request_kind", "item_tooltip");
        JsonArray contexts = new JsonArray();
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean worldReady = minecraft != null
                && minecraft.world != null
                && minecraft.player != null
                && minecraft.getConnection() != null;
        response.addProperty("world_ready", worldReady);
        if (worldReady) {
            Set<String> itemIds = requestedIds(request);
            for (String itemId : itemIds) {
                if (contexts.size() >= MAX_ITEMS) {
                    break;
                }
                JsonObject context = capture(minecraft, itemId, value(request, "language"));
                if (context != null) {
                    contexts.add(context);
                }
            }
        }
        response.add("runtime_item_context", contexts);
        response.addProperty("captured_at", System.currentTimeMillis());
        if (utf8Size(response) > MAX_RESPONSE_BYTES) {
            response.addProperty("truncated", true);
            response.add("runtime_item_context", new JsonArray());
        } else {
            response.addProperty("truncated", false);
        }
        return response;
    }

    public static JsonObject unavailable(String requestId, String message) {
        JsonObject response = base(requestId);
        response.addProperty("request_kind", "item_tooltip");
        response.addProperty("world_ready", false);
        response.add("runtime_item_context", new JsonArray());
        response.addProperty("message", message == null ? "" : message);
        return response;
    }

    private static JsonObject capture(Minecraft minecraft, String rawId, String language) {
        try {
            LegacyItemIdentity identity = LegacyItemIdentity.parse(rawId);
            Item item = Item.getByNameOrId(identity.getItemId());
            if (item == null) {
                return null;
            }
            int metadata = identity.getMetadata() == LegacyItemIdentity.UNSPECIFIED_METADATA
                    ? 0 : identity.getMetadata();
            ItemStack stack = new ItemStack(item, 1, metadata);
            List<String> normal = tooltip(minecraft, stack, 0);
            if (normal.isEmpty()) {
                return null;
            }
            String displayName = first(normal, stack.getDisplayName());
            List<String> rendered = renderedTooltip(identity);
            String markdown = mergeTooltips(minecraft, stack, normal, rendered, displayName);
            JsonObject result = new JsonObject();
            result.addProperty("item_id", identity.canonicalKey());
            result.addProperty("language", language == null || language.trim().isEmpty()
                    ? "neutral" : language.trim().toLowerCase(Locale.ROOT));
            result.addProperty("display_name", displayName);
            result.addProperty("tooltip_markdown", markdown);
            result.addProperty("world_ready", true);
            result.addProperty("captured_at", System.currentTimeMillis());
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mergeTooltips(Minecraft minecraft, ItemStack stack,
            List<String> normal, List<String> rendered, String displayName) {
        StringBuilder markdown = new StringBuilder();
        Set<String> seen = new LinkedHashSet<String>();
        appendLines(markdown, seen, "", normal, displayName);
        // RenderTooltipEvent.PostText 可能包含 Jade、JEI 或物品自身根据当前
        // 世界状态追加的行；它只来自客户端内存，不进入静态目录或会话。
        appendLines(markdown, seen, "", rendered, displayName);
        appendLines(markdown, seen, "Shift", tooltip(minecraft, stack, 1), displayName);
        appendLines(markdown, seen, "Ctrl/Cmd", tooltip(minecraft, stack, 2), displayName);
        appendLines(markdown, seen, "Shift+Ctrl/Cmd", tooltip(minecraft, stack, 3), displayName);
        return markdown.toString();
    }

    private static List<String> renderedTooltip(LegacyItemIdentity identity) {
        LegacyTargetStore.Target target = LegacyTargetStore.get().current();
        if (target == null || target.getRenderedTooltipLines().isEmpty()) {
            return new ArrayList<String>();
        }
        try {
            LegacyItemIdentity targetIdentity = LegacyItemIdentity.parse(target.getItemId());
            if (!identity.getItemId().equals(targetIdentity.getItemId())) {
                return new ArrayList<String>();
            }
            if (identity.getMetadata() != LegacyItemIdentity.UNSPECIFIED_METADATA
                    && targetIdentity.getMetadata() != identity.getMetadata()) {
                return new ArrayList<String>();
            }
            return target.getRenderedTooltipLines();
        } catch (RuntimeException ignored) {
            return new ArrayList<String>();
        }
    }

    private static void appendLines(StringBuilder target, Set<String> seen, String mode,
            List<String> lines, String displayName) {
        if (lines == null) {
            return;
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line == null) {
                continue;
            }
            line = line.trim();
            if (line.isEmpty() || index == 0 || line.equals(displayName)) {
                continue;
            }
            String value = mode.isEmpty() ? line : mode + "：" + line;
            if (seen.add(value)) {
                if (target.length() > 0) {
                    target.append('\n');
                }
                target.append("- ").append(limit(value, MAX_TEXT_CHARS));
                if (target.length() >= MAX_TEXT_CHARS) {
                    return;
                }
            }
        }
    }

    private static List<String> tooltip(Minecraft minecraft, ItemStack stack, int mode) {
        KeyboardSnapshot snapshot = KeyboardSnapshot.capture();
        try {
            if (snapshot != null) {
                snapshot.apply(mode);
            }
            return new ArrayList<String>(stack.getTooltip(
                    minecraft.player, ITooltipFlag.TooltipFlags.NORMAL));
        } catch (Throwable ignored) {
            return new ArrayList<String>();
        } finally {
            if (snapshot != null) {
                snapshot.restore();
            }
        }
    }

    private static Set<String> requestedIds(JsonObject request) {
        Set<String> result = new LinkedHashSet<String>();
        JsonElement values = request == null ? null : request.get("item_ids");
        if (values == null || !values.isJsonArray()) {
            return result;
        }
        for (JsonElement value : values.getAsJsonArray()) {
            if (!value.isJsonPrimitive()) {
                continue;
            }
            String itemId = value.getAsString().trim();
            if (!itemId.isEmpty()) {
                result.add(itemId);
            }
            if (result.size() >= MAX_ITEMS) {
                break;
            }
        }
        return result;
    }

    private static JsonObject base(String requestId) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", 1);
        result.addProperty("type", "runtime_context_response");
        result.addProperty("request_id", requestId == null ? "" : requestId);
        result.addProperty("conversation_id", "");
        return result;
    }

    private static String first(List<String> values, String fallback) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String value(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int utf8Size(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static Field keyDownBuffer() {
        Field cached = keyDownBuffer;
        if (cached != null) {
            return cached;
        }
        try {
            Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
            field.setAccessible(true);
            keyDownBuffer = field;
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class KeyboardSnapshot {
        private final ByteBuffer buffer;
        private final byte[] bytes;
        private final int position;

        private KeyboardSnapshot(ByteBuffer buffer, byte[] bytes, int position) {
            this.buffer = buffer;
            this.bytes = bytes;
            this.position = position;
        }

        private static KeyboardSnapshot capture() {
            synchronized (KEYBOARD_LOCK) {
                try {
                    Field field = keyDownBuffer();
                    if (field == null) {
                        return null;
                    }
                    ByteBuffer buffer = (ByteBuffer) field.get(null);
                    if (buffer == null) {
                        return null;
                    }
                    byte[] bytes = new byte[buffer.capacity()];
                    int position = buffer.position();
                    buffer.position(0);
                    buffer.get(bytes);
                    buffer.position(position);
                    return new KeyboardSnapshot(buffer, bytes, position);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        private void apply(int mode) {
            synchronized (KEYBOARD_LOCK) {
                set(Keyboard.KEY_LSHIFT, mode == 1 || mode == 3);
                set(Keyboard.KEY_RSHIFT, mode == 1 || mode == 3);
                set(Keyboard.KEY_LCONTROL, mode == 2 || mode == 3);
                set(Keyboard.KEY_RCONTROL, mode == 2 || mode == 3);
                set(Keyboard.KEY_LMETA, mode == 2 || mode == 3);
                set(Keyboard.KEY_RMETA, mode == 2 || mode == 3);
            }
        }

        private void set(int key, boolean pressed) {
            if (key >= 0 && key < buffer.capacity()) {
                buffer.put(key, (byte) (pressed ? 1 : 0));
            }
        }

        private void restore() {
            synchronized (KEYBOARD_LOCK) {
                try {
                    buffer.position(0);
                    buffer.put(bytes);
                    buffer.position(position);
                } catch (Throwable ignored) {
                    // 键盘状态恢复失败时不影响当前回答；后续读取仍会重新快照。
                }
            }
        }
    }
}
