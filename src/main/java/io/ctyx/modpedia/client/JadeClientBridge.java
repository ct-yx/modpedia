package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;

/**
 * 可选视线识别模组的客户端回调桥。
 *
 * <p>项目不声明该模组的硬依赖，也不把它的 API 打进 ModPedia。首次客户端 tick 时，
 * 在识别模组已经完成初始化后，通过反射注册其公开 tooltip 收集回调。回调参数的第一
 * 个对象在不同小版本中有过变化，第二个参数始终是 Accessor，因此桥接只依赖方法名和
 * Accessor 的公开 {@code getPickedResult()} 入口。</p>
 */
public final class JadeClientBridge {
    private static final String MOD_ID = "jade";
    private static final String REGISTRATION_CLASS = "snownee.jade.impl.WailaClientRegistration";
    private static final String CALLBACK_CLASS = "snownee.jade.api.callback.JadeTooltipCollectedCallback";

    private static int retryTicks;
    private static boolean registered;
    private static boolean warned;

    private JadeClientBridge() {
    }

    /** 在客户端 tick 中调用；缺失或 API 变化时只跳过联动，不影响助手启动。 */
    public static void tick() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            JadeTargetStore.clear();
            return;
        }
        if (registered || retryTicks-- > 0) {
            return;
        }
        retryTicks = 40;
        try {
            registerTooltipCallback();
            registered = true;
            ModPedia.LOGGER.info("可选视线识别联动已接入");
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                ModPedia.LOGGER.warn(
                        "可选视线识别联动初始化失败，已隐藏目标插入按钮：{}",
                        failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage()
                );
            }
        }
    }

    /** Jade 已成功注册回调后，目标存储切换到高级识别来源。 */
    static boolean isRegistered() {
        return registered;
    }

    private static void registerTooltipCallback() throws ReflectiveOperationException {
        Class<?> registrationType = Class.forName(REGISTRATION_CLASS);
        Class<?> callbackType = Class.forName(CALLBACK_CLASS);
        Object registration = invokeStaticNoArg(registrationType, "instance");
        if (registration == null) {
            throw new IllegalStateException("识别模组客户端注册器尚未就绪");
        }

        Method addCallback = findCallbackMethod(registrationType, callbackType);
        if (addCallback == null) {
            throw new NoSuchMethodException("addTooltipCollectedCallback");
        }

        InvocationHandler handler = new TooltipCallbackHandler();
        ClassLoader loader = callbackType.getClassLoader();
        if (loader == null) {
            loader = JadeClientBridge.class.getClassLoader();
        }
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, handler);
        addCallback.setAccessible(true);
        addCallback.invoke(registration, 0, callback);
    }

    private static Object invokeStaticNoArg(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 0
                        && Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    return method.invoke(null);
                }
            }
        }
        return null;
    }

    private static Method findCallbackMethod(Class<?> type, Class<?> callbackType) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("addTooltipCollectedCallback")
                        || method.getParameterCount() != 2
                        || !method.getParameterTypes()[0].equals(int.class)
                        || !method.getParameterTypes()[1].isAssignableFrom(callbackType)) {
                    continue;
                }
                return method;
            }
        }
        return null;
    }

    private static final class TooltipCallbackHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("onTooltipCollected") && args != null && args.length >= 2) {
                try {
                    JadeTargetStore.updateFromJadeAccessor(args[1]);
                } catch (Throwable ignored) {
                    JadeTargetStore.clear();
                }
                return null;
            }
            if (method.getName().equals("toString")) {
                return "ModPediaTooltipCollectedCallback";
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == (args == null ? null : args[0]);
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            return null;
        }
    }
}
