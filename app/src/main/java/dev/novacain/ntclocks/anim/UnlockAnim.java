package dev.novacain.ntclocks.anim;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Разблокировка по-iOS: часы (с датой) и виджеты под ними уезжают вверх, чуть уменьшаются и растворяются — и при
 * свайпе к вводу PIN (за пальцем), и при разблокировке отпечатком. Источник прогресса — каждый шаг перехода экрана
 * блокировки (KeyguardTransitionRepositoryImpl.emitTransition: откуда, куда, 0…1, стадия). Двигаем через
 * setAnimationMatrix и setTransitionAlpha: эти свойства система не трогает, с анимациями Nothing (AOD, сдвиг от
 * выгорания, прозрачность) не спорим. Settings.Global ntweaker_unlock {enabled, distance}.
 */
public final class UnlockAnim {
    private UnlockAnim() {
    }

    public static final String KEY = "ntweaker_unlock";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<View> clockRef = new WeakReference<>(null), widgetsRef = new WeakReference<>(null);
    private static volatile boolean enabled = true;
    private static volatile float distance = 0.22f;      // на сколько уезжают — доля высоты экрана
    private static boolean listening, moved;
    private static final Matrix M = new Matrix();
    private static final boolean DEBUG = false;          // true — шаги переходов и панель в лог

    public static void hook(ClassLoader cl) {
        Class<?> repo = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.data.repository.KeyguardTransitionRepositoryImpl", cl);
        if (repo == null) {
            XposedBridge.log("ntweaker: разблокировка — переходы не найдены");
            return;
        }
        Hook.all(repo, "emitTransition", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    step(p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: разблокировка — " + t);
                }
            }
        });
        Class<?> sm = XposedHelpers.findClassIfExists("com.android.systemui.shade.ShadeExpansionStateManager", cl);
        if (sm != null) Hook.all(sm, "onPanelExpansionChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (DEBUG) XposedBridge.log("ntweaker: разблокировка — панель " + p.args[0] + " " + p.args[1] + " " + p.args[2]);
                // свайп вверх по экрану блокировки: панель сворачивается за пальцем (1 → ~0.55), потом пружинит назад.
                // Ввод PIN у Nothing накрывает экран уже на первой трети пути — часы должны успеть уехать к 0.78
                if (!onLockscreen) return;
                float fraction = (Float) p.args[0];
                panelP = Math.max(0f, Math.min(1f, (1f - fraction) / 0.22f));
                MAIN.post(UnlockAnim::update);
            }
        });
        // часы и контейнер виджетов — запоминаем, когда SystemUI их создаёт
        Class<?> widgets = XposedHelpers.findClassIfExists("com.nothing.systemui.widget.NTWidgetContainer", cl);
        if (widgets != null) Hook.all(widgets, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                widgetsRef = new WeakReference<>((View) p.thisObject);
                clockRef = new WeakReference<>(null);
            }
        });
        XposedBridge.log("ntweaker: разблокировка по-iOS подключена");
    }

    private static void prefs() {
        if (listening) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        listening = true;
        ContentResolver cr = app.getContentResolver();
        Runnable read = () -> {
            try {
                String s = Settings.Global.getString(cr, KEY);
                JSONObject o = new JSONObject(s != null ? s : "{}");
                enabled = o.optBoolean("enabled", true);
                distance = (float) Math.max(0.05, Math.min(0.5, o.optDouble("distance", 0.22)));
            } catch (Throwable ignored) {
            }
        };
        read.run();
        cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, new ContentObserver(MAIN) {
            @Override
            public void onChange(boolean selfChange) {
                read.run();
            }
        });
    }

    /** На экране блокировки (не AOD, не убран): только тогда свайп панели — это разблокировка, а не шторка. */
    private static volatile boolean onLockscreen;
    private static volatile float panelP, transP;

    private static void step(Object s) {
        prefs();
        String from = String.valueOf(XposedHelpers.callMethod(s, "getFrom"));
        String to = String.valueOf(XposedHelpers.callMethod(s, "getTo"));
        float v = (Float) XposedHelpers.callMethod(s, "getValue");
        String state = String.valueOf(XposedHelpers.callMethod(s, "getTransitionState"));
        if (DEBUG && !state.equals("RUNNING")) XposedBridge.log("ntweaker: разблокировка — шаг " + from + "→" + to + " " + v + " " + state);
        if (to.equals("LOCKSCREEN") && !from.endsWith("BOUNCER")) {
            // экран блокировки снова показывается (пробуждение, блокировка) — часы на место
            onLockscreen = true;
            panelP = 0f;
            transP = 0f;
        } else if (from.equals("LOCKSCREEN") && to.equals("GONE")) {
            // разблокировка без PIN-экрана (отпечаток): уезжают вместе с переходом
            transP = state.equals("CANCELED") ? 0f : v;
            if (state.equals("FINISHED")) onLockscreen = false;
        } else if (from.equals("LOCKSCREEN") && (to.equals("AOD") || to.equals("DOZING") || to.equals("OCCLUDED"))) {
            onLockscreen = false;
            panelP = 0f;
            transP = 0f;
        } else if (to.equals("GONE")) {
            onLockscreen = false;                               // PIN введён: экран блокировки уже скрыт
        }
        MAIN.post(UnlockAnim::update);
    }

    private static void update() {
        float f = Math.max(panelP, transP);
        View clock = clock();
        View widgets = widgetsRef.get();
        if (f <= 0f || !enabled) {
            if (moved) { reset(clock); reset(widgets); moved = false; }
            return;
        }
        moved = true;
        float e = 1f - (float) Math.pow(1f - Math.min(1f, f), 3);   // плавное начало — как у iOS
        float alpha = Math.max(0f, 1f - f * 1.35f);
        transform(clock, e, alpha, 1f);
        transform(widgets, e, alpha, 0.85f);                         // виджеты отстают — глубина
    }

    private static void transform(View v, float e, float alpha, float lag) {
        if (v == null) return;
        float h = v.getResources().getDisplayMetrics().heightPixels;
        M.reset();
        if (AnimHooks.prefs().coloros17Motion()) {
            // ColorOS 17: часы не улетают — остаются на месте, чуть подрастают и растворяются, пока стол опускается
            float scale = 1f + 0.04f * e;
            M.postScale(scale, scale, v.getWidth() / 2f, v.getHeight() / 2f);
        } else {
            float scale = 1f - 0.07f * e;
            M.postScale(scale, scale, v.getWidth() / 2f, 0f);
            M.postTranslate(0f, -h * distance * e * lag);
        }
        v.setAnimationMatrix(M);
        v.setTransitionAlpha(alpha);
    }

    private static void reset(View v) {
        if (v == null) return;
        v.setAnimationMatrix(null);
        v.setTransitionAlpha(1f);
    }

    private static View clock() {
        View c = clockRef.get();
        if (c != null && c.isAttachedToWindow()) return c;
        View w = widgetsRef.get();
        if (w == null) return null;
        View root = w.getRootView();
        int id = root.getResources().getIdentifier("lockscreen_clock_view", "id", root.getContext().getPackageName());
        c = id != 0 ? root.findViewById(id) : null;
        clockRef = new WeakReference<>(c);
        return c;
    }
}
