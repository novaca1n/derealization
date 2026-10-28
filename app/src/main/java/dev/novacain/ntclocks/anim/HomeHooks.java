package dev.novacain.ntclocks.anim;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Стол и док в лаунчере Nothing:
 *  - док как в iOS 26: под значками дока — настоящее размытие обоев (LucentBlur, дырка в окне) с белым оттенком,
 *    бликом по верхней кромке и тонкой светлой обводкой; пустой док — без подложки;
 *  - подпрыгивание значка дока при запуске: значок взлетает, приложение запускается, значок пружинит на место;
 *  - подписи: как у Nothing / без подписей только у дока / без подписей; подписи шрифтом Ndot;
 *  - свайп двумя пальцами вниз по столу — быстрые настройки (просит SystemUI: у лаунчера нет EXPAND_STATUS_BAR).
 */
public final class HomeHooks {
    private HomeHooks() {
    }

    public static final String ACTION_QS = "dev.novacain.ntclocks.EXPAND_QS";
    private static final int HOTSEAT = -101, DESKTOP = -100;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile HomePrefs prefs;
    private static Field fContainer;
    private static Class<?> itemInfo, folderIcon;
    private static Typeface ndot;
    private static java.lang.ref.WeakReference<View> rootRef = new java.lang.ref.WeakReference<>(null);

    static HomePrefs prefs() {
        HomePrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new HomePrefs();
        synchronized (HomeHooks.class) {
            if (prefs == null) {
                prefs = HomePrefs.read(app.getContentResolver());
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(HomePrefs.KEY), false, new ContentObserver(MAIN) {
                    @Override
                    public void onChange(boolean selfChange) {
                        prefs = HomePrefs.read(app.getContentResolver());
                        refreshAll();
                    }
                });
            }
            return prefs;
        }
    }

    public static void launcher(ClassLoader cl) {
        Class<?> bubble = XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView", cl);
        Class<?> hotseat = XposedHelpers.findClassIfExists("com.android.launcher3.Hotseat", cl);
        Class<?> click = XposedHelpers.findClassIfExists("com.android.launcher3.touch.ItemClickHandler", cl);
        Class<?> dragLayer = XposedHelpers.findClassIfExists("com.android.launcher3.views.BaseDragLayer", cl);
        itemInfo = XposedHelpers.findClassIfExists("com.android.launcher3.model.data.ItemInfo", cl);
        folderIcon = XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderIcon", cl);
        if (bubble == null || itemInfo == null) {
            XposedBridge.log("ntweaker: стол и док — не найдено");
            return;
        }
        fContainer = XposedHelpers.findField(itemInfo, "container");

        // ── подписи ──
        Hook.all(bubble, "shouldHideAppLabel", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                int mode = prefs().labels;
                if (mode == HomePrefs.LABELS_NOTHING) return;
                int c = container((View) p.thisObject);
                // «без подписей у дока» — прячем только в доке, стол — как в настройке Nothing; «без подписей» — везде
                if (c == HOTSEAT || (c == DESKTOP && mode == HomePrefs.LABELS_NONE)) p.setResult(true);
            }
        });
        XC_MethodHook font = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                applyFont((TextView) p.thisObject);
            }
        };
        Hook.all(bubble, "applyFromWorkspaceItem", font);
        Hook.all(bubble, "applyFromApplicationInfo", font);      // меню приложений
        Hook.all(bubble, "applyLabel", font);

        // ── док-стекло ──
        // dispatchDraw объявлен только у CellLayout (Hotseat его не переопределяет, а hookAllMethods берёт лишь
        // методы самого класса) — вешаемся на родителя и отбираем док
        Class<?> cellLayout = XposedHelpers.findClassIfExists("com.android.launcher3.CellLayout", cl);
        if (hotseat != null && cellLayout != null) {
            Hook.all(cellLayout, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!hotseat.isInstance(p.thisObject)) return;
                    try {
                        dockGlass((ViewGroup) p.thisObject, (Canvas) p.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: док — " + t);
                    }
                }
            });
        }

        // ── подпрыгивание при запуске из дока ──
        if (click != null) {
            Hook.all(click, "onClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 1 || !(p.args[0] instanceof View)) return;
                    View v = (View) p.args[0];
                    if (!prefs().bounce || container(v) != HOTSEAT || folderIcon.isInstance(v)) return;
                    if (XposedHelpers.getAdditionalInstanceField(v, "ntBounce") != null) return;   // уже летит — запуск штатно
                    XposedHelpers.setAdditionalInstanceField(v, "ntBounce", true);
                    p.setResult(null);
                    bounce(v, p.method);
                }
            });
        }

        // ── два пальца вниз — быстрые настройки ──
        if (dragLayer != null) {
            Hook.all(dragLayer, "dispatchTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (twoFinger((View) p.thisObject, (MotionEvent) p.args[0])) p.setResult(true);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: два пальца — " + t);
                    }
                }
            });
        }
        XposedBridge.log("ntweaker: стол и док подключены");
    }

    /** Контейнер значка (стол −100, док −101; у значка в папке — номер папки). */
    private static int container(View v) {
        Object tag = v.getTag();
        if (!itemInfo.isInstance(tag)) return 0;
        try {
            return fContainer.getInt(tag);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Typeface ndot() {
        if (ndot == null) {
            try {
                java.io.File f = new java.io.File("/system/fonts/Ndot-57.otf");
                ndot = f.exists() ? Typeface.createFromFile(f) : Typeface.DEFAULT;
            } catch (Throwable t) {
                ndot = Typeface.DEFAULT;
            }
        }
        return ndot;
    }

    private static void applyFont(TextView v) {
        rootRef = new java.lang.ref.WeakReference<>(v.getRootView());
        int c = container(v);
        // стол, док, папки (номер папки > 0) и меню приложений (−104)
        if (c != DESKTOP && c != HOTSEAT && c != -104 && c <= 0) return;
        Object orig = XposedHelpers.getAdditionalInstanceField(v, "ntFont");
        if (orig == null) XposedHelpers.setAdditionalInstanceField(v, "ntFont", orig = v.getTypeface() != null ? v.getTypeface() : Typeface.DEFAULT);
        Typeface want = prefs().ndot ? ndot() : (Typeface) orig;
        if (v.getTypeface() != want) v.setTypeface(want);
    }

    /** Новые настройки — пройти по виду лаунчера: шрифт, подписи, док. */
    private static void refreshAll() {
        View root = rootRef.get();
        if (root != null) walk(root);
    }

    private static void walk(View v) {
        if (v instanceof TextView && v.getClass().getName().equals("com.android.launcher3.BubbleTextView")) {
            applyFont((TextView) v);
            try {
                float a = XposedHelpers.getFloatField(v, "mTextAlpha");
                XposedHelpers.callMethod(v, "setTextAlpha", a == 0f ? 1f : a);
            } catch (Throwable ignored) {
            }
            v.requestLayout();
        }
        if (v.getClass().getName().equals("com.android.launcher3.Hotseat")) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ───────── док ─────────

    private static final RectF R = new RectF();
    private static LinearGradient rim;
    private static float rimTop, rimBottom;
    private static final float[] RADII = new float[4];
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static void dockGlass(ViewGroup hotseat, Canvas c) {
        HomePrefs hp = prefs();
        ViewGroup items = (ViewGroup) XposedHelpers.callMethod(hotseat, "getShortcutsAndWidgets");
        // рамка по видимым значкам (в координатах дока)
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < items.getChildCount(); i++) {
            View ch = items.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE) continue;
            float x = items.getLeft() + ch.getLeft(), y = items.getTop() + ch.getTop();
            l = Math.min(l, x); t = Math.min(t, y); r = Math.max(r, x + ch.getWidth()); b = Math.max(b, y + ch.getHeight());
            n++;
        }
        if (!hp.dockGlass || n == 0 || !c.isHardwareAccelerated()) {
            LucentBlur.clear(hotseat);
            return;
        }
        float d = hotseat.getResources().getDisplayMetrics().density;
        float padX = 10 * d, padY = 6 * d;
        R.set(Math.max(8 * d, l - padX), t - padY, Math.min(hotseat.getWidth() - 8 * d, r + padX), b + padY);
        float rad = Math.min(R.height() * 0.42f, 34 * d);
        float[] radii = RADII;
        radii[0] = radii[1] = radii[2] = radii[3] = rad;
        // док — над обоями: статичные обои размываем сами (без нагрузки на композитор), живые — системным размытием
        if (LucentBlur.wallpaper(c, hotseat, R, radii, Math.round(hp.dockBlur * d)) || !LucentBlur.wallpaperLive()) LucentBlur.clear(hotseat);
        else LucentBlur.drawShape(c, hotseat, hotseat, R, radii, Math.round(hp.dockBlur * d));
        // оттенок стекла
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(android.graphics.Color.argb(Math.round(hp.dockTint * 255), 255, 255, 255));
        c.drawRoundRect(R, rad, rad, P);
        // блик: светлая кромка сверху, гаснет к низу (жидкое стекло)
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, 1.2f * d));
        if (rim == null || rimTop != R.top || rimBottom != R.bottom) {     // градиент кромки — только при смене размера
            rim = new LinearGradient(0, R.top, 0, R.bottom, 0x8CFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP);
            rimTop = R.top;
            rimBottom = R.bottom;
        }
        P.setShader(rim);
        R.inset(P.getStrokeWidth() / 2, P.getStrokeWidth() / 2);
        c.drawRoundRect(R, rad, rad, P);
        P.setShader(null);
        LucentBlur.grain(c, 0f);
    }

    // ───────── подпрыгивание ─────────

    private static void bounce(View v, java.lang.reflect.Member onClick) {
        float d = v.getResources().getDisplayMetrics().density;
        v.animate().cancel();
        v.animate().translationY(-16 * d).setDuration(120).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    try {
                        XposedBridge.invokeOriginalMethod(onClick, null, new Object[]{v});
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: запуск из дока — " + t);
                    }
                    v.animate().translationY(0f).setDuration(420).setInterpolator(new OvershootInterpolator(2.2f))
                            .withEndAction(() -> XposedHelpers.removeAdditionalInstanceField(v, "ntBounce")).start();
                }).start();
    }

    // ───────── два пальца вниз ─────────

    private static final boolean DEBUG = false;           // true — касания двумя пальцами в лог
    private static float startY;
    private static boolean tracking, fired;

    private static boolean twoFinger(View layer, MotionEvent e) {
        if (!prefs().twoFingerQs) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = false;
                fired = false;
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (DEBUG) XposedBridge.log("ntweaker: два пальца — " + e.getPointerCount() + " на столе " + onHome(layer) + " " + layer.getClass().getName());
                if (e.getPointerCount() == 2 && onHome(layer)) {
                    tracking = true;
                    startY = (e.getY(0) + e.getY(1)) / 2f;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking || fired || e.getPointerCount() < 2) return fired;
                float y = (e.getY(0) + e.getY(1)) / 2f;
                if (y - startY > 70 * layer.getResources().getDisplayMetrics().density) {
                    fired = true;
                    // остальным — отмена: стол не листается, значки не берутся
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try {
                        XposedBridge.invokeOriginalMethod(XposedHelpers.findMethodBestMatch(layer.getClass(), "dispatchTouchEvent", MotionEvent.class), layer, new Object[]{cancel});
                    } catch (Throwable ignored) {
                    }
                    cancel.recycle();
                    expandQs(layer.getContext());
                }
                return fired;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = fired;
                tracking = false;
                fired = false;
                return was;
            default:
                return fired;
        }
    }

    /** Стол в обычном состоянии, без открытых папок и меню. */
    private static boolean onHome(View layer) {
        try {
            Context ctx = layer.getContext();
            ClassLoader cl = layer.getClass().getClassLoader();
            Object launcher = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.launcher3.Launcher", cl), "getLauncher", ctx);
            // состояние стола: NORMAL — LauncherState с ordinal 0 (статическое поле NORMAL рефлексией не достаётся)
            Object state = XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getStateManager"), "getState");
            if (state == null || XposedHelpers.getIntField(state, "ordinal") != 0) return false;
            Object top = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.launcher3.AbstractFloatingView", cl), "getTopOpenView", launcher);
            return top == null;
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log("ntweaker: два пальца, состояние — " + t);
            return false;
        }
    }

    private static void expandQs(Context ctx) {
        ctx.sendBroadcast(new Intent(ACTION_QS).setPackage("com.android.systemui"));
    }
}
