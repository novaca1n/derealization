package dev.novacain.ntclocks.anim;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Живой стол («ColorOS 17», sysMotion): значки текущей страницы и дока откликаются на события.
 *   - волна: окно приложения садится в значок — соседи расходятся от него, как круги по воде, и упруго возвращаются
 *     (ближние раньше и сильнее, дальние позже и слабее);
 *   - каскад: разблокировка — значки «опускаются» на стол ряд за рядом сверху вниз (из 118 %, прозрачными, сверху).
 * Двигаем через setAnimationMatrix и setTransitionAlpha: эти свойства анимации Nothing не трогают.
 * Сигнал разблокировки шлёт SystemUI (Coloros17.unlock) — приём только от владельцев STATUS_BAR.
 */
public final class HomeWave {
    private HomeWave() {
    }

    public static final String ACTION_UNLOCK = "dev.novacain.ntclocks.HOME_UNLOCK";
    private static WeakReference<Activity> launcher = new WeakReference<>(null);
    private static ValueAnimator running;
    private static final List<View> VIEWS = new ArrayList<>();
    private static float[] cx = new float[0], cy = new float[0], delay = new float[0], amp = new float[0], ux = new float[0], uy = new float[0];
    private static final Matrix M = new Matrix();
    private static final int[] LOC = new int[2];

    public static void launcher(ClassLoader cl) {
        Class<?> l = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        if (l == null) return;
        Hook.all(l, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                launcher = new WeakReference<>(a);
                try {
                    a.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            cascade();
                        }
                    }, new IntentFilter(ACTION_UNLOCK), "android.permission.STATUS_BAR", null, Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: живой стол — " + t);
                }
            }
        });
        Class<?> rv = XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", cl);
        if (rv != null) Hook.all(rv, "updateCurveProperties", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                recents((ViewGroup) p.thisObject);
            }
        });
        XposedBridge.log("ntweaker: живой стол подключён");
    }

    private static boolean on() {
        return AnimHooks.prefs().coloros17Motion();
    }

    /** Значки текущей страницы и дока, видимые. */
    private static boolean collect() {
        VIEWS.clear();
        Activity a = launcher.get();
        if (a == null) return false;
        try {
            ViewGroup ws = (ViewGroup) XposedHelpers.callMethod(a, "getWorkspace");
            int page = (Integer) XposedHelpers.callMethod(ws, "getCurrentPage");
            View cell = ws.getChildAt(page);
            if (cell != null) add((ViewGroup) XposedHelpers.callMethod(cell, "getShortcutsAndWidgets"));
            Object hs = XposedHelpers.callMethod(a, "getHotseat");
            if (hs != null) add((ViewGroup) XposedHelpers.callMethod(hs, "getShortcutsAndWidgets"));
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: живой стол, значки — " + t);
        }
        int n = VIEWS.size();
        cx = new float[n]; cy = new float[n]; delay = new float[n]; amp = new float[n]; ux = new float[n]; uy = new float[n];
        int[] loc = new int[2];
        for (int i = 0; i < n; i++) {
            View v = VIEWS.get(i);
            v.getLocationOnScreen(loc);
            cx[i] = loc[0] + v.getWidth() / 2f;
            cy[i] = loc[1] + v.getHeight() / 2f;
        }
        return n > 0;
    }

    private static void add(ViewGroup g) {
        if (g == null) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0) VIEWS.add(v);
        }
    }

    private static void stop() {
        ValueAnimator r = running;
        running = null;                                         // до cancel: его onAnimationEnd уже не наш
        if (r != null) r.cancel();
        reset();
    }

    private static WeakReference<View> hitRef = new WeakReference<>(null);

    private static void reset() {
        View h = hitRef.get();
        if (h != null) h.setAnimationMatrix(null);
        for (View v : VIEWS) {
            v.setAnimationMatrix(null);
            v.setTransitionAlpha(1f);
        }
    }

    /**
     * Волна от точки (x, y) экрана через startMs. Скорость фронта 0.9 dp/мс; размах 9 dp·d/(d+60 dp)·e^(−d/800 dp) —
     * у самой точки ноль (туда садится окно), пик рядом, дальше затухает. Каждый значок — один плавный горб bump(t/0.12 с)
     * (NovaCain: синус с проскоком «странный»), на гребне сжатие до 0.98. «Удар»: стол проседает до 0.994 вокруг точки (NovaCain: «ещё незаметнее»).
     */
    static void wave(float x, float y, long startMs) {
        if (!on()) return;
        Activity a = launcher.get();
        if (a == null) return;
        a.getWindow().getDecorView().postDelayed(() -> {
            stop();
            if (!collect()) return;
            float d = a.getResources().getDisplayMetrics().density;
            float maxDelay = 0;
            for (int i = 0; i < VIEWS.size(); i++) {
                float dx = cx[i] - x, dy = cy[i] - y, dist = (float) Math.hypot(dx, dy);
                ux[i] = dist > 1 ? dx / dist : 0;
                uy[i] = dist > 1 ? dy / dist : 0;
                delay[i] = dist / (0.9f * d);                                  // мс
                amp[i] = (float) (9 * d * (dist / (dist + 60 * d)) * Math.exp(-dist / (800 * d)));
                maxDelay = Math.max(maxDelay, delay[i]);
            }
            float a0 = 9 * d, end = maxDelay + 790;
            View ws = (View) XposedHelpers.callMethod(a, "getWorkspace");
            hitRef = new WeakReference<>(ws);
            run((long) maxDelay + 800, t -> {
                // «удар» посадки: стол целиком проседает до 0.97 вокруг точки и пружинит обратно
                double st = t / 1000.0;
                float hit = (float) (0.006 * bump(st / 0.14));
                if (ws != null) {
                    M.reset();
                    ws.getLocationOnScreen(LOC);
                    M.postScale(1f - hit, 1f - hit, x - LOC[0], y - LOC[1]);
                    ws.setAnimationMatrix(t >= end ? null : M);
                }
                for (int i = 0; i < VIEWS.size(); i++) {
                    View v = VIEWS.get(i);
                    double s = (t - delay[i]) / 1000.0;
                    if (s <= 0) continue;
                    double k = bump(s / 0.12);
                    float off = (float) (amp[i] * k), sc = 1f - 0.02f * off / a0;
                    M.reset();
                    M.postScale(sc, sc, v.getWidth() / 2f, v.getHeight() / 2f);
                    M.postTranslate(ux[i] * off, uy[i] * off);
                    v.setAnimationMatrix(M);
                }
            });
        }, startMs);
    }

    /**
     * Каскад при разблокировке: ряды сверху вниз через 45 мс (внутри ряда слева направо +25 мс), каждый значок —
     * пружина (отклик 0.42 с, затухание 0.72) из 118 % и 36 dp выше места, проявление вдвое быстрее движения.
     */
    static void cascade() {
        if (!on()) return;
        Activity a = launcher.get();
        if (a == null) return;
        stop();
        if (!collect()) return;
        float d = a.getResources().getDisplayMetrics().density, w = a.getResources().getDisplayMetrics().widthPixels;
        float[] rows = cy.clone();
        java.util.Arrays.sort(rows);
        float maxDelay = 0;
        for (int i = 0; i < VIEWS.size(); i++) {
            int row = 0;
            for (int j = 1; j < rows.length; j++) if (rows[j] - rows[j - 1] > 24 * d && rows[j] <= cy[i]) row++;
            delay[i] = row * 45f + 25f * cx[i] / w;
            maxDelay = Math.max(maxDelay, delay[i]);
            VIEWS.get(i).setTransitionAlpha(0f);
        }
        ShellAnims.Spring sp = new ShellAnims.Spring(0.42, 0.72);
        float lift = 36 * d;
        run((long) maxDelay + sp.durationMs(), t -> {
            for (int i = 0; i < VIEWS.size(); i++) {
                View v = VIEWS.get(i);
                double s = (t - delay[i]) / 1000.0;
                float x = s <= 0 ? 0f : (float) sp.at(s);
                float sc = 1.18f - 0.18f * x;
                M.reset();
                M.postScale(sc, sc, v.getWidth() / 2f, v.getHeight() / 2f);
                M.postTranslate(0f, -lift * (1f - x));
                v.setAnimationMatrix(M);
                v.setTransitionAlpha(Math.max(0f, Math.min(1f, x * 2f)));
            }
        });
    }

    // ───────── недавние приложения ─────────

    private static final Matrix RM = new Matrix();

    /**
     * Карусель с глубиной, как в iOS: карточка тем меньше и прозрачнее, чем дальше от центра (на расстоянии одной
     * карточки — 0.88 и 0.8). Центральная — ровно 1: переход живого окна в карточку и обратно не сбивается.
     * Зовётся после RecentsView.updateCurveProperties (на каждой прокрутке).
     */
    static void recents(ViewGroup rv) {
        boolean on = on();
        float center = rv.getScrollX() + rv.getWidth() / 2f;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            if (!c.getClass().getName().contains("TaskView")) continue;
            if (!on) {
                c.setAnimationMatrix(null);
                c.setTransitionAlpha(1f);
                continue;
            }
            float span = Math.max(1f, c.getWidth() * 1.05f);
            float d = Math.min(1f, Math.abs(c.getLeft() + c.getTranslationX() + c.getWidth() / 2f - center) / span);
            float e = d * d * (3f - 2f * d);                     // мягко у центра
            if (e < 0.001f) {
                c.setAnimationMatrix(null);
                c.setTransitionAlpha(1f);
                continue;
            }
            float sc = 1f - 0.12f * e;
            RM.reset();
            RM.postScale(sc, sc, c.getWidth() / 2f, c.getHeight() / 2f);
            c.setAnimationMatrix(RM);
            c.setTransitionAlpha(1f - 0.2f * e);
        }
    }

    // ───────── меню приложений ─────────

    private static final java.util.Set<View> DRAWER = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final Matrix DM = new Matrix();

    /**
     * Меню приложений «выплывает» рядами за пальцем (AllAppsTransitionController.setProgress: 1 — закрыто, 0 — открыто).
     * Ряд с относительной высотой r (0 — верхний, 1 — нижний) отстаёт на 0.4·r прогресса; каждый значок поднимается
     * снизу на 70 dp, вырастает из 0.9 и проявляется. Обратимо: тянешь меню вниз — ряды уходят в обратном порядке.
     * Открыто/закрыто полностью — всё сброшено, прокрутка списка не задета.
     */
    static void drawer(Object ctl, float progress) {
        float open = 1f - Math.max(0f, Math.min(1f, progress));
        ViewGroup rv;
        try {
            rv = (ViewGroup) XposedHelpers.callMethod(XposedHelpers.getObjectField(ctl, "mAppsView"), "getActiveRecyclerView");
        } catch (Throwable t) {
            return;
        }
        if (rv == null) return;
        if (!on() || open >= 0.999f || open <= 0.001f) {
            if (!DRAWER.isEmpty()) {
                for (View v : DRAWER) { v.setAnimationMatrix(null); v.setTransitionAlpha(1f); }
                DRAWER.clear();
            }
            return;
        }
        int n = rv.getChildCount();
        if (n == 0) return;
        float minTop = Float.MAX_VALUE, maxTop = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float t = rv.getChildAt(i).getTop();
            minTop = Math.min(minTop, t);
            maxTop = Math.max(maxTop, t);
        }
        float span = Math.max(1f, maxTop - minTop), lift = 70 * rv.getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            View v = rv.getChildAt(i);
            float r = (v.getTop() - minTop) / span;
            float local = Math.max(0f, Math.min(1f, (open - 0.4f * r) / 0.6f));
            float e = 1f - (1f - local) * (1f - local) * (1f - local);
            float sc = 0.9f + 0.1f * e;
            DM.reset();
            DM.postScale(sc, sc, v.getWidth() / 2f, v.getHeight() / 2f);
            DM.postTranslate(0f, lift * (1f - e));
            v.setAnimationMatrix(DM);
            v.setTransitionAlpha(Math.min(1f, local * 1.5f));
            DRAWER.add(v);
        }
    }

    /**
     * Плавный горб без отрицательной фазы: u²·e^(2(1−u)) — старт с нулевой скоростью, пик 1 при u = 1, мягкое
     * затухание (к u = 4 ≈ 0.02). Значок отъезжает, останавливается и возвращается, не проскакивая своё место.
     */
    private static double bump(double u) {
        return u <= 0 ? 0 : u * u * Math.exp(2 * (1 - u));
    }

    private interface Frame {
        void at(float ms);
    }

    private static void run(long total, Frame f) {
        ValueAnimator va = ValueAnimator.ofFloat(0f, total);
        va.setDuration(total);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(an -> f.at((Float) an.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (running == an) {
                    running = null;
                    reset();
                }
            }
        });
        running = va;
        va.start();
    }
}
