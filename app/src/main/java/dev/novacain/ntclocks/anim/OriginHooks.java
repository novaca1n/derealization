package dev.novacain.ntclocks.anim;

import android.app.Activity;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Движение OriginOS 17 (по видео NovaCain):
 *  - пока открывается/открыто приложение или меню приложений — рабочий стол (иконки и док) размывается: сила по тому,
 *    насколько стол отъехал (масштаб Workspace) и насколько открыто меню; при быстрой смене приложений размытие не
 *    успевает спасть — окна «перелетают» поверх матового стола;
 *  - меню приложений не выезжает снизу, а проявляется из глубины (растёт из 90 % и проявляется);
 *  - шторка вырастает от верхнего края из 88 % и проявляется (только на разблокированном телефоне).
 */
public final class OriginHooks {
    private OriginHooks() {
    }

    // ───────── лаунчер ─────────

    private static WeakReference<Activity> launcher = new WeakReference<>(null);

    public static void launcher(ClassLoader cl) {
        Class<?> l = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        if (l != null) {
            Hook.all(l, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (launcher.get() == a) return;
                    launcher = new WeakReference<>(a);
                    wsRef = new WeakReference<>(null);
                    View root = a.getWindow().getDecorView();
                    root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            try {
                                homeBlur(a);
                            } catch (Throwable t) {
                                XposedBridge.log("ntweaker: размытие стола — " + t);
                                root.getViewTreeObserver().removeOnPreDrawListener(this);
                            }
                            return true;
                        }
                    });
                }
            });
        }
        // меню приложений: прогресс 1 — закрыто, 0 — открыто
        Class<?> all = XposedHelpers.findClassIfExists("com.android.launcher3.allapps.AllAppsTransitionController", cl);
        if (all != null) {
            Hook.all(all, "setProgress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    drawer(p.thisObject, (Float) p.args[0]);
                    HomeWave.drawer(p.thisObject, (Float) p.args[0]);
                }
            });
        }
        // прозрачность меню: у Nothing оно проявляется только с трети анимации (ALL_APPS_FADE 0.33…0.83) и в «наплыве»
        // выскакивало в конце. Для своих режимов — быстрая кривая: видно уже через 40 % движения
        Class<?> swipe = XposedHelpers.findClassIfExists("com.android.launcher3.touch.AllAppsSwipeController", cl);
        if (swipe != null) {
            XC_MethodHook fade = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    String d = AnimHooks.prefs().drawer;
                    if (!AnimPrefs.IOS.equals(d) && !AnimPrefs.ORIGIN.equals(d)) return;
                    try {
                        XposedHelpers.callMethod(p.args[1], "setInterpolator", 10, (android.view.animation.Interpolator) t -> {
                            float x = Math.min(1f, t / 0.4f);
                            return 1f - (1f - x) * (1f - x);
                        });
                    } catch (Throwable ignored) {
                    }
                }
            };
            Hook.all(swipe, "applyNormalToAllAppsAnimConfig", fade);
            Hook.all(swipe, "applyAllAppsToNormalConfig", fade);
        }
        // нажатие на иконку как в ColorOS (IconPressAnimManager.NormalAnimation): сжатие до 85 % и затемнение до 40 %
        // за 200 мс по кривой (0.4, 0, 0.2, 1); отпустил — обратно
        Class<?> bubble = XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView", cl);
        if (bubble != null) {
            // только BubbleTextView.onTouchEvent (раньше — View.dispatchTouchEvent у всех видов на пути касания)
            Hook.all(bubble, "onTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    View v = (View) p.thisObject;
                    int a = ((android.view.MotionEvent) p.args[0]).getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_DOWN) press(v, true);
                    else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) press(v, false);
                }
            });
        }
        XposedBridge.log("ntweaker: движение OriginOS 17 (лаунчер) подключено");
    }

    private static final int PRESS_TAG = 0x7e4e5402;

    private static void press(View v, boolean down) {
        AnimPrefs pr = AnimHooks.prefs();
        Object prev = v.getTag(PRESS_TAG);
        if (prev instanceof android.animation.ValueAnimator) ((android.animation.ValueAnimator) prev).cancel();
        if (!pr.iconPress && !down && v.getScaleX() == 1f) return;
        if (!pr.iconPress && down) return;
        float from = (1f - v.getScaleX()) / 0.15f;                     // текущая «глубина» нажатия 0…1
        float to = down ? 1f : 0f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(Math.max(0f, Math.min(1f, from)), to);
        va.setDuration(Math.round(200 * Math.abs(to - from)) + 1);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f));
        Object icon = XposedHelpers.getObjectField(v, "mIcon");
        va.addUpdateListener(an -> {
            float k = (Float) an.getAnimatedValue();
            float s = 1f - 0.15f * k;
            v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
            v.setScaleX(s); v.setScaleY(s);
            if (icon instanceof android.graphics.drawable.Drawable) {
                ((android.graphics.drawable.Drawable) icon).setColorFilter(k <= 0.001f ? null
                        : new android.graphics.PorterDuffColorFilter(android.graphics.Color.argb(Math.round(0.4f * 255 * k), 0, 0, 0),
                        android.graphics.PorterDuff.Mode.SRC_ATOP));
            }
        });
        v.setTag(PRESS_TAG, va);
        va.start();
    }

    // кэш видов лаунчера для размытия (без рефлексии на каждом кадре)
    private static WeakReference<View> wsRef = new WeakReference<>(null), hsRef = new WeakReference<>(null);
    private static WeakReference<Object> allRef = new WeakReference<>(null);
    private static java.lang.reflect.Field progressField;

    /**
     * Размытие иконок и дока: k — насколько стол «ушёл» (масштаб при открытии приложения или открытое меню).
     * RenderEffect видеочип накладывает при КАЖДОМ выводе слоя — размывать стол целиком (1080×2392) каждый кадр
     * было главной нагрузкой лаунчера в переходах (замер FrameTimeline/atrace: ожидание видеочипа 1.1 с против 0.41 с
     * без размытия). Поэтому размываем уменьшенную в BlurProxy.Q раз копию стола (в 16 раз меньше точек), растягиваем
     * её масштабом поверх и плавно меняем резкий стол на размытый (transitionAlpha — родные анимации Nothing её не
     * трогают).
     */
    private static void homeBlur(Activity a) throws Exception {
        AnimPrefs pr = AnimHooks.prefs();
        View ws = wsRef.get();
        if (ws == null) {
            ws = (View) XposedHelpers.callMethod(a, "getWorkspace");
            wsRef = new WeakReference<>(ws);
            hsRef = new WeakReference<>((View) XposedHelpers.callMethod(a, "getHotseat"));
            Object all = XposedHelpers.callMethod(a, "getAllAppsController");
            allRef = new WeakReference<>(all);
            if (all != null) { progressField = XposedHelpers.findField(all.getClass(), "mProgress"); }
        }
        if (ws == null) return;
        View hs = hsRef.get();
        float k = 0f;
        if (pr.homeBlur > 0f) {
            float home = Math.max(0.7f, Math.min(0.99f, pr.homeScale));
            k = Math.max(k, (1f - ws.getScaleX()) / (1f - home));
            Object all = allRef.get();
            if (all != null && progressField != null) k = Math.max(k, 1f - progressField.getFloat(all));
        }
        k = Math.max(0f, Math.min(1f, k));
        BlurProxy proxy = BlurProxy.of(ws, hs);
        if (proxy == null) return;
        if (k < 0.01f) {
            if (proxy.getVisibility() != View.GONE) {
                proxy.setVisibility(View.GONE);
                ws.setTransitionAlpha(1f);
                if (hs != null) hs.setTransitionAlpha(1f);
            }
            return;
        }
        int radius = Math.round(pr.homeBlur * a.getResources().getDisplayMetrics().density);
        if (proxy.getVisibility() != View.VISIBLE) {
            proxy.setRadius(radius);
            proxy.setVisibility(View.VISIBLE);
            proxy.invalidate();                                 // снимок стола — один раз за переход
        } else if (proxy.radius != radius) {
            proxy.setRadius(radius);
        }
        proxy.follow(ws);
        float x = Math.min(1f, k * 1.6f);
        float blurA = x * x * (3f - 2f * x);                    // размытая копия проявляется быстрее, чем уходит стол
        proxy.setAlpha(blurA);
        ws.setTransitionAlpha(1f - blurA);
        if (hs != null) hs.setTransitionAlpha(1f - blurA);
    }

    /** Уменьшенная копия стола и дока под размытие — сразу над доком в DragLayer, ниже папок и летящих значков. */
    static final class BlurProxy extends View {
        static final int Q = 4;
        private static WeakReference<BlurProxy> current = new WeakReference<>(null);
        private final WeakReference<View> ws, hs;
        int radius = -1;

        private BlurProxy(View ws, View hs) {
            super(ws.getContext());
            this.ws = new WeakReference<>(ws);
            this.hs = new WeakReference<>(hs);
            setVisibility(GONE);
            setWillNotDraw(false);
            setPivotX(0f);
            setPivotY(0f);
        }

        static BlurProxy of(View ws, View hs) {
            BlurProxy p = current.get();
            if (p != null && p.ws.get() == ws && p.getParent() != null) return p;
            if (!(ws.getParent() instanceof android.view.ViewGroup)) return null;
            android.view.ViewGroup layer = (android.view.ViewGroup) ws.getParent();
            p = new BlurProxy(ws, hs);
            int at = Math.max(layer.indexOfChild(ws), hs != null && hs.getParent() == layer ? layer.indexOfChild(hs) : -1) + 1;
            layer.addView(p, at, new android.view.ViewGroup.LayoutParams(ws.getWidth(), ws.getHeight()));
            current = new WeakReference<>(p);
            return p;
        }

        void setRadius(int r) {
            radius = r;
            float q = Math.max(1f, r / (float) Q);
            setRenderEffect(RenderEffect.createBlurEffect(q, q, Shader.TileMode.CLAMP));
        }

        /** Повторить положение и масштаб стола: точка стола x → left + tx + pivot·(1−s) + s·x. */
        void follow(View w) {
            // DragLayer чужих детей не измеряет — границы задаём сами (иначе 0×0 и всё обрезано)
            if (getLeft() != 0 || getTop() != 0 || getWidth() != w.getWidth() || getHeight() != w.getHeight())
                layout(0, 0, w.getWidth(), w.getHeight());
            float s = w.getScaleX();
            setScaleX(Q * s);
            setScaleY(Q * w.getScaleY());
            setTranslationX(w.getLeft() + w.getTranslationX() + w.getPivotX() * (1f - s) - getLeft());
            setTranslationY(w.getTop() + w.getTranslationY() + w.getPivotY() * (1f - w.getScaleY()) - getTop());
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            View w = ws.get();
            if (w == null) return;
            c.save();
            c.scale(1f / Q, 1f / Q);
            int sc = c.save();
            c.translate(-w.getScrollX(), -w.getScrollY());        // как View.updateDisplayListIfDirty
            w.draw(c);
            c.restoreToCount(sc);
            View h = hs.get();
            if (h != null && h.getVisibility() == VISIBLE) {
                c.translate(h.getLeft() - w.getLeft() + h.getTranslationX() - w.getTranslationX(), h.getTop() - w.getTop() + h.getTranslationY() - w.getTranslationY());
                c.translate(-h.getScrollX(), -h.getScrollY());
                h.draw(c);
            }
            c.restore();
        }
    }

    private static boolean drawerTouched;

    private static void drawer(Object ctl, float progress) {
        AnimPrefs pr = AnimHooks.prefs();
        View v = (View) XposedHelpers.getObjectField(ctl, "mHiddenAppsPagedView");
        if (v == null) return;
        boolean ios = AnimPrefs.IOS.equals(pr.drawer);
        if (!AnimPrefs.ORIGIN.equals(pr.drawer) && !ios) {
            if (drawerTouched) {                    // выключили — вернуть как у Nothing
                drawerTouched = false;
                v.setScaleX(1f); v.setScaleY(1f); v.setTransitionAlpha(1f);
            }
            return;
        }
        drawerTouched = true;
        float open = 1f - Math.max(0f, Math.min(1f, progress));
        float eased = 1f - (1f - open) * (1f - open);
        float shift = XposedHelpers.getFloatField(ctl, "mShiftRange");
        if (ios) {
            // как App Library в iOS: меню не выезжает, а «наплывает» — проявляется и чуть уменьшается из 106 % до
            // своего размера; почти без сдвига. Стол за ним размывается (homeBlur)
            float e3 = 1f - (1f - open) * (1f - open) * (1f - open);
            v.setTranslationY(progress * shift * 0.05f);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() * 0.45f);
            float si = 1.06f - 0.06f * e3;
            v.setScaleX(si); v.setScaleY(si);
            v.setTransitionAlpha(Math.min(1f, open * 2.5f));
            return;
        }
        v.setTranslationY(progress * shift * 0.12f);          // лишь лёгкий подъём вместо выезда снизу
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() * 0.42f);
        float s = 0.9f + 0.1f * eased;
        v.setScaleX(s); v.setScaleY(s);
        v.setTransitionAlpha(Math.min(1f, open * 1.6f));
    }

    // ───────── шторка (SystemUI) ─────────

    private static WeakReference<View> panel = new WeakReference<>(null);
    private static boolean shadeTouched;

    public static void systemui(ClassLoader cl) {
        Class<?> pv = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationPanelView", cl);
        if (pv != null) {
            Hook.ctors(pv, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    panel = new WeakReference<>((View) p.thisObject);
                }
            });
        }
        Class<?> sm = XposedHelpers.findClassIfExists("com.android.systemui.shade.ShadeExpansionStateManager", cl);
        if (sm != null) {
            Hook.all(sm, "onPanelExpansionChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        shade((Float) p.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: шторка OriginOS — " + t);
                    }
                }
            });
        }
        XposedBridge.log("ntweaker: шторка OriginOS 17 " + (pv != null && sm != null ? "подключена" : "не найдена"));
    }

    private static void shade(float fraction) {
        View v = panel.get();
        if (v == null) return;
        AnimPrefs pr = AnimHooks.prefs();
        boolean unlocked = !Lock.locked(v.getContext());
        ColorShade.update(v, fraction, unlocked && AnimPrefs.COLOROS.equals(pr.shade));
        IosShade.update(v, fraction, unlocked && AnimPrefs.IOS.equals(pr.shade));
        boolean on = AnimPrefs.ORIGIN.equals(pr.shade) && unlocked;
        if (!on) {
            if (shadeTouched) {
                shadeTouched = false;
                v.setScaleX(1f); v.setScaleY(1f); v.setTransitionAlpha(1f);
            }
            return;
        }
        shadeTouched = true;
        float f = Math.max(0f, Math.min(1f, fraction));
        float eased = 1f - (1f - f) * (1f - f) * (1f - f);
        float s = 0.88f + 0.12f * eased;
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(0f);
        v.setScaleX(s); v.setScaleY(s);
        v.setTransitionAlpha(Math.min(1f, f * 2.2f));
    }

    /**
     * Шторка «iOS»: панель не прилипает к пальцу 1:1, а мягко догоняет его пружиной (отклик 0,3 с, затухание 0,88 —
     * едва заметный доезд). Догоняющая доля F двигает всё: панель съезжает сверху на 40 dp, вырастает 0,95 → 1 от
     * верхнего края и проявляется; плитки и уведомления появляются волной сверху вниз в такт пальцу — каждая со своим
     * запаздыванием по высоте на экране (плитки 0,9 → 1 и проявление, уведомления — проявление). Без «пыхов» на пороге.
     */
    static final class IosShade {
        private static float cur = Float.NaN, vel, target;
        private static long lastFrame;
        private static boolean running, active;
        private static final java.util.List<View> kids = new java.util.ArrayList<>();
        private static float[] kidY = new float[0];
        private static boolean[] row = new boolean[0];
        private static WeakReference<View> panelRef = new WeakReference<>(null);

        static void update(View panel, float fraction, boolean on) {
            if (!on) {
                if (active) { active = false; reset(panel); }
                return;
            }
            float f = Math.max(0f, Math.min(1f, fraction));
            if (!active) { active = true; cur = f; vel = 0f; }
            panelRef = new WeakReference<>(panel);
            if (f > 0.01f && kids.isEmpty()) collect(panel);
            target = f;
            if (!running) { running = true; lastFrame = 0; android.view.Choreographer.getInstance().postFrameCallback(IosShade::step); }
        }

        private static void step(long nanos) {
            View panel = panelRef.get();
            if (panel == null || !active) { running = false; return; }
            float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (nanos - lastFrame) / 1e9f);
            lastFrame = nanos;
            double w = 2 * Math.PI / 0.3, z = 0.88;
            int n = Math.max(1, (int) Math.ceil(dt / 0.004));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = (float) (-w * w * (cur - target) - 2 * z * w * vel);
                vel += a * h; cur += vel * h;
            }
            boolean settled = Math.abs(cur - target) < 0.001f && Math.abs(vel) < 0.01f;
            if (settled) { cur = target; vel = 0f; }
            apply(panel, cur);
            if (settled) {
                running = false;
                if (target <= 0.001f) { restoreKids(); }
                return;
            }
            android.view.Choreographer.getInstance().postFrameCallback(IosShade::step);
        }

        private static void apply(View panel, float F) {
            float d = panel.getResources().getDisplayMetrics().density;
            float c = Math.max(0f, Math.min(1f, F));
            panel.setPivotX(panel.getWidth() / 2f);
            panel.setPivotY(0f);
            float s = 0.95f + 0.05f * c;
            panel.setScaleX(s); panel.setScaleY(s);
            panel.setTranslationY(-40f * d * (1f - F));        // F может чуть перелететь 1 — панель мягко «доедет»
            panel.setTransitionAlpha(Math.min(1f, c / 0.35f));
            float hgt = Math.max(1, panel.getResources().getDisplayMetrics().heightPixels);
            for (int i = 0; i < kids.size(); i++) {
                float k = Math.max(0f, Math.min(1f, (c - 0.1f - 0.3f * Math.min(1f, kidY[i] / hgt)) / 0.55f));
                float e = 1f - (1f - k) * (1f - k) * (1f - k);
                View v = kids.get(i);
                // положение и масштаб карточек уведомлений ведёт сама шторка (ViewState) — им только проявление;
                // сдвиг не трогаем ни у кого (плитки двигает QSAnimator)
                if (!row[i]) { float ks = 0.9f + 0.1f * e; v.setScaleX(ks); v.setScaleY(ks); }
                v.setTransitionAlpha(e);
            }
        }

        /** Плитки быстрых настроек и карточки уведомлений с их высотой на экране. */
        private static void collect(View root) {
            kids.clear();
            walk(root, 0);
            kidY = new float[kids.size()];
            row = new boolean[kids.size()];
            int[] a = new int[2];
            for (int i = 0; i < kids.size(); i++) {
                View v = kids.get(i);
                v.getLocationOnScreen(a);
                kidY[i] = Math.max(0, a[1]);
                row[i] = v.getClass().getSimpleName().equals("ExpandableNotificationRow");
            }
        }

        private static void walk(View v, int depth) {
            if (depth > 14 || v.getVisibility() != View.VISIBLE) return;
            String n = v.getClass().getSimpleName();
            if (n.endsWith("TileView") || n.endsWith("TileViewImpl") || n.equals("ExpandableNotificationRow")) { kids.add(v); return; }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1);
            }
        }

        private static void restoreKids() {
            for (int i = 0; i < kids.size(); i++) {
                View k = kids.get(i);
                if (i < row.length && !row[i]) { k.setScaleX(1f); k.setScaleY(1f); }
                k.setTransitionAlpha(1f);
            }
            kids.clear();
        }

        private static void reset(View panel) {
            restoreKids();
            panel.setScaleX(1f); panel.setScaleY(1f); panel.setTranslationY(0f); panel.setTransitionAlpha(1f);
            cur = Float.NaN; vel = 0f;
        }
    }

    /**
     * Открытие центра уведомлений как в ColorOS 16 (по коду их SystemUI: OplusPanelAnimationExImpl и
     * NotificationPanelChildAppearAnimationManagerImpl):
     *  - содержимое шторки съезжает сверху: translationY = lerp(−порог, 0, доля), по пружине с откликом 0,35 с;
     *    затухание 1,0, при резком рывке — до 0,6 (1 − 0,4·скорость/10000 px/с);
     *  - элементы (плитки и уведомления) скрыты, пока шторка открыта меньше чем на 86 %; потом волной сверху вниз
     *    вырастают 0,8 → 1 и проявляются 0 → 1 — пружины без перелёта с откликом 0,3 + 0,02·номер.
     */
    static final class ColorShade {
        private static float cur = Float.NaN, vel, target, lastTarget;
        private static long lastT;
        private static boolean running, shown, active;
        private static final java.util.List<View> kids = new java.util.ArrayList<>();
        private static long appearStart;

        static void update(View panel, float fraction, boolean on) {
            if (!on) {
                if (active) { active = false; panel.setTranslationY(0f); restore(); }
                return;
            }
            active = true;
            float f = Math.max(0f, Math.min(1f, fraction));
            float thr = 64f * panel.getResources().getDisplayMetrics().density;
            long now = android.os.SystemClock.uptimeMillis();
            float t = -thr * (1f - f);
            if (Float.isNaN(cur)) { cur = t; vel = 0f; }
            // скорость цели (px/с) — для «упругости» резкого рывка, как в ColorOS
            if (lastT != 0 && now > lastT) {
                float v = Math.abs(t - lastTarget) / ((now - lastT) / 1000f);
                damping = 1f - 0.4f * Math.min(1f, v / 10000f * 8f);
            }
            lastTarget = t; lastT = now; target = t;
            if (!running) { running = true; android.view.Choreographer.getInstance().postFrameCallback(n -> step(panel, n)); }
            // элементы: прячем при начале открытия, показываем волной с 86 %
            if (f <= 0.05f) {
                if (shown) { shown = false; restore(); }
            } else if (!shown && f < 0.86f) {
                hideKids(panel);
            } else if (!shown && f >= 0.86f) {
                shown = true;
                appearStart = now;
                if (kids.isEmpty()) collect(panel);
                android.view.Choreographer.getInstance().postFrameCallback(n -> appear());
            }
        }

        private static float damping = 1f;
        private static long lastFrame;

        private static void step(View panel, long nanos) {
            float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (nanos - lastFrame) / 1e9f);
            lastFrame = nanos;
            double w = 2 * Math.PI / 0.35;                         // отклик 0,35 с
            float a = (float) (-w * w * (cur - target) - 2 * damping * w * vel);
            vel += a * dt; cur += vel * dt;
            panel.setTranslationY(cur);
            if (Math.abs(cur - target) < 0.5f && Math.abs(vel) < 5f) {
                cur = target; panel.setTranslationY(cur); vel = 0; running = false; lastFrame = 0;
                return;
            }
            android.view.Choreographer.getInstance().postFrameCallback(n -> step(panel, n));
        }

        private static boolean hidden;

        private static void hideKids(View panel) {
            if (hidden) return;
            hidden = true;
            kids.clear();
            collect(panel);
            for (View k : kids) { k.setScaleX(0.8f); k.setScaleY(0.8f); k.setTransitionAlpha(0f); }
        }

        /**
         * Плитки быстрых настроек и карточки уведомлений — сверху вниз (по экранному Y). Это первый кадр вытягивания
         * шторки: имя класса проверяется один раз на класс, положение на экране — один раз на вид (раньше сортировка
         * звала getLocationOnScreen в каждом сравнении — +4 мс к первому кадру).
         */
        private static void collect(View root) {
            kids.clear();
            walk(root, 0);
            int n = kids.size();
            long[] key = new long[n];
            int[] a = new int[2];
            for (int i = 0; i < n; i++) {
                kids.get(i).getLocationOnScreen(a);
                key[i] = ((long) (a[1] + 100000) << 32) | ((a[0] + 100000) << 12) | i;   // Y, затем X, затем номер
            }
            java.util.Arrays.sort(key);
            View[] sorted = new View[n];
            for (int i = 0; i < n; i++) sorted[i] = kids.get((int) (key[i] & 0xFFF));
            kids.clear();
            java.util.Collections.addAll(kids, sorted);
        }

        private static final java.util.HashMap<Class<?>, Boolean> KID = new java.util.HashMap<>();

        private static void walk(View v, int depth) {
            if (depth > 14 || v.getVisibility() != View.VISIBLE) return;
            Boolean kid = KID.get(v.getClass());
            if (kid == null) {
                String n = v.getClass().getSimpleName();
                kid = n.endsWith("TileView") || n.endsWith("TileViewImpl") || n.equals("ExpandableNotificationRow");
                KID.put(v.getClass(), kid);
            }
            if (kid) { kids.add(v); return; }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1);
            }
        }

        private static void appear() {
            if (!shown) return;
            double sec = (android.os.SystemClock.uptimeMillis() - appearStart) / 1000.0;
            boolean done = true;
            for (int i = 0; i < kids.size(); i++) {
                double w = 2 * Math.PI / (0.3 + 0.02 * i);
                double p = 1 - (1 + w * sec) * Math.exp(-w * sec);   // критическое затухание, без перелёта
                if (p < 0.999) done = false; else p = 1;
                View k = kids.get(i);
                float s = (float) (0.8 + 0.2 * p);
                k.setScaleX(s); k.setScaleY(s); k.setTransitionAlpha((float) p);
            }
            if (!done) android.view.Choreographer.getInstance().postFrameCallback(n -> appear());
            else hidden = false;
        }

        private static void restore() {
            for (View k : kids) { k.setScaleX(1f); k.setScaleY(1f); k.setTransitionAlpha(1f); }
            kids.clear();
            hidden = false;
        }
    }
}
