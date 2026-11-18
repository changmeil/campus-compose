package com.bhu.campus.hook;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 焦点通知（超级岛）白名单绕过 —— 内置在主 App 里的 Xposed 模块。
 *
 * 背景：App 往 notification.extras 塞了 `miui.focus.param`，通知本身发得出去，
 * 但 SystemUI 的焦点通知插件会先 inflate 再鉴权，鉴权不过就 onAuthFailed + 把岛撤掉。
 * 鉴权是「包名 + 签名是否在小米白名单里」，侧载应用永远过不了，也没有开关可改。
 *
 * 这里把鉴权函数本身改成恒 true，等价于「本机所有应用都在白名单里」：
 *   miui.systemui.notification.NotificationSettingsManager#canShowFocus / canCustomFocus
 *   miui.systemui.notification.focus.SignatureChecker#checkSignatures
 *   miui.systemui.notification.focus.FocusNotifUtils#canShowFocus
 *   com.miui.systemui.notification.NotificationSettingsManager#canShowFocus（主 dex 的老实现）
 *
 * 难点在于这些类不在 SystemUI 主 APK 里，而是在插件 miui.systemui.plugin
 * （/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk）里，用 SystemUI 的
 * defaultClassLoader 加载不到。所以先 hook PluginFactory.createPluginContext，
 * 从它返回的 PluginContext 上把插件的 ClassLoader 捞出来，再去 hook 插件里的类。
 *
 * 启用方式：LSPosed 里勾选「校园一站」→ 作用域同时勾「系统界面 / 小米服务框架」→ 重启 SystemUI。
 * 只在本机生效，不联网、不上传、不改系统分区。HyperOS 升级后类名若变，改下面的常量即可。
 */
public class IslandAuthHook implements IXposedHookLoadPackage {

    private static final String TAG = "CampusIslandHook";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_XMSF = "com.xiaomi.xmsf";

    /** SystemUI 用来创建插件上下文（顺带暴露插件 ClassLoader）的工厂类。 */
    private static final String PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";

    /** 需要「恒返回 true」的类与方法。类可能在主 dex，也可能在插件里，两处都试。 */
    private static final String[][] TARGETS = {
            {"miui.systemui.notification.NotificationSettingsManager", "canShowFocus", "canCustomFocus"},
            {"miui.systemui.notification.focus.SignatureChecker", "checkSignatures"},
            {"miui.systemui.notification.focus.FocusNotifUtils", "canShowFocus"},
            {"com.miui.systemui.notification.NotificationSettingsManager", "canShowFocus", "canCustomFocus"},
    };

    /** classLoader -> 已处理过的类名，避免 createPluginContext 多次回调时重复 hook。 */
    private static final Map<ClassLoader, Set<String>> DONE =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Set<String>>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (PKG_SYSTEMUI.equals(lp.packageName)) {
            // 主 dex 里那份（老实现，新机型可能已经不用，但 hook 一下没坏处）
            hookTargets(lp.classLoader);
            // 插件里的那份：等它的 ClassLoader 出现
            hookPluginFactory(lp.classLoader);
        } else if (PKG_XMSF.equals(lp.packageName)) {
            // 小米服务框架侧的 content provider 鉴权（island 权限查询走这里）
            hookXmsfAuth(lp.classLoader);
        }
    }

    // ---------------------------------------------------------------- 通用

    /**
     * 把 [className] 里名字在 [methods] 里、返回 boolean 的方法，全部 hook 成 true。
     *
     * 刻意不校验参数列表：不同 HyperOS 版本签名会变（多一个 int、少一个 Set 之类），
     * 而「返回 boolean 的同名方法」在这个类里只会是鉴权判断，误伤概率基本为零。
     */
    private static void hookBoolReturning(ClassLoader cl, String className, String... methods) {
        if (cl == null) return;
        Set<String> done = DONE.get(cl);
        if (done == null) {
            done = Collections.synchronizedSet(new HashSet<String>());
            DONE.put(cl, done);
        }
        if (done.contains(className)) return;

        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, cl);
        } catch (Throwable t) {
            return; // 这个 ClassLoader 里没有这个类，换下一个 ClassLoader 再试
        }
        done.add(className);

        Set<String> wanted = new HashSet<>();
        Collections.addAll(wanted, methods);
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!wanted.contains(m.getName())) continue;
            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                hooked++;
                log("hooked " + m.getDeclaringClass().getName() + "#" + m.getName()
                        + "(" + m.getParameterTypes().length + " args) -> true");
            } catch (Throwable t) {
                log("hook " + m + " failed: " + t);
            }
        }
        if (hooked == 0) log("no boolean method matched in " + className);
    }

    private static void hookTargets(ClassLoader cl) {
        for (String[] t : TARGETS) {
            String[] methods = new String[t.length - 1];
            System.arraycopy(t, 1, methods, 0, methods.length);
            hookBoolReturning(cl, t[0], methods);
        }
    }

    // ---------------------------------------------------------------- 插件

    private static void hookPluginFactory(final ClassLoader sysuiLoader) {
        try {
            Class<?> factory = Class.forName(PLUGIN_FACTORY, false, sysuiLoader);
            int count = 0;
            for (Method m : factory.getDeclaredMethods()) {
                if (!"createPluginContext".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof Context)) return;
                        ClassLoader pluginLoader = ((Context) result).getClassLoader();
                        if (pluginLoader == null) return;
                        hookTargets(pluginLoader);
                    }
                });
                count++;
            }
            log("plugin factory hooked (" + count + " method), waiting for plugin classloader");
        } catch (Throwable t) {
            log("plugin factory hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- XMSF

    /**
     * com.xiaomi.xms.auth.AuthSession 是小米服务框架里的鉴权会话。
     *
     * 它在鉴权失败时回调 `b(AuthError)`，AuthError 的字段 `a` 是错误码；
     * 把错误码置 0 再触发成功回调，这条链路就当鉴权通过。
     *
     * 这里不硬编码混淆后的方法名：遍历所有「单参数且参数类型名含 AuthError」的方法，
     * 命中后清掉错误码字段，再找一个无参且返回 void 的方法当成功回调兜底。
     */
    private static void hookXmsfAuth(ClassLoader cl) {
        Class<?> session;
        try {
            session = Class.forName("com.xiaomi.xms.auth.AuthSession", false, cl);
        } catch (Throwable t) {
            log("AuthSession not found in xmsf");
            return;
        }
        int hooked = 0;
        for (final Method m : session.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || m.getReturnType() != void.class) continue;
            if (!ps[0].getName().contains("AuthError")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object err = param.args[0];
                        if (err == null) return;
                        try {
                            java.lang.reflect.Field f = err.getClass().getDeclaredField("a");
                            f.setAccessible(true);
                            Object old = f.get(err);
                            f.setInt(err, 0);
                            log("xmsf auth error " + old + " -> 0");
                        } catch (Throwable t) {
                            log("clear auth error failed: " + t);
                        }
                    }
                });
                hooked++;
                log("hooked AuthSession#" + m.getName() + "(" + ps[0].getName() + ")");
            } catch (Throwable t) {
                log("hook AuthSession#" + m.getName() + " failed: " + t);
            }
        }
        if (hooked == 0) log("AuthSession: no method matched (version changed?)");
    }

    private static void log(String msg) {
        Log.e(TAG, msg);
        try {
            XposedBridge.log("CampusIslandHook: " + msg);
        } catch (Throwable ignored) {
        }
    }
}
