package dev.novacain.ntclocks.anim;

import android.app.Application;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.animation.Animation;

import java.util.HashMap;
import java.util.Map;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Перехваты анимаций. Лаунчер Nothing (Launcher3): пружины RectFSpringAnim, отдаление рабочего стола и размытие фона
 * берутся из ресурсов лаунчера — подменяем ответы Resources для этих имён (имена AOSP не обфусцируются и переживают
 * обновления лаунчера из Play). SystemUI (WM Shell): стандартные анимации переходов TransitionAnimation —
 * подменяем своими (ShellAnims). Стиль «nothing» — ничего не трогаем. Любая ошибка — только в лог.
 */
public final class AnimHooks {
    private static final String LAUNCHER = "com.nothing.launcher";
    private static volatile AnimPrefs prefs;
    /** Направление текущей пружины: 1 — открытие, −1 — закрытие/жест «домой». */
    private static final ThreadLocal<Integer> DIR = new ThreadLocal<>();

    private AnimHooks() {
    }

    /** Настройки из Settings.Global + подписка на изменения (лениво: приложение процесса появляется не сразу). */
    static AnimPrefs prefs() {
        AnimPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new AnimPrefs();
        synchronized (AnimHooks.class) {
            if (prefs == null) {
                prefs = AnimPrefs.read(app.getContentResolver());
                try {
                    app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(AnimPrefs.KEY), false,
                            new ContentObserver(new Handler(Looper.getMainLooper())) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    prefs = AnimPrefs.read(app.getContentResolver());
                                }
                            });
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: подписка на анимации — " + t);
                }
            }
            return prefs;
        }
    }

    // ───────── лаунчер ─────────

    /** Что за число просит лаунчер: жёсткость по X/Y/размеру, затухание движения/размера, отдаление стола. */
    private enum Kind { STIFF_X, STIFF_Y, SCALE_STIFF, DAMP, SCALE_DAMP, HOME_SCALE }

    private static Map<Integer, Kind> floatIds;
    private static int blurDimenId, blurIntId;

    /** Номера ресурсов — строго из ресурсов самого лаунчера (пока приложения процесса нет — пусто, без кэша). */
    private static synchronized Map<Integer, Kind> ids(Resources ignored) {
        if (floatIds != null) return floatIds;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null || !LAUNCHER.equals(app.getPackageName())) return new HashMap<>();
        Resources r = app.getResources();
        Map<Integer, Kind> m = new HashMap<>();
        String[][] names = {
                {"swipe_up_rect_xy_stiffness", "STIFF_X"}, {"swipe_up_rect_x_stiffness", "STIFF_X"},
                {"swipe_up_rect_y_stiffness", "STIFF_Y"}, {"swipe_up_rect_x_stiffness_v3", "STIFF_X"},
                {"swipe_up_rect_y_stiffness_v3", "STIFF_Y"},
                {"swipe_up_rect_xy_damping_ratio", "DAMP"}, {"swipe_up_rect_x_damping_ratio", "DAMP"},
                {"swipe_up_rect_y_damping_ratio", "DAMP"}, {"swipe_up_rect_x_damping_ratio_v3", "DAMP"},
                {"swipe_up_rect_y_damping_ratio_v3", "DAMP"},
                {"swipe_up_rect_scale_stiffness", "SCALE_STIFF"}, {"swipe_up_rect_scale_higher_stiffness", "SCALE_STIFF"},
                {"swipe_up_rect_scale_stiffness_v3", "SCALE_STIFF"},
                {"swipe_up_rect_scale_damping_ratio", "SCALE_DAMP"}, {"swipe_up_rect_scale_damping_ratio_v3", "SCALE_DAMP"},
                {"workspace_content_scale", "HOME_SCALE"},
        };
        for (String[] n : names) {
            int id = r.getIdentifier(n[0], "dimen", LAUNCHER);
            if (id != 0) m.put(id, Kind.valueOf(n[1]));
        }
        blurDimenId = r.getIdentifier("max_depth_blur_radius_enhanced", "dimen", LAUNCHER);
        blurIntId = r.getIdentifier("max_depth_blur_radius", "integer", LAUNCHER);
        floatIds = m;
        XposedBridge.log("ntweaker: ресурсов анимаций лаунчера найдено " + m.size());
        return m;
    }

    private static boolean ours(Resources r, int id) {
        try {
            return LAUNCHER.equals(r.getResourcePackageName(id));      // у виджетов бывают те же номера ресурсов
        } catch (Throwable t) {
            return false;
        }
    }


    private static final java.util.Set<Class<?>> HOOKED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final float MAX_OVERSHOOT = 1.25f;

    /**
     * Перехваты пружины окна (RectFSpringAnim и подклассы): направление для подмены ресурсов, снятие потолка размера
     * и обёртка слушателей. У Nothing прогресс размера окна обрезан на 1.0 (setMaxValue(1) и min в onUpdate) —
     * пружина с перелётом «билась о потолок». Поднимаем потолок, а перелёт дорисовываем в слушателях сами.
     */
    /** Скорость пальца в конце жеста «домой» (px/мс) и когда он закончился — см. launcher(). */
    static volatile android.graphics.PointF gestureVel;
    static volatile long gestureAt;

    /** Кадр окна, который сейчас применяется с «искривлением» (см. hookWarp), — только на потоке анимации. */
    static final ThreadLocal<Overshoot> WARP = new ThreadLocal<>();

    /** Поле по одному из имён (у NOS 4.1 — mCurrentRectF, у NOS 5 — currentAnimTargetRectF), с поиском по предкам. */
    static java.lang.reflect.Field field(Class<?> c, String... names) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * «Искривление» окна: Nothing масштабирует окно приложения равномерно (по ширине) и обрезает высоту. Пока идёт кадр
     * растягивания, матрица окна — неравномерная, ровно по летящему прямоугольнику (интерфейс приложения сплющен и
     * расправляется вместе с окном), обрезка — всё окно, радиус углов — так, чтобы углы на экране оставались круглыми,
     * а не эллипсами. Чистые сдвиги (другие окна в том же кадре) не трогаем. Без выделения памяти на кадр.
     */
    private static void hookWarp(ClassLoader cl) {
        Class<?> sp = XposedHelpers.findClassIfExists("com.android.quickstep.util.SurfaceTransaction$SurfaceProperties", cl);
        if (sp == null) return;
        XposedHelpers.findAndHookMethod(sp, "setMatrix", android.graphics.Matrix.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Overshoot o = WARP.get();
                if (o != null && o.warpMatrix((android.graphics.Matrix) p.args[0])) p.args[0] = o.matrix;
            }
        });
        XposedHelpers.findAndHookMethod(sp, "setWindowCrop", android.graphics.Rect.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Overshoot o = WARP.get();
                if (o != null && o.warped) p.args[0] = o.crop;
            }
        });
        XposedHelpers.findAndHookMethod(sp, "setCornerRadius", float.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Overshoot o = WARP.get();
                if (o != null && o.warped) p.args[0] = o.radius((Float) p.args[0]);
            }
        });
    }

    private static void hookSpring(Class<?> c) {
        Hook.all(c, "start", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    DIR.set(XposedHelpers.getIntField(p.thisObject, "mAnimDirection"));
                } catch (Throwable ignored) {
                }
                // скорость жеста (px/мс) — для пружин краёв окна, чтобы движение подхватывалось без провала. До вызова:
                // первый кадр Nothing отдаёт прямо внутри start, и края заводятся уже на нём
                for (Object a : p.args) if (a instanceof android.graphics.PointF) XposedHelpers.setAdditionalInstanceField(p.thisObject, "ntVel", a);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                DIR.remove();
                if (prefs().overridesLauncher()) raiseCeiling(p.thisObject);
                try {
                    // окно садится в значок — по столу идёт волна (HomeWave), когда оно почти долетело
                    if (XposedHelpers.getIntField(p.thisObject, "mAnimDirection") != 1) {
                        android.graphics.RectF t = (android.graphics.RectF) XposedHelpers.getObjectField(p.thisObject, "mTargetRect");
                        if (t != null && !t.isEmpty()) HomeWave.wave(t.centerX(), t.centerY(), 170);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        Hook.all(c, "addOnUpdateListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object l = p.args[0];
                if (l == null || java.lang.reflect.Proxy.isProxyClass(l.getClass())) return;
                try {
                    p.args[0] = Overshoot.wrap(p.thisObject, l);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: обёртка пружины — " + t);
                }
            }
        });
    }

    /** Потолок прогресса размера: у базового класса — SpringAnimation (имена AOSP), у подкласса Nothing — свой «держатель». */
    private static void raiseCeiling(Object anim) {
        try {
            Object scale = XposedHelpers.getObjectField(anim, "mRectScaleAnim");
            if (scale != null) {
                XposedHelpers.callMethod(scale, "setMaxValue", MAX_OVERSHOOT);
                return;
            }
        } catch (Throwable ignored) {
        }
        // обфусцированный держатель пружины: узнаём по ключу "rect_scale"; потолок — единственное float-поле, равное 1.0
        try {
            for (java.lang.reflect.Field f : anim.getClass().getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object h = f.get(anim);
                if (h == null || !hasKey(h, "rect_scale")) continue;
                java.lang.reflect.Field ceiling = null;
                int found = 0;
                for (java.lang.reflect.Field g : h.getClass().getDeclaredFields()) {
                    if (g.getType() != float.class || java.lang.reflect.Modifier.isStatic(g.getModifiers())) continue;
                    g.setAccessible(true);
                    if (g.getFloat(h) == 1f) { ceiling = g; found++; }
                }
                if (found == 1) ceiling.setFloat(h, MAX_OVERSHOOT);
                return;
            }
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: потолок пружины — " + t);
        }
    }

    private static boolean hasKey(Object holder, String key) throws IllegalAccessException {
        for (java.lang.reflect.Field g : holder.getClass().getDeclaredFields()) {
            if (g.getType() != String.class || java.lang.reflect.Modifier.isStatic(g.getModifiers())) continue;
            g.setAccessible(true);
            if (key.equals(g.get(holder))) return true;
        }
        return false;
    }

    /**
     * Слушатель кадров пружины с дорисованным перелётом: Nothing считает прямоугольник окна с размером, обрезанным на
     * 1.0, но сам прогресс передаёт честный. Если прогресс больше 1 — растягиваем прямоугольник вокруг центра на ту же
     * долю пути (start → target), а слушателю отдаём 1.0 (прозрачности и скругления — как в конце анимации).
     */
    static final class Overshoot implements java.lang.reflect.InvocationHandler {
        private static final Object[] NONE = {};
        private final Object anim, target;
        // поля и методы ищем один раз на анимацию: XposedHelpers на каждом вызове собирает строку-ключ (мусор каждый кадр)
        private final java.lang.reflect.Field fDir, fStart, fTarget, fCur, fOrig;
        private final java.lang.reflect.Method mReverse;
        private int dir = -1;                     // 1 — открытие (читается на первом кадре)

        private Overshoot(Object anim, Object target) {
            this.anim = anim;
            this.target = target;
            Class<?> a = anim.getClass();
            fDir = field(a, "mAnimDirection");
            fStart = field(a, "mStartRect");
            fTarget = field(a, "mTargetRect");
            java.lang.reflect.Method r = null;
            try {
                r = XposedHelpers.findMethodBestMatch(a, "isReverse");
            } catch (Throwable ignored) {
            }
            mReverse = r;
            // «бегунок» окна (SpringAnimRunner): только у него можно «искривлять» окно
            java.lang.reflect.Field cur = field(target.getClass(), "mCurrentRectF", "currentAnimTargetRectF");
            java.lang.reflect.Field orig = field(target.getClass(), "mWindowOriginalBounds", "windowOriginalBounds");
            fCur = cur != null && orig != null ? cur : null;
            fOrig = fCur != null ? orig : null;
        }

        static Object wrap(Object anim, Object listener) {
            Class<?> iface = XposedHelpers.findClass("com.android.quickstep.util.RectFSpringAnim$OnUpdateListener", anim.getClass().getClassLoader());
            return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, new Overshoot(anim, listener));
        }

        private long t0 = -1;
        private long sentFrame = -1;              // кадр и прогресс последнего отданного слушателю обновления
        private float sentP = -1f;

        /**
         * Темп краёв относительно пружины Nothing: при скорости 260 самый медленный край проходит 90 % пути за ≈ 0.22 с
         * и почти весь — за ≈ 0.4 с, как в iOS 26 (там окно на 90 % экрана, когда в iOS 18 оно ещё на полпути).
         */
        private static final double LAUNCH = 0.85;

        /** Затухание инерции пальца, 1/с: пик смещения через 1/λ ≈ 60 мс, на величину v/(λ·e). */
        private static final double MOMENTUM = 16.0;
        private float momX, momY;                 // скорость пальца, px/с (0 — без жеста)
        private Edge[] edges;

        // «искривление» (см. hookWarp): переиспользуемые объекты — применяются в транзакцию сразу, копией
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
        final android.graphics.Rect crop = new android.graphics.Rect();
        private final float[] v = new float[9];
        boolean warped;
        private float warpRadius = 1f;            // во сколько раз поправить радиус Nothing, чтобы углы остались круглыми

        boolean warpMatrix(android.graphics.Matrix in) {
            try {
                in.getValues(v);
                if (v[android.graphics.Matrix.MSCALE_X] == 1f && v[android.graphics.Matrix.MSCALE_Y] == 1f) return false;   // чистый сдвиг
                android.graphics.RectF cur = (android.graphics.RectF) fCur.get(target);
                android.graphics.Rect orig = (android.graphics.Rect) fOrig.get(target);
                int w = orig.width(), h = orig.height();
                if (w <= 0 || h <= 0 || cur.width() <= 0 || cur.height() <= 0) return false;
                float a = cur.width() / w, b = cur.height() / h;
                // содержимое: деформация jellyEff (не сильнее окна), ширину заполняет всегда, лишнее снизу обрезает окно
                double je = Math.max(jellyEff, Math.log(b / a));
                float sx = (float) Math.max(a, b * Math.exp(-je)), sy = (float) (sx * Math.exp(je));
                float cw = cur.width() / sx, ch = Math.min(h, cur.height() / sy), cx = (w - cw) / 2f;
                matrix.setScale(sx, sy);
                matrix.postTranslate(cur.left - cx * sx, cur.top);
                crop.set(Math.round(cx), 0, Math.round(cx + cw), Math.round(ch));
                // Nothing задаёт радиус для равномерного масштаба u; на экране при (sx, sy) углы были бы эллипсами —
                // берём радиус по среднему масштабу
                float u = Math.min(1f, h >= w ? a : b);
                warpRadius = (float) (u / Math.sqrt(sx * sy));
                warped = true;
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        float radius(float r) {
            return r * warpRadius;
        }

        /**
         * Пружина одного края (масса 1): положение 0 → 1 с начальной скоростью v0 (доли пути в секунду) — аналитически,
         * без накопления ошибки. Начальная скорость берётся из жеста: окно, отпущенное на лету, продолжает лететь.
         */
        static final class Edge {
            private final double w, z, v0;

            Edge(double stiffness, double damping, double v0) {
                this.w = Math.sqrt(stiffness);
                this.z = Math.max(0.3, Math.min(1.0, damping));
                this.v0 = v0;
            }

            /**
             * Стартовый толчок края, как в iOS 26: начальная скорость 0.3·w. Полный толчок (z·w) срывал окно с места за
             * один кадр — выглядело дёргано; с 0.3·w окно трогается сразу, но ещё ≈ 0.6/w секунды плавно разгоняется
             * (ускорение w·(w − 2·v0) > 0), а не разгоняется с нуля, как пружина Nothing.
             */
            static final double KICK = 0.3;

            double at(double t) {
                if (z < 1) {
                    double wd = w * Math.sqrt(1 - z * z);
                    double b = (z * w - v0) / wd;
                    return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + b * Math.sin(wd * t));
                }
                return 1 - Math.exp(-w * t) * (1 + (w - v0) * t);
            }
        }

        /**
         * Время кадра в наносекундах (Choreographer — момент vsync этого кадра). AnimationUtils даёт целые миллисекунды:
         * на 120 Гц это до 12 % кадра ошибки, на быстром участке — дрожание края на несколько пикселей. Вне кадра
         * (обрыв анимации из обработчика касания) — те же часы (CLOCK_MONOTONIC) через AnimationUtils.
         */
        private static long frameNanos() {
            try {
                if (FRAME_NANOS != null) return (Long) FRAME_NANOS.invoke(android.view.Choreographer.getInstance());
            } catch (Throwable ignored) {
            }
            return android.view.animation.AnimationUtils.currentAnimationTimeMillis() * 1_000_000L;
        }

        /** Choreographer.getFrameTimeNanos — скрыт из SDK, ищем один раз. */
        private static final java.lang.reflect.Method FRAME_NANOS = frameNanosMethod();

        private static java.lang.reflect.Method frameNanosMethod() {
            try {
                java.lang.reflect.Method m = android.view.Choreographer.class.getDeclaredMethod("getFrameTimeNanos");
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: время кадра в нс недоступно — " + t);
                return null;
            }
        }

        /**
         * «Растягивание»: у каждого края окна своя пружина. Время — момент vsync кадра (frameNanos), чтобы не было
         * дрожания. Края стартуют с толчка (Edge.KICK), как в iOS 26, плюс скорость пальца. «ColorOS 17» (stretchBottom): дальний от иконки край (которому дальше
         * ехать) приходит к краю экрана первым, бока — следом, ближний — последним; при закрытии так же ведёт дальний. Иначе — как в iOS 26/27.
         */
        private android.graphics.RectF stretched(float p, AnimPrefs pr, long now) {
            if (!pr.stretch || fStart == null || fTarget == null) return null;
            try {
                if (dir < 0) dir = fDir != null ? fDir.getInt(anim) : 1;
                boolean open = dir == 1;
                if (!open && !pr.stretchBottom) return null;                   // закрытие — только у «ColorOS 17»
                if (mReverse != null && (Boolean) mReverse.invoke(anim, NONE)) return null;
                android.graphics.RectF s = (android.graphics.RectF) fStart.get(anim);
                android.graphics.RectF t = (android.graphics.RectF) fTarget.get(anim);
                if (t0 < 0) {
                    t0 = now;
                    float[] d = {Math.abs(t.left - s.left), Math.abs(t.top - s.top), Math.abs(t.right - s.right), Math.abs(t.bottom - s.bottom)};
                    float max = Math.max(Math.max(d[0], d[1]), Math.max(d[2], d[3]));
                    float base = open ? pr.openStiffness : pr.closeStiffness;
                    float damp = Math.min(1f, open ? pr.openDamping : pr.closeDamping);
                    // скорость жеста (px/мс → px/с): из start или, для жеста «домой», из конца жеста
                    Object vo = XposedHelpers.getAdditionalInstanceField(anim, "ntVel");
                    float vx = 0, vy = 0;
                    if (vo instanceof android.graphics.PointF) { vx = ((android.graphics.PointF) vo).x * 1000f; vy = ((android.graphics.PointF) vo).y * 1000f; }
                    if (!open && vx == 0 && vy == 0) {
                        // скорость пальца — одна на анимацию (у неё несколько слушателей: окно, иконка), берётся один раз
                        Object g = XposedHelpers.getAdditionalInstanceField(anim, "ntGest");
                        if (g == null) {
                            android.graphics.PointF gv = gestureVel;
                            g = gv != null && android.os.SystemClock.uptimeMillis() - gestureAt < 400 ? gv : new android.graphics.PointF();
                            gestureVel = null;
                            XposedHelpers.setAdditionalInstanceField(anim, "ntGest", g);
                        }
                        vx = ((android.graphics.PointF) g).x * 1000f;
                        vy = ((android.graphics.PointF) g).y * 1000f;
                    }
                    // жест: окно целиком продолжает лететь со скоростью пальца (затухающее смещение, см. MOMENTUM), края
                    // стартуют с места — скорость в момент отпускания та же, что у пальца, без разрыва
                    boolean flung = Math.abs(vx) + Math.abs(vy) > 50f;
                    momX = flung ? Math.max(-12000f, Math.min(12000f, vx)) : 0f;
                    momY = flung ? Math.max(-12000f, Math.min(12000f, vy)) : 0f;
                    if (pr.debug) android.util.Log.d("ntanim", String.format(java.util.Locale.ROOT, "старт %s %s v %.0f,%.0f px/с %s → %s",
                            open ? "откр" : "закр", anim.getClass().getName(), vx, vy, s.toShortString(), t.toShortString()));
                    // «ColorOS 17»: веса жёсткости краёв (лево, верх, право, низ). Опережает ДАЛЬНИЙ от иконки край — тот, кому
                    // дальше ехать: иконка в нижней половине — верх (и при открытии, и при закрытии), в верхней — низ.
                    // Перевес плавно растёт с удалением иконки от середины экрана (f = −1 у верха … 1 у низа), посередине
                    // верх и низ идут вровень. Доезд до конца гарантирует пол из прогресса Nothing (см. ниже).
                    android.graphics.RectF icon = open ? s : t, screen = open ? t : s;
                    double f = Math.max(-1, Math.min(1, (icon.centerY() - screen.centerY()) / (0.3 * Math.max(1f, screen.height()))));
                    double mid = open ? 2.0 : 1.8, lead = open ? 1.0 : 0.8;
                    double side = open ? 1.8 : 1.5;
                    double[] wt = {side, mid + lead * f, side, mid - lead * f};
                    edges = new Edge[4];
                    for (int i = 0; i < 4; i++) {         // 0 — лево, 1 — верх, 2 — право, 3 — низ
                        double k;
                        if (pr.stretchBottom) {
                            k = base * wt[i];
                        } else {
                            double axis = (i == 0 || i == 2) ? 1.6 : 1.0;
                            double near = Math.max(1.0, Math.min(2.4, Math.pow(max / Math.max(1f, d[i]), 0.5)));
                            k = base * Math.min(3.2, axis * near);
                        }
                        // окно, отпущенное жестом, продолжает лететь со скоростью пальца; иначе — «выстрел» из иконки
                        double kl = k * LAUNCH;
                        edges[i] = new Edge(kl, damp, flung ? 0 : Edge.KICK * Math.sqrt(kl));
                    }
                }
                if (p >= 0.999f && now - t0 > 2_000_000_000L) return null;   // давно закончилось — отдаём как есть
                double sec = (now - t0) / 1e9;
                // доезд: на последних 20 % прогресса самой анимации Nothing края плавно подтягиваются к месту. Когда её
                // обрывают (быстро открыли и закрыли) или она доходит до конца, последний кадр приходит с прогрессом 1 —
                // окно встаёт целиком. Жёсткий пол max(p, край) не годится: гасит инерцию, когда край по скорости пальца
                // на миг уходит назад
                double u = Math.max(0, Math.min(1, (p - 0.8) / 0.2)), pull = u * u * (3 - 2 * u);
                float l = settle(edges[0].at(sec), pull), tp = settle(edges[1].at(sec), pull),
                        r = settle(edges[2].at(sec), pull), b = settle(edges[3].at(sec), pull);
                // инерция пальца: x(t) = v·t·e^(−λt) — старт со скоростью v из нуля, пик через 1/λ, затухание; к концу
                // гасится тем же доездом
                double mo = sec * Math.exp(-MOMENTUM * sec) * (1 - pull);
                float ox = (float) (momX * mo), oy = (float) (momY * mo);
                android.graphics.RectF out = new android.graphics.RectF(s.left + (t.left - s.left) * l + ox, s.top + (t.top - s.top) * tp + oy,
                        s.right + (t.right - s.right) * r + ox, s.bottom + (t.bottom - s.bottom) * b + oy);
                // упругое содержимое: деформация окна ε = ln(доля высоты / доля ширины), содержимое догоняет s·ε пружиной
                android.graphics.RectF full = open ? t : s;
                if (full.width() > 0 && full.height() > 0 && out.width() > 0 && out.height() > 0) {
                    double eps = Math.log((out.height() / full.height()) / (out.width() / full.width()));
                    stepJelly(eps, now);
                    jellyEff = (float) Math.max(jelly * (1 - pull), eps);
                }
                if (pr.debug) android.util.Log.d("ntanim", String.format(java.util.Locale.ROOT, "%s %.2f p %.4f  л %.4f в %.4f п %.4f н %.4f  инерция %.0f,%.0f  содержимое %.3f",
                        open ? "откр" : "закр", sec * 1000, p, l, tp, r, b, ox, oy, jellyEff));
                return out;
            } catch (Throwable e) {
                return null;
            }
        }

        /**
         * Упругое содержимое окна: его деформация jelly — пружина (ω = 40/с, ζ = 0.4), которая догоняет 0.3 деформации
         * окна. Содержимое сплющивается мягче окна и с запаздыванием, а не приклеено к его форме. Упор: сплющиться
         * сильнее окна оно не может (иначе пришлось бы обрезать бока) — окно его подталкивает.
         */
        private void stepJelly(double eps, long now) {
            if (jellyAt < 0) {
                jelly = JELLY_S * eps;
                jellyV = 0;
            } else if (now > jellyAt) {
                double dt = Math.min(0.05, (now - jellyAt) / 1e9), target = JELLY_S * eps;
                int n = Math.max(1, (int) Math.ceil(dt / 0.002));
                double h = dt / n;
                for (int i = 0; i < n; i++) {
                    double acc = JELLY_W * JELLY_W * (target - jelly) - 2 * JELLY_Z * JELLY_W * jellyV;
                    jellyV += acc * h;
                    jelly += jellyV * h;
                }
                if (jelly < eps) {
                    jelly = eps;
                    jellyV = Math.max(jellyV, (eps - jellyEps) / dt);
                }
            } else {
                return;
            }
            jellyEps = eps;
            jellyAt = now;
        }

        private static final double JELLY_S = 0.3, JELLY_W = 40, JELLY_Z = 0.4;
        private double jelly, jellyV, jellyEps;
        private long jellyAt = -1;
        private float jellyEff;                   // деформация содержимого для этого кадра (с доездом к нулю)

        private static float settle(double e, double pull) {
            return (float) (e + (1 - e) * pull);
        }

        private Object call(java.lang.reflect.Method m, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
            if (args != null && args.length == 2 && args[1] instanceof Float && "onUpdate".equals(m.getName())) {
                float p = (Float) args[1];
                AnimPrefs pr = prefs();
                if (pr.overridesLauncher()) {
                    // Nothing зовёт слушателей до трёх раз за кадр (после шага каждой из пружин X, Y и размера): повтор с тем
                    // же кадром и прогрессом даёт то же окно — пропускаем
                    long now = Overshoot.frameNanos();
                    if (now == sentFrame && p == sentP) return null;
                    android.graphics.RectF st = stretched(p, pr, now);
                    if (st != null) {
                        sentFrame = now;
                        sentP = p;
                        args[0] = st;                    // массив аргументов прокси — свой на каждый вызов
                        if (p > 1f) args[1] = 1f;
                        // «искривление»: пока этот кадр применяется к окну, матрица окна — по форме прямоугольника
                        if (pr.warp && fCur != null) {
                            warped = false;
                            WARP.set(this);
                            try {
                                return call(m, args);
                            } finally {
                                WARP.remove();
                            }
                        }
                    } else if (p > 1f) {
                        try {
                            if (mReverse == null || !(Boolean) mReverse.invoke(anim, NONE)) {
                                android.graphics.RectF s = (android.graphics.RectF) fStart.get(anim);
                                android.graphics.RectF t = (android.graphics.RectF) fTarget.get(anim);
                                android.graphics.RectF r = new android.graphics.RectF((android.graphics.RectF) args[0]);
                                float k = Math.min(p, MAX_OVERSHOOT) - 1f;
                                r.inset(-(t.width() - s.width()) * k / 2f, -(t.height() - s.height()) * k / 2f);
                                args[0] = r;
                                args[1] = 1f;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return call(m, args);
        }
    }

    /**
     * Отдаление стола при открытии. У Nothing это своя анимация (LauncherContentAnimManager, обфусцирован): пружины
     * на workspace/hotseat с масштабом, зашитым числом 0.95, — ресурс workspace_content_scale она не читает.
     * Класс находим от необфусцированного NTLauncher.getLauncherContentAnimManager(): в нём поля-«аниматоры» вида,
     * у которых методы (float, float, FloatProperty). Для свойств масштаба стола подменяем 0.95 на своё значение.
     */
    private static void homeContent(ClassLoader cl) {
        try {
            Class<?> launcher = XposedHelpers.findClass("com.nothing.launcher.NTLauncher", cl);
            Class<?> manager = launcher.getMethod("getLauncherContentAnimManager").getReturnType();
            java.util.Set<Class<?>> animators = new java.util.HashSet<>();
            for (java.lang.reflect.Field f : manager.getDeclaredFields()) {
                for (java.lang.reflect.Method m : f.getType().getDeclaredMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (t.length == 3 && t[0] == float.class && t[1] == float.class && t[2] == android.util.FloatProperty.class) {
                        animators.add(f.getType());
                    }
                }
            }
            int hooked = 0;
            for (Class<?> c : animators) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (t.length != 3 || t[0] != float.class || t[1] != float.class || t[2] != android.util.FloatProperty.class) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            AnimPrefs a = prefs();
                            if (!a.overridesLauncher()) return;
                            String name = ((android.util.FloatProperty<?>) p.args[2]).getName();
                            if (name == null || !(name.startsWith("workspace_scale") || name.startsWith("hotseat_scale"))) return;
                            for (int i = 0; i < 2; i++) {
                                if (Math.abs((Float) p.args[i] - 0.95f) < 0.0001f) p.args[i] = a.homeScale;
                            }
                        }
                    });
                    hooked++;
                }
            }
            XposedBridge.log("ntweaker: отдаление стола — перехвачено методов " + hooked);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: отдаление стола не найдено — " + t);
        }
    }

    /**
     * Обои «уходят вглубь» (setWallpaperZoomOut по глубине состояния). Nothing включает это только на части своих
     * телефонов: проверка a6.u.b() (обфусцирована). Узнаём класс по форме: статические b() и d() без аргументов,
     * возвращают boolean, и он упоминается в BaseDepthController — иначе ничего не трогаем.
     */
    private static void depthGate(ClassLoader cl) {
        try {
            Class<?> gate = XposedHelpers.findClassIfExists("a6.u", cl);
            if (gate == null) return;
            java.lang.reflect.Method b = gate.getDeclaredMethod("b");
            java.lang.reflect.Method d = gate.getDeclaredMethod("d");
            if (b.getReturnType() != boolean.class || d.getReturnType() != boolean.class
                    || !java.lang.reflect.Modifier.isStatic(b.getModifiers()) || !java.lang.reflect.Modifier.isStatic(d.getModifiers())) return;
            XposedBridge.hookMethod(b, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    AnimPrefs a = prefs();
                    if (a.overridesLauncher() && a.depth) p.setResult(true);
                }
            });
            XposedBridge.log("ntweaker: глубина обоев подключена");
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: глубина обоев не найдена — " + t);
        }
    }

    public static void launcher(ClassLoader cl) {
        Class<?> spring = XposedHelpers.findClassIfExists("com.android.quickstep.util.RectFSpringAnim", cl);
        Class<?> config = XposedHelpers.findClassIfExists("com.android.quickstep.util.RectFSpringAnim$DefaultSpringConfig", cl);
        try {
            hookWarp(cl);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: искривление окна — " + t);
        }
        // Nothing стартует анимацию «домой» с нулевой скоростью (new PointF()), и окно после отпускания пальца трогается
        // с места. Настоящую скорость пальца берём из конца жеста — края окна продолжат движение по инерции
        Class<?> swipe = XposedHelpers.findClassIfExists("com.android.quickstep.AbsSwipeUpHandler", cl);
        if (swipe != null) {
            Hook.all(swipe, "handleNormalGestureEnd", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (a instanceof android.graphics.PointF) {
                            android.graphics.PointF v = (android.graphics.PointF) a;
                            gestureVel = new android.graphics.PointF(v.x, v.y);
                            gestureAt = android.os.SystemClock.uptimeMillis();
                        }
                    }
                }
            });
        }
        if (spring != null) {
            hookSpring(spring);
            // подклассы (у Nothing — асинхронная пружина закрытия и жеста «домой», имя обфусцировано) находим по
            // конструктору базового класса и цепляем те же перехваты к их собственным start/addOnUpdateListener
            Hook.ctors(spring, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Class<?> c = p.thisObject.getClass();
                    if (c != spring && HOOKED.add(c)) hookSpring(c);
                }
            });
        }
        if (config != null) {
            Hook.ctors(config, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 5 && p.args[4] instanceof Integer) DIR.set((Integer) p.args[4]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length == 5) DIR.remove();
                }
            });
        }
        XposedHelpers.findAndHookMethod(Resources.class, "getFloat", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    AnimPrefs a = prefs();
                    if (!a.overridesLauncher()) return;
                    Resources r = (Resources) p.thisObject;
                    int id = (Integer) p.args[0];
                    Kind k = ids(r).get(id);
                    if (k == null || !ours(r, id)) return;
                    Integer dir = DIR.get();
                    boolean open = dir != null && dir == 1;
                    float stiff = open ? a.openStiffness : a.closeStiffness, damp = open ? a.openDamping : a.closeDamping;
                    // Пропорции — как у самой Nothing (X 342 : Y 315 : размер 380). Перелёт размера окна за 1.0
                    // Nothing обрезает — потолок снимают hookSpring/Overshoot ниже.
                    switch (k) {
                        case STIFF_X: p.setResult(stiff); break;
                        // ColorOS держит X, Y и размер на одной жёсткости (SpringAnimConstants), Nothing — 342 : 315 : 380
                        case STIFF_Y: p.setResult(a.colorOs() ? stiff : stiff * 0.92f); break;
                        case SCALE_STIFF: p.setResult(a.colorOs() ? stiff : stiff * 1.11f); break;
                        case DAMP: p.setResult(damp); break;
                        case SCALE_DAMP: p.setResult(damp); break;
                        case HOME_SCALE: p.setResult(a.homeScale); break;
                    }
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: getFloat — " + t);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Resources r = (Resources) p.thisObject;
                int id = (Integer) p.args[0];
                if (floatIds == null) ids(r);
                if (id != blurDimenId || blurDimenId == 0) return;
                AnimPrefs a = prefs();
                if (!a.overridesLauncher() || !ours(r, id)) return;
                p.setResult(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, a.blur, r.getDisplayMetrics())));
            }
        });
        XposedHelpers.findAndHookMethod(Resources.class, "getInteger", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Resources r = (Resources) p.thisObject;
                int id = (Integer) p.args[0];
                if (floatIds == null) ids(r);
                if (id != blurIntId || blurIntId == 0) return;
                AnimPrefs a = prefs();
                if (!a.overridesLauncher() || !ours(r, id)) return;
                p.setResult(Math.round(a.blur * 23f / 34f));               // старый вариант в «своих единицах» Nothing
            }
        });
        homeContent(cl);
        depthGate(cl);
        XposedBridge.log("ntweaker: анимации лаунчера подключены" + (spring == null ? " (без RectFSpringAnim!)" : ""));
    }

    // ───────── SystemUI / WM Shell ─────────

    public static void shell() {
        Class<?> ta = XposedHelpers.findClassIfExists("com.android.internal.policy.TransitionAnimation", null);
        if (ta == null) {
            XposedBridge.log("ntweaker: TransitionAnimation не найден — переходы остаются родными");
            return;
        }
        XposedHelpers.findAndHookMethod(ta, "loadDefaultAnimationAttr", int.class, boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    AnimPrefs a = prefs();
                    if (AnimPrefs.IN_NATIVE.equals(a.inApp)) return;
                    Animation an = ShellAnims.create(a.inApp, (Integer) p.args[0], a.inAppSpeed);
                    if (an != null) p.setResult(an);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: переход — " + t);
                }
            }
        });
        XposedBridge.log("ntweaker: переходы WM Shell подключены");
    }
}
