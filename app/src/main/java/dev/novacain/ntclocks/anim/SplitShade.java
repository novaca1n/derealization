package dev.novacain.ntclocks.anim;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Раздельная шторка, как в Nothing OS 5: свайп из правой половины строки состояния открывает сразу быстрые настройки
 * (без уведомлений), из левой — только уведомления (без плиток). На старой шторке NOS 4.1, без её переделки:
 *  - справа — штатный «открыть быстрые настройки сразу» (QuickSettingsControllerImpl.isOpenQsEvent — так работает
 *    свайп двумя пальцами), уведомления на это время уводятся за экран и гаснут;
 *  - слева — рамка быстрых настроек (qs_frame) уводится за экран вместе с областью нажатий, список уведомлений
 *    поднимается под строку состояния (сдвиг = его верхний отступ − высота строки состояния). Если дотянуть шторку
 *    до быстрых настроек, они появляются как обычно.
 * Только на разблокированном телефоне; при закрытии шторки всё возвращается.
 */
public final class SplitShade {
    private SplitShade() {
    }

    private static final int NONE = 0, LEFT = 1, RIGHT = 2;
    private static int mode = NONE;
    private static float curShift;               // на сколько поднят список (режим «слева»), 0 — не поднят
    private static boolean touched, opened;      // opened — шторка в этом жесте уже открывалась (доля > 0)
    private static WeakReference<View> panelRef = new WeakReference<>(null), qsRef = new WeakReference<>(null), stackRef = new WeakReference<>(null);
    private static Object qsController;

    public static void hook(ClassLoader cl) {
        Class<?> qsc = XposedHelpers.findClassIfExists("com.android.systemui.shade.QuickSettingsControllerImpl", cl);
        Class<?> pv = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationPanelView", cl);
        Class<?> sm = XposedHelpers.findClassIfExists("com.android.systemui.shade.ShadeExpansionStateManager", cl);
        if (qsc == null || pv == null || sm == null) {
            XposedBridge.log("ntweaker: раздельная шторка — не найдено");
            return;
        }
        Hook.ctors(qsc, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                qsController = p.thisObject;
            }
        });
        Hook.ctors(pv, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                panelRef = new WeakReference<>((View) p.thisObject);
                qsRef = new WeakReference<>(null);
                stackRef = new WeakReference<>(null);
            }
        });
        // начало свайпа из строки состояния на свёрнутой шторке: сторона решает, что открыть
        XposedHelpers.findAndHookMethod(qsc, "isOpenQsEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    MotionEvent e = (MotionEvent) p.args[0];
                    if (e.getActionMasked() != MotionEvent.ACTION_DOWN || !AnimHooks.prefs().splitShade) return;
                    View panel = panelRef.get();
                    if (panel == null || locked(panel)) return;
                    int w = panel.getResources().getDisplayMetrics().widthPixels;
                    mode = e.getRawX() > w / 2f ? RIGHT : LEFT;
                    opened = false;
                    if (mode == RIGHT) p.setResult(true);
                    else noImmediateQs();
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: раздельная шторка (касание) — " + t);
                }
            }
        });
        // поднятый на shift список должен и места получить на shift больше — иначе нижние уведомления уходят в «полку»
        Class<?> nssl = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", cl);
        if (nssl != null) {
            // рамка обрезки списка (её низ посчитан для неподнятого списка) — опускаем низ на тот же сдвиг
            XposedHelpers.findAndHookMethod(nssl, "setRoundedClippingBounds", int.class, int.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (curShift > 0f) p.args[3] = (Integer) p.args[3] + Math.round(curShift);
                }
            });
            // и предел высоты раскладки — по нему подрезается последняя строка
            XposedHelpers.findAndHookMethod(nssl, "setMaxLayoutHeight", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (curShift > 0f) p.args[0] = (Integer) p.args[0] + Math.round(curShift);
                }
            });
            XposedHelpers.findAndHookMethod(nssl, "updateStackEndHeight", float.class, float.class, float.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (curShift > 0f) p.args[0] = (Float) p.args[0] + curShift;
                }
            });
        }
        Hook.all(sm, "onPanelExpansionChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    update((Float) p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: раздельная шторка — " + t);
                }
            }
        });
        XposedBridge.log("ntweaker: раздельная шторка подключена");
    }

    /** Флаг «раскрыть сразу быстрые настройки» иногда остаётся после прошлого закрытия — слева он не нужен. */
    private static void noImmediateQs() {
        try {
            if (qsController != null && (Boolean) XposedHelpers.callMethod(qsController, "isExpandImmediate")) {
                XposedHelpers.callMethod(qsController, "setExpandImmediate", false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean locked(View v) {
        return Lock.locked(v.getContext());
    }

    private static void update(float fraction) {
        View panel = panelRef.get();
        if (panel == null) return;
        if (fraction > 0.01f) opened = true;
        // режим живёт до закрытия шторки (первые события жеста приходят ещё с долей 0)
        if (fraction <= 0f && opened) { mode = NONE; opened = false; }
        if (mode == NONE || !AnimHooks.prefs().splitShade || locked(panel)) {
            restore();
            return;
        }
        View qs = qsRef.get(), stack = stackRef.get();
        if (qs == null || stack == null) {
            int id = panel.getResources().getIdentifier("qs_frame", "id", panel.getContext().getPackageName());
            qs = id != 0 ? panel.findViewById(id) : null;
            stack = find(panel.getRootView(), "NotificationStackScrollLayout", 0);
            qsRef = new WeakReference<>(qs);
            stackRef = new WeakReference<>(stack);
            if (qs == null || stack == null) return;
        }
        touched = true;
        if (mode == RIGHT) {
            // только быстрые настройки: уведомления — за экран
            qs.setTranslationY(0f);
            qs.setTransitionAlpha(1f);
            stack.setTransitionAlpha(0f);
            stack.setTranslationY(panel.getHeight() * 2f);
            return;
        }
        // слева — только уведомления; если пользователь дотянул до быстрых настроек — показать их как обычно
        boolean qsOpen = false;
        try {
            if (qsController != null) qsOpen = (Boolean) XposedHelpers.callMethod(qsController, "getExpanded");
        } catch (Throwable ignored) {
        }
        if (qsOpen) {
            restore();
            return;
        }
        if (fraction < 1f) noImmediateQs();          // пока шторка открывается — без «сразу в быстрые настройки»
        qs.setTransitionAlpha(0f);
        qs.setTranslationY(-panel.getHeight() * 2f);
        int top = 0;
        try {
            top = (Integer) XposedHelpers.callMethod(stack, "getTopPadding");
        } catch (Throwable ignored) {
        }
        int bar = 0;
        int sid = panel.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sid != 0) bar = panel.getResources().getDimensionPixelSize(sid);
        float d = panel.getResources().getDisplayMetrics().density;
        float shift = Math.max(0f, top - bar - 12f * d);
        stack.setTransitionAlpha(1f);
        stack.setTranslationY(-shift);
        if (Math.abs(shift - curShift) > 0.5f) { curShift = shift; stack.requestLayout(); }
        // поднятый список рисуется ниже своего края: ни ему, ни родителю не обрезать строки по своим границам
        if (stack instanceof ViewGroup) ((ViewGroup) stack).setClipChildren(false);
        if (stack.getParent() instanceof ViewGroup) ((ViewGroup) stack.getParent()).setClipChildren(false);
    }

    private static void restore() {
        curShift = 0f;
        if (!touched) return;
        touched = false;
        View qs = qsRef.get(), stack = stackRef.get();
        if (qs != null) { qs.setTranslationY(0f); qs.setTransitionAlpha(1f); }
        if (stack != null) {
            stack.setTranslationY(0f);
            stack.setTransitionAlpha(1f);
        }
    }

    private static View find(View v, String simpleName, int depth) {
        if (depth > 20) return null;
        if (v.getClass().getSimpleName().contains(simpleName)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), simpleName, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}
