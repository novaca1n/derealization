package dev.novacain.ntclocks.anim;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Полоска жестов (SystemUI NavigationHandle): цвет, длина, толщина, непрозрачность. Цвет у системы плавно идёт от
 * светлого (на тёмном фоне) к тёмному (на светлом) по darkIntensity — так же делаем и мы, только тонами палитры:
 * 200 на тёмном, 700 на светлом. Настройки меняются на лету.
 */
public final class NavHooks {
    private static volatile NavPrefs prefs;
    private static final List<WeakReference<View>> HANDLES = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NavHooks() {
    }

    static NavPrefs prefs() {
        NavPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new NavPrefs();
        synchronized (NavHooks.class) {
            if (prefs == null) {
                prefs = NavPrefs.read(app.getContentResolver());
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(NavPrefs.KEY), false,
                        new ContentObserver(MAIN) {
                            @Override
                            public void onChange(boolean selfChange) {
                                prefs = NavPrefs.read(app.getContentResolver());
                                refreshAll();
                            }
                        });
            }
            return prefs;
        }
    }

    private static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static void hook(ClassLoader cl) {
        Class<?> h = XposedHelpers.findClassIfExists("com.android.systemui.navigationbar.gestural.NavigationHandle", cl);
        if (h == null) return;
        Hook.ctors(h, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                synchronized (HANDLES) {
                    HANDLES.add(new WeakReference<>(v));
                }
                XposedHelpers.setAdditionalInstanceField(v, "ntRadius", XposedHelpers.getFloatField(v, "mRadius"));
                v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        refresh(view);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                    }
                });
            }
        });
        Hook.all(h, "setDarkIntensity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                float dark = (Float) p.args[0];
                XposedHelpers.setAdditionalInstanceField(v, "ntDark", dark);
                NavPrefs n = prefs();
                if (!n.enabled || "system".equals(n.color)) return;
                Paint paint = (Paint) XposedHelpers.getObjectField(v, "mPaint");
                int c = color(v.getContext(), n.color, dark);
                if (paint.getColor() != c) {
                    paint.setColor(c);
                    v.invalidate();
                }
                p.setResult(null);
            }
        });
        XposedBridge.log("derealization: gesture bar hooked");
    }

    /** Тон палитры: 200 на тёмном фоне (dark = 0), 700 на светлом (dark = 1). */
    private static final android.animation.ArgbEvaluator EVAL = new android.animation.ArgbEvaluator();

    private static int color(Context c, String pal, float dark) {
        // вызывается в каждом кадре переходов (setDarkIntensity) — без поиска ресурсов по имени и новых объектов
        int light, deep;
        if ("a2".equals(pal)) { light = c.getColor(android.R.color.system_accent2_200); deep = c.getColor(android.R.color.system_accent2_700); }
        else if ("a3".equals(pal)) { light = c.getColor(android.R.color.system_accent3_200); deep = c.getColor(android.R.color.system_accent3_700); }
        else { light = c.getColor(android.R.color.system_accent1_200); deep = c.getColor(android.R.color.system_accent1_700); }
        return (Integer) EVAL.evaluate(Math.max(0f, Math.min(1f, dark)), light, deep);   // один вычислитель на всё время
    }

    // ───────── лаунчер: на этом телефоне полоску рисует панель задач лаунчера (окно «Taskbar») ─────────

    private static final List<WeakReference<Object>> CONTROLLERS = new ArrayList<>();

    public static void launcher(ClassLoader cl) {
        Class<?> ctl = XposedHelpers.findClassIfExists("com.android.launcher3.taskbar.StashedHandleViewController", cl);
        if (ctl == null) return;
        Hook.all(ctl, "init", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object c = p.thisObject;
                View v = (View) XposedHelpers.getObjectField(c, "mStashedHandleView");
                XposedHelpers.setAdditionalInstanceField(c, "ntW", XposedHelpers.getIntField(c, "mStashedHandleWidth"));
                XposedHelpers.setAdditionalInstanceField(c, "ntH", XposedHelpers.getIntField(c, "mStashedHandleHeight"));
                XposedHelpers.setAdditionalInstanceField(v, "ntLight", XposedHelpers.getIntField(v, "mStashedHandleLightColor"));
                XposedHelpers.setAdditionalInstanceField(v, "ntDarkC", XposedHelpers.getIntField(v, "mStashedHandleDarkColor"));
                synchronized (CONTROLLERS) {
                    CONTROLLERS.add(new WeakReference<>(c));
                }
                prefs();
                applyLauncher(c);
            }
        });
        XposedBridge.log("derealization: gesture bar (launcher taskbar) hooked");
    }

    /** Цвет с непрозрачностью из настроек. */
    private static int withOpacity(int c, float opacity) {
        return Color.argb(Math.round(Color.alpha(c) * opacity), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static void applyLauncher(Object c) {
        try {
            NavPrefs n = prefs();
            View v = (View) XposedHelpers.getObjectField(c, "mStashedHandleView");
            Context ctx = v.getContext();
            int w0 = (Integer) XposedHelpers.getAdditionalInstanceField(c, "ntW");
            int h0 = (Integer) XposedHelpers.getAdditionalInstanceField(c, "ntH");
            XposedHelpers.setIntField(c, "mStashedHandleWidth", n.enabled ? Math.round(dp(ctx, n.width)) : w0);
            XposedHelpers.setIntField(c, "mStashedHandleHeight", n.enabled ? Math.round(dp(ctx, n.thickness)) : h0);
            int light = (Integer) XposedHelpers.getAdditionalInstanceField(v, "ntLight");
            int deep = (Integer) XposedHelpers.getAdditionalInstanceField(v, "ntDarkC");
            if (n.enabled && !"system".equals(n.color)) {
                light = color(ctx, n.color, 0f);
                deep = color(ctx, n.color, 1f);
            }
            if (n.enabled) {
                light = withOpacity(light, n.opacity);
                deep = withOpacity(deep, n.opacity);
            }
            XposedHelpers.setIntField(v, "mStashedHandleLightColor", light);
            XposedHelpers.setIntField(v, "mStashedHandleDarkColor", deep);
            Object dark = XposedHelpers.getObjectField(v, "mIsRegionDark");
            if (dark instanceof Boolean) v.setBackgroundColor((Boolean) dark ? light : deep);
            v.invalidateOutline();
            v.invalidate();
        } catch (Throwable t) {
            XposedBridge.log("derealization: gesture bar (launcher) — " + t);
        }
    }

    private static void refreshAll() {
        synchronized (CONTROLLERS) {
            for (WeakReference<Object> r : CONTROLLERS) {
                Object c = r.get();
                if (c != null) MAIN.post(() -> applyLauncher(c));
            }
        }
        synchronized (HANDLES) {
            for (WeakReference<View> r : HANDLES) {
                View v = r.get();
                if (v != null) v.post(() -> refresh(v));
            }
        }
    }

    private static void refresh(View v) {
        try {
            NavPrefs n = prefs();
            Context c = v.getContext();
            Float base = (Float) XposedHelpers.getAdditionalInstanceField(v, "ntRadius");
            float radius = n.enabled ? dp(c, n.thickness) / 2f : (base != null ? base : dp(c, 2));
            XposedHelpers.setFloatField(v, "mRadius", radius);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                int w = Math.round(dp(c, n.enabled ? n.width : 132f));
                if (lp.width != w) {
                    lp.width = w;
                    v.setLayoutParams(lp);
                }
            }
            v.setAlpha(n.enabled ? n.opacity : 1f);
            Float dark = (Float) XposedHelpers.getAdditionalInstanceField(v, "ntDark");
            if (dark != null) {
                if (n.enabled && !"system".equals(n.color)) {
                    ((Paint) XposedHelpers.getObjectField(v, "mPaint")).setColor(color(c, n.color, dark));
                } else {
                    int light = XposedHelpers.getIntField(v, "mLightColor"), deep = XposedHelpers.getIntField(v, "mDarkColor");
                    ((Paint) XposedHelpers.getObjectField(v, "mPaint")).setColor((Integer) new android.animation.ArgbEvaluator().evaluate(dark, light, deep));
                }
            }
            v.invalidate();
        } catch (Throwable t) {
            XposedBridge.log("derealization: gesture bar — " + t);
        }
    }
}
