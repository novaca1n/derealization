package dev.novacain.ntclocks.anim;

import android.graphics.Color;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.PathInterpolator;
import android.view.animation.TranslateAnimation;

/**
 * Свои анимации переходов внутри и между приложениями — подставляются WM Shell (SystemUI) вместо стандартных.
 * Номера типов — индексы WindowAnimation (как их выбирает TransitionAnimationHelper.loadAttributeAnimation):
 * 4/5 — открытие экрана (новый/старый), 6/7 — закрытие (предыдущий/текущий), 8/9 и 12/13 — открытие задачи (другое
 * приложение), 10/11 — закрытие задачи. Остальное (обои, вращение…) не трогаем — null = оставить как есть.
 *
 * Правила, чтобы не было «деревянности»: движение — настоящая пружина (длительность считается из её физики, а не
 * задаётся числом), два окна никогда не полупрозрачны одновременно (иначе посередине просвечивает чёрный фон),
 * нижнее окно только уходит в тень над чёрной подложкой, прозрачность меняется быстрее движения.
 */
public final class ShellAnims {
    private ShellAnims() {
    }

    /**
     * Пружина массы 1 из положения 0 в 1 как функция нормированного времени. response — период собственных колебаний
     * в секундах (как у SwiftUI/iOS), damping — коэффициент затухания (1 — без перелёта). Длительность — время, за
     * которое отклонение падает ниже 0,4 % пути: дальше глазом не видно.
     */
    public static final class Spring implements Interpolator {
        private final double w, z, settle;

        public Spring(double response, double damping) {
            w = 2 * Math.PI / Math.max(0.05, response);
            z = Math.max(0.3, Math.min(1.0, damping));
            settle = settleTime(w, z);
        }

        private static double settleTime(double w, double z) {
            double eps = 0.004;
            if (z < 1) return Math.log(1 / (eps * Math.sqrt(1 - z * z))) / (z * w);
            double t = 1 / w;                                    // (1+wt)e^(−wt) = eps — по Ньютону
            for (int i = 0; i < 30; i++) {
                double f = (1 + w * t) * Math.exp(-w * t) - eps, df = -w * w * t * Math.exp(-w * t);
                t -= f / df;
            }
            return t;
        }

        /** Положение пружины в момент s секунд. */
        public double at(double s) {
            if (z < 1) {
                double wd = w * Math.sqrt(1 - z * z);
                return 1 - Math.exp(-z * w * s) * (Math.cos(wd * s) + z * w / wd * Math.sin(wd * s));
            }
            return 1 - Math.exp(-w * s) * (1 + w * s);
        }

        public long durationMs() {
            return Math.round(settle * 1000);
        }

        @Override
        public float getInterpolation(float t) {
            return t >= 1f ? 1f : (float) at(t * settle);
        }
    }

    /** Кусок общей шкалы времени: до from — 0, после to — 1, между — плавное замедление. */
    private static Interpolator part(float from, float to) {
        return t -> {
            float x = Math.max(0f, Math.min(1f, (t - from) / (to - from)));
            return 1f - (1f - x) * (1f - x);
        };
    }

    /** Пружина стиля — общая для всех частей перехода; её же берёт превью в приложении. */
    public static Spring spring(String style, boolean task) {
        if (AnimPrefs.IOS.equals(style)) return task ? new Spring(0.46, 0.92) : new Spring(0.42, 1.0);
        if (AnimPrefs.COLOROS.equals(style)) return task ? new Spring(0.40, 0.96) : new Spring(0.36, 1.0);
        if (AnimPrefs.ORIGIN.equals(style)) return task ? new Spring(0.44, 0.70) : new Spring(0.40, 0.74);
        return null;
    }

    public static Animation create(String style, int attr, float speed) {
        boolean task = attr >= 8;
        Spring s = spring(style, task);
        if (s == null || attr < 4 || attr > 13) return null;
        long d = Math.round(s.durationMs() * speed);
        if (AnimPrefs.IOS.equals(style)) return ios(attr, s, d);
        if (AnimPrefs.COLOROS.equals(style)) { coSpeed = speed; return coloros(attr, s, d); }
        return origin(attr, s, d);
    }

    // ───────── iOS 26: экран «въезжает» поверх, старый уходит влево и в тень ─────────

    private static Animation ios(int attr, Spring s, long d) {
        switch (attr) {
            case 4: return set(d, slideX(1f, 0f, s));                                         // новый — справа, непрозрачный
            case 5: return set(d, slideX(0f, -0.25f, s), alpha(1f, 0.78f, s));               // старый — в тень
            case 6: return set(d, slideX(-0.25f, 0f, s), alpha(0.78f, 1f, s));
            case 7: return set(d, slideX(0f, 1f, s));                                         // текущий уезжает вправо
            // другое приложение: «вырастает» из центра, как открытие с иконки
            case 8: case 12: return set(d, scale(0.86f, 1f, s), alpha(0f, 1f, part(0f, 0.3f)));
            case 9: case 13: return set(d, scale(1f, 0.94f, s), alpha(1f, 0.7f, s));
            case 10: return set(d, scale(0.94f, 1f, s), alpha(0.7f, 1f, s));
            case 11: return set(d, scale(1f, 0.86f, s), alpha(1f, 0f, part(0.05f, 0.45f)));
            default: return null;
        }
    }

    // ───────── ColorOS: страница въезжает целиком, старая отступает вглубь — чуть уменьшается и темнеет ─────────

    /**
     * ColorOS 16 — один в один по ресурсам OPPO (oplus-framework-res: anim/oplus_open_slide_*, oplus_close_slide_*,
     * oplus_task_open/close_slide_*, и их interpolator): длительности, сдвиги, кривые Безье, затемнение до 50 %,
     * у переходов между приложениями — сдвиг на 109.9976 % и двухфазный масштаб (100→95 % за 150 мс, потом ×1.0526).
     * speed растягивает все длительности.
     */
    private static Animation coloros(int attr, Spring unused, long unusedD) {
        float k = coSpeed;
        Interpolator alphaI = new PathInterpolator(0.33f, 0f, 0.67f, 1f);
        switch (attr) {
            case 4: return co(true, k, tr(1f, 0f, 350, new PathInterpolator(0.3f, 0.1f, 0.3f, 1f)));
            case 5: return co(false, k, tr(0f, -0.3f, 350, new PathInterpolator(0.3f, 0.15f, 0.3f, 1f)), al(1f, 0.5f, 350, alphaI));
            case 6: return co(false, k, tr(-0.3f, 0f, 350, new PathInterpolator(0.3f, 0.26f, 0.4f, 1f)), al(0.5f, 1f, 350, alphaI));
            case 7: return co(true, k, tr(0f, 1f, 350, new PathInterpolator(0.25f, 0.1f, 0.3f, 1f)));
            case 8: case 12: return coTask(k, 1.099976f, 0f);
            case 9: case 13: return coTask(k, 0f, -1.099976f);
            case 10: return coTask(k, -1.099976f, 0f);
            case 11: return coTask(k, 0f, 1.099976f);
            default: return null;
        }
    }

    private static float coSpeed = 1f;

    /** Переход между приложениями ColorOS: сдвиг 420 мс + масштаб 100→95 % (150 мс) и затем ×1.0526 (270 мс с задержкой 150). */
    private static Animation coTask(float k, float from, float to) {
        Animation shrink = sc(1f, 0.95f, 150, new PathInterpolator(0.33f, 0f, 0.67f, 1f));
        Animation zoom = sc(1f, 1.052582f, 270, new PathInterpolator(0.15f, 0f, 0.5f, 1f));
        zoom.setStartOffset(150);
        return co(true, k, tr(from, to, 420, new PathInterpolator(0.4f, 0.4f, 0.08f, 1f)), shrink, zoom);
    }

    /** Набор со своими длительностями у каждой части (как в XML ColorOS — без общей длительности набора). */
    private static Animation co(boolean top, float k, Animation... parts) {
        AnimationSet s = new AnimationSet(false);
        for (Animation a : parts) {
            a.setDuration(Math.round(a.getDuration() * k));
            a.setStartOffset(Math.round(a.getStartOffset() * k));
            a.setFillEnabled(true);
            a.setFillBefore(false);
            a.setFillAfter(true);
            s.addAnimation(a);
        }
        s.setFillAfter(true);
        if (top) s.setZAdjustment(Animation.ZORDER_TOP);
        hidden(s, "setHasRoundedCorners", boolean.class, true);
        hidden(s, "setShowBackdrop", boolean.class, true);
        hidden(s, "setBackdropColor", int.class, Color.BLACK);
        return s;
    }

    private static Animation tr(float from, float to, long ms, Interpolator i) {
        Animation a = slideX(from, to, i);
        a.setDuration(ms);
        return a;
    }

    private static Animation sc(float from, float to, long ms, Interpolator i) {
        Animation a = scale(from, to, i);
        a.setDuration(ms);
        return a;
    }

    private static Animation al(float from, float to, long ms, Interpolator i) {
        Animation a = alpha(from, to, i);
        a.setDuration(ms);
        return a;
    }

    // ───────── OriginOS: зум «из глубины» с упругим доводом ─────────

    private static Animation origin(int attr, Spring s, long d) {
        switch (attr) {
            case 4: return set(d, scale(0.88f, 1f, s), alpha(0f, 1f, part(0f, 0.28f)));
            case 5: return set(d, scale(1f, 0.93f, s), alpha(1f, 0.65f, s));
            case 6: return set(d, scale(0.93f, 1f, s), alpha(0.65f, 1f, s));
            case 7: return set(d, scale(1f, 0.88f, s), alpha(1f, 0f, part(0.05f, 0.42f)));
            case 8: case 12: return set(d, scale(0.8f, 1f, s), alpha(0f, 1f, part(0f, 0.28f)));
            case 9: case 13: return set(d, scale(1f, 0.9f, s), alpha(1f, 0.6f, s));
            case 10: return set(d, scale(0.9f, 1f, s), alpha(0.6f, 1f, s));
            case 11: return set(d, scale(1f, 0.8f, s), alpha(1f, 0f, part(0.05f, 0.42f)));
            default: return null;
        }
    }

    // ───────── кирпичики ─────────

    private static Animation set(long duration, Animation... parts) {
        AnimationSet s = new AnimationSet(false);
        for (Animation a : parts) s.addAnimation(a);
        s.setDuration(duration);
        s.setFillAfter(true);
        s.setFillBefore(true);
        hidden(s, "setHasRoundedCorners", boolean.class, true);       // скругление окна, как у системных переходов
        hidden(s, "setShowBackdrop", boolean.class, true);            // тень нижнего окна — над чёрной подложкой
        hidden(s, "setBackdropColor", int.class, Color.BLACK);
        return s;
    }

    /** Скрытые методы Animation (SystemUI — платформенный, ему можно); не вышло — переход просто без этого. */
    private static void hidden(Animation a, String name, Class<?> type, Object value) {
        try {
            Animation.class.getMethod(name, type).invoke(a, value);
        } catch (Throwable ignored) {
        }
    }

    private static Animation slideX(float from, float to, Interpolator i) {
        Animation a = new TranslateAnimation(Animation.RELATIVE_TO_SELF, from, Animation.RELATIVE_TO_SELF, to,
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f);
        a.setInterpolator(i);
        return a;
    }

    private static Animation scale(float from, float to, Interpolator i) {
        Animation a = new ScaleAnimation(from, to, from, to, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        a.setInterpolator(i);
        return a;
    }

    private static Animation alpha(float from, float to, Interpolator i) {
        Animation a = new AlphaAnimation(from, to);
        a.setInterpolator(i);
        return a;
    }
}
