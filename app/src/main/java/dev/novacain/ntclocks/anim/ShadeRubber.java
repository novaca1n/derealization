package dev.novacain.ntclocks.anim;

import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Упругая шторка («ColorOS 17», sysMotion): быстрые настройки раскрыты полностью, а палец тянет дальше вниз — панель
 * тянется следом с нарастающим сопротивлением (резина: смещение стремится к L = 18 % высоты экрана) и чуть вытягивается;
 * отпустил — возвращается пружиной с лёгким перелётом. Касания — из строки состояния (первое вытягивание) и из окна
 * шторки. Двигаем через setAnimationMatrix: translationY панели занят анимацией шторки (OriginHooks.ColorShade).
 */
public final class ShadeRubber {
    private ShadeRubber() {
    }

    private static WeakReference<Object> qs = new WeakReference<>(null);
    private static WeakReference<View> panel = new WeakReference<>(null);
    private static float base = Float.NaN, cur;
    private static ValueAnimator back;
    private static final Matrix M = new Matrix();

    public static void hook(ClassLoader cl) {
        Class<?> qsc = XposedHelpers.findClassIfExists("com.android.systemui.shade.QuickSettingsControllerImpl", cl);
        Class<?> pv = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationPanelView", cl);
        Class<?> win = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationShadeWindowView", cl);
        Class<?> bar = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarView", cl);
        if (qsc == null || pv == null || win == null) {
            XposedBridge.log("derealization: rubber shade — not found");
            return;
        }
        Hook.ctors(qsc, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                qs = new WeakReference<>(p.thisObject);
            }
        });
        Hook.ctors(pv, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                panel = new WeakReference<>((View) p.thisObject);
            }
        });
        XC_MethodHook touch = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] instanceof MotionEvent) {
                    try {
                        touch((MotionEvent) p.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("derealization: rubber shade — " + t);
                    }
                }
            }
        };
        Hook.all(win, "dispatchTouchEvent", touch);
        if (bar != null) Hook.all(bar, "onTouchEvent", touch);
        XposedBridge.log("derealization: rubber shade hooked");
    }

    private static void touch(MotionEvent e) {
        View v = panel.get();
        if (v == null) return;
        if (!AnimHooks.prefs().coloros17Motion()) {
            if (cur != 0f) set(v, 0f);
            return;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                base = Float.NaN;
                if (back != null) { back.cancel(); back = null; }
                break;
            case MotionEvent.ACTION_MOVE: {
                Object q = qs.get();
                boolean full = q != null && XposedHelpers.getBooleanField(q, "mFullyExpanded");
                if (!full) {
                    base = Float.NaN;
                    if (cur != 0f) set(v, 0f);
                    break;
                }
                float y = e.getRawY();
                if (Float.isNaN(base) || y < base) base = y;                   // упёрлись — отсюда и тянем
                float dy = y - base, l = 0.18f * v.getResources().getDisplayMetrics().heightPixels;
                set(v, l * (1f - 1f / (1f + 0.55f * dy / l)));                  // резина: к L, но никогда до неё
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                base = Float.NaN;
                if (cur > 0.5f) {
                    ShellAnims.Spring sp = new ShellAnims.Spring(0.38, 0.72);
                    float from = cur;
                    back = ValueAnimator.ofFloat(1f, 0f);
                    back.setDuration(sp.durationMs());
                    back.setInterpolator(sp);
                    back.addUpdateListener(a -> set(v, from * (Float) a.getAnimatedValue()));
                    back.start();
                } else if (cur != 0f) {
                    set(v, 0f);
                }
                break;
            default:
                break;
        }
    }

    /** Смещение off: панель сдвигается на 70 % и вытягивается от верха до +4 % на полном «L». */
    private static void set(View v, float off) {
        cur = off;
        if (Math.abs(off) < 0.01f) {
            v.setAnimationMatrix(null);
            return;
        }
        float l = 0.18f * v.getResources().getDisplayMetrics().heightPixels;
        M.reset();
        M.postScale(1f, 1f + 0.04f * off / l, v.getWidth() / 2f, 0f);
        M.postTranslate(0f, 0.7f * off);
        v.setAnimationMatrix(M);
    }
}
