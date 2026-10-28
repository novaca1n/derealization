package dev.novacain.ntclocks.anim;

import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Шторка без рывка в начале. Окно шторки, пока она закрыта, невидимо (INVISIBLE), и в первом кадре вытягивания
 * Android синхронно делает ему relayoutWindow (вызов в system_server, 12–16 мс) и измерение (~6 мс) — это и есть
 * рывок, который видно у стока (замер Perfetto: первый кадр 48–60 мс). Делаем корневой вид окна видимым уже при
 * касании строки состояния: между касанием и началом вытягивания у пальца 30–50 мс, relayout успевает пройти.
 * Фокус окна не трогаем (setPanelVisible сделал бы окно фокусным — закрылась бы клавиатура). Если палец отпустили,
 * не потянув, — контроллер пересчитывает состояние (apply) и прячет окно сам. Выключатель для замеров:
 * Settings.Global ntweaker_shadewarm = 0.
 */
public final class ShadeWarm {
    private ShadeWarm() {
    }

    private static WeakReference<Object> ctl = new WeakReference<>(null);
    private static boolean warmed;

    public static void hook(ClassLoader cl) {
        Class<?> wc = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationShadeWindowControllerImpl", cl);
        Class<?> bar = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarView", cl);
        if (wc == null || bar == null) {
            XposedBridge.log("derealization: shade warm-up — not found");
            return;
        }
        Hook.all(wc, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                ctl = new WeakReference<>(p.thisObject);
            }
        });
        Hook.all(bar, "onTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof MotionEvent)) return;
                int a = ((MotionEvent) p.args[0]).getActionMasked();
                try {
                    if (a == MotionEvent.ACTION_DOWN) warm();
                    else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) cool((View) p.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: shade warm-up — " + t);
                }
            }
        });
        XposedBridge.log("derealization: shade warm-up hooked");
    }

    private static View root() {
        Object c = ctl.get();
        return c != null ? (View) XposedHelpers.getObjectField(c, "mWindowRootView") : null;
    }

    private static void warm() {
        View r = root();
        if (r != null && android.provider.Settings.Global.getInt(r.getContext().getContentResolver(), "ntweaker_shadewarm", 1) == 0) return;
        if (r == null || r.getVisibility() == View.VISIBLE) return;      // уже видимо (экран блокировки, шторка)
        warmed = true;
        r.setVisibility(View.VISIBLE);
    }

    /** Отпустили: если шторку так и не потянули, состояние контроллера вернёт окну невидимость. */
    private static void cool(View bar) {
        if (!warmed) return;
        warmed = false;
        bar.postDelayed(() -> {
            Object c = ctl.get();
            if (c == null) return;
            try {
                Object st = XposedHelpers.getObjectField(c, "mCurrentState");
                // шторку потянули — окном уже управляет контроллер, лишний apply посреди анимации не нужен
                if (XposedHelpers.getBooleanField(st, "panelVisible")) return;
                XposedHelpers.callMethod(c, "apply", st);
            } catch (Throwable t) {
                XposedBridge.log("derealization: shade warm-up, reset — " + t);
            }
        }, 80);
    }
}
