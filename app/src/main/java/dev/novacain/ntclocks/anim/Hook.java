package dev.novacain.ntclocks.anim;

import java.lang.reflect.Member;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Обёртки над hookAllMethods/hookAllConstructors, которые не молчат. hookAllMethods берёт только методы, объявленные
 * в самом классе: если метод унаследован (Hotseat.dispatchDraw живёт в CellLayout) или в этой версии его нет,
 * хук тихо не встаёт — такое пишем в журнал.
 */
final class Hook {
    private Hook() {
    }

    /** true — каждый хук пишет срез «nt:Класс.метод» в atrace (замер своей нагрузки в Perfetto). */
    static final boolean TRACE = false;

    static Set<XC_MethodHook.Unhook> all(Class<?> c, String method, XC_MethodHook h) {
        if (TRACE) h = traced(c.getSimpleName() + "." + method, h);
        Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(c, method, h);
        if (s.isEmpty()) XposedBridge.log("ntweaker: хук не встал — " + c.getName() + "." + method + " (нет в классе)");
        return s;
    }

    static Set<XC_MethodHook.Unhook> ctors(Class<?> c, XC_MethodHook h) {
        if (TRACE) h = traced(c.getSimpleName() + ".<init>", h);
        Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllConstructors(c, h);
        if (s.isEmpty()) XposedBridge.log("ntweaker: хук не встал — " + c.getName() + ".<init>");
        return s;
    }

    private static java.lang.reflect.Method before, after;

    private static XC_MethodHook traced(String name, XC_MethodHook h) {
        try {
            if (before == null) {
                before = XC_MethodHook.class.getDeclaredMethod("beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
                after = XC_MethodHook.class.getDeclaredMethod("afterHookedMethod", XC_MethodHook.MethodHookParam.class);
                before.setAccessible(true);
                after.setAccessible(true);
            }
        } catch (Throwable t) {
            return h;
        }
        String tag = ("nt:" + name).length() > 120 ? ("nt:" + name).substring(0, 120) : "nt:" + name;
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                android.os.Trace.beginSection(tag);
                try {
                    before.invoke(h, p);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    android.os.Trace.endSection();
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                android.os.Trace.beginSection(tag + "↩");
                try {
                    after.invoke(h, p);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    android.os.Trace.endSection();
                }
            }
        };
    }
}
