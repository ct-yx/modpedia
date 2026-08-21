package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.SourceReference;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** APP 手册客户端跳转适配器；只通过反射访问可选手册模组。 */
final class AppSourceNavigator {
    private static final List<String> DEFAULT_API_CLASSES = List.of(
            "com.klikli_dev.modonomicon.api.ModonomiconAPI",
            // 1.20.1/1.21.x exposes the actual client navigation here. Keep
            // the other candidates for compatible forks and future releases.
            "com.klikli_dev.modonomicon.client.gui.BookGuiManager",
            "com.klikli_dev.modonomicon.client.ModonomiconClient",
            "com.klikli_dev.modonomicon.client.gui.book.BookScreen"
    );
    private static final Set<String> OPEN_METHODS = Set.of(
            "openBookEntry", "openEntry", "openBook", "open"
    );

    boolean open(SourceReference source) {
        Optional<Target> parsed = target(source);
        if (parsed.isEmpty() || Minecraft.getInstance().player == null) {
            return false;
        }
        Target target = parsed.get();
        for (String className : apiClasses()) {
            try {
                if (invokeApi(Class.forName(className), target)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // APP 版本或运行时入口不同，继续尝试其他公开客户端入口。
            }
        }
        return false;
    }

    static boolean isAppSource(SourceReference source) {
        return target(source).isPresent();
    }

    static Optional<Target> target(SourceReference source) {
        if (source == null) {
            return Optional.empty();
        }
        String path = normalize(source.sourcePath());
        String documentId = source.documentId() == null ? "" : source.documentId();
        if (!path.contains("#book=") && !documentId.contains(":app/")) {
            return Optional.empty();
        }

        String namespace = namespace(path);
        if (namespace.isBlank()) {
            int colon = documentId.indexOf(':');
            namespace = colon > 0 ? documentId.substring(0, colon) : "";
        }
        Map<String, String> fragment = fragment(path);
        String book = firstNonBlank(fragment.get("book"), idPart(documentId, 0));
        String category = firstNonBlank(fragment.get("category"), idPart(documentId, 1));
        String entry = fragment.containsKey("entry")
                ? fragment.get("entry")
                : path.contains("/entries/") ? idPart(documentId, 2) : "";
        if (namespace.isBlank() || book.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Target(
                namespace,
                book,
                category,
                entry,
                path.contains("#") ? path.substring(0, path.indexOf('#')) : path
        ));
    }

    static List<String> entryCandidates(Target target) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (!target.category().isBlank() && !target.entry().isBlank()) {
            candidates.add(target.category() + "/" + target.entry());
        }
        if (!target.entry().isBlank()) {
            candidates.add(target.entry());
        }
        if (!target.book().isBlank() && !target.category().isBlank() && !target.entry().isBlank()) {
            candidates.add(target.book() + "/" + target.category() + "/" + target.entry());
        }
        return List.copyOf(candidates);
    }

    private boolean invokeApi(Class<?> type, Target target) throws ReflectiveOperationException {
        Object singleton = singleton(type);
        for (Method method : type.getMethods()) {
            if (!OPEN_METHODS.contains(method.getName())) {
                continue;
            }
            List<ResourceLocation> entryIds = new ArrayList<>();
            for (String entry : entryCandidates(target)) {
                entryIds.add(ResourceLocation.fromNamespaceAndPath(target.namespace(), entry));
            }
            if (entryIds.isEmpty()) {
                entryIds.add(null);
            }
            for (ResourceLocation entryId : entryIds) {
                Object[] arguments = arguments(method.getParameterTypes(), target, entryId);
                if (arguments == null) {
                    continue;
                }
                try {
                    Object result = method.invoke(Modifier.isStatic(method.getModifiers()) ? null : singleton, arguments);
                    if (!(result instanceof Boolean) || (Boolean) result) {
                        return true;
                    }
                } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException ignored) {
                    // 方法签名匹配但目标页面不存在时继续尝试下一个候选。
                }
            }
        }
        return false;
    }

    private Object[] arguments(Class<?>[] parameterTypes, Target target, ResourceLocation entryId) {
        ResourceLocation bookId = ResourceLocation.fromNamespaceAndPath(target.namespace(), target.book());
        Object[] result = new Object[parameterTypes.length];
        int resourceIndex = 0;
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            if (ResourceLocation.class.isAssignableFrom(type)) {
                ResourceLocation value = resourceIndex++ == 0 ? bookId : entryId;
                if (value == null) {
                    return null;
                }
                result[index] = value;
            } else if (Player.class.isAssignableFrom(type)) {
                result[index] = Minecraft.getInstance().player;
            } else if (Minecraft.class.isAssignableFrom(type)) {
                result[index] = Minecraft.getInstance();
            } else if (type == boolean.class || type == Boolean.class) {
                result[index] = false;
            } else if (type == int.class || type == Integer.class) {
                result[index] = 0;
            } else {
                return null;
            }
        }
        return result;
    }

    private Object singleton(Class<?> type) throws ReflectiveOperationException {
        for (String methodName : List.of("get", "getInstance", "instance")) {
            try {
                Method method = type.getMethod(methodName);
                if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                    return method.invoke(null);
                }
            } catch (NoSuchMethodException ignored) {
                // 尝试下一个常见单例入口。
            }
        }
        try {
            return type.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private List<String> apiClasses() {
        String configured = System.getProperty("modpedia.app.apiClasses", "");
        if (configured.isBlank()) {
            return DEFAULT_API_CLASSES;
        }
        return List.of(configured.split(","));
    }

    private static Map<String, String> fragment(String path) {
        int hash = path.indexOf('#');
        if (hash < 0 || hash + 1 >= path.length()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : path.substring(hash + 1).split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                result.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return result;
    }

    private static String namespace(String path) {
        String[] parts = path.split("/");
        return parts.length >= 2 && ("assets".equals(parts[0]) || "data".equals(parts[0]))
                ? parts[1].toLowerCase(Locale.ROOT)
                : "";
    }

    private static String idPart(String documentId, int index) {
        int marker = documentId.indexOf(":app/");
        if (marker < 0) {
            return "";
        }
        String[] parts = documentId.substring(marker + ":app/".length()).split("/");
        return parts.length > index ? parts[index] : "";
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    record Target(String namespace, String book, String category, String entry, String resourcePath) {
    }
}
