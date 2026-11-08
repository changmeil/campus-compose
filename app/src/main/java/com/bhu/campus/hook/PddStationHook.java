package com.bhu.campus.hook;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 拼多多驿站页直通 —— 内置在主 App 里的 Xposed 模块。
 *
 * 为什么需要它：驿站取件页 `mdkd.pinduoduo.com/weixin/package?station_code=xxx`
 * 被拼多多放在非导出的 NewPageActivity 里，外部只能通过
 * `pinduoduo://com.xunmeng.pinduoduo/campusprobe?code=xxx` 触发，而拼多多的 scheme
 * 路由会把域名固定成 m.pinduoduo.net，落地就是 404。
 *
 * 做法：在 NewPageActivity.onCreate 之前，把 Intent extras 里
 * ForwardProps.url 换成真实驿站地址（连嵌套 JSON 里转义过的那份一起换）。
 *
 * 启用方式：LSPosed 里勾选「校园一站」→ 作用域勾「拼多多」→ 强停拼多多。
 * 这个类只在拼多多进程里被加载，主 App 进程不受影响。
 */
public class PddStationHook implements IXposedHookLoadPackage {

    private static final String TAG = "CampusHook";
    private static final String PKG = "com.xunmeng.pinduoduo";
    private static final String ACT = "com.xunmeng.pinduoduo.activity.NewPageActivity";

    /** 哨兵路径：主 App 用这个路径来触发改写。 */
    private static final String MARKER = "/campusprobe";
    private static final String REAL_URL = "https://mdkd.pinduoduo.com/weixin/package?station_code=";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(ACT, lp.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Intent it = (Intent) XposedHelpers.callMethod(p.thisObject, "getIntent");
                        rewrite(it);
                    } catch (Throwable t) {
                        log("rewrite failed: " + t);
                    }
                }
            });
            log("hooked NewPageActivity on proc=" + lp.processName);
        } catch (Throwable t) {
            log("hook failed: " + t);
        }
    }

    private static void rewrite(Intent it) {
        if (it == null) return;
        Bundle b;
        try {
            b = it.getExtras();
        } catch (Throwable ignored) {
            return;
        }
        if (b == null) return;

        Object props;
        try {
            props = b.get("props");
        } catch (Throwable ignored) {
            return;
        }
        if (props == null) return;

        String oldUrl = (String) getField(props, "url");
        if (oldUrl == null || !oldUrl.contains(MARKER)) return;

        String code = queryParam(oldUrl, "code");
        if (code == null || code.isEmpty()) {
            log("marker hit but no code");
            return;
        }

        String newUrl = REAL_URL + code;
        setField(props, "url", newUrl);

        // 嵌套的 props 是一段 JSON 字符串，里面的 url 也要一起换（/ 会被转义成 \/）
        String json = (String) getField(props, "props");
        if (json != null) {
            String replaced = json
                    .replace(escape(oldUrl), escape(newUrl))
                    .replace(oldUrl, newUrl);
            setField(props, "props", replaced);
        }
        log("station " + code + " -> " + newUrl);
    }

    private static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String kv : url.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("/", "\\/");
    }

    private static Object getField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return;
        try {
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            log("set " + name + " failed: " + t);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void log(String msg) {
        Log.e(TAG, msg);
        try {
            XposedBridge.log("CampusHook: " + msg);
        } catch (Throwable ignored) {
        }
    }
}
