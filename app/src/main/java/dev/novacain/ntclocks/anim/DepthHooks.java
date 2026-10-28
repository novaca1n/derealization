package dev.novacain.ntclocks.anim;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Эффект глубины Nothing под большие часы.
 * «Обои и стиль» (com.nothing.wallpaper): перекрытие объекта с часами Nothing считает по СВОИМ маленьким часам
 * (BitmapOpaqueUtil → NO_OPAQUE / SATISFIED / EXCEEDED) и из-за этого не даёт включить эффект — при нашей настройке
 * ответ всегда SATISFIED.
 * SystemUI (NTDepthEffectWallpaperController): вырезка кладётся поверх часов 1:1 со сдвигом
 * getDepthEffectWallpaperPosition. Перед показом стираем в ней область настоящих часов выше разрешённой линии
 * (объект может закрыть только нижнюю долю cover высоты часов), с мягким краем. Часы поменяли размер — пересчёт.
 */
public final class DepthHooks {
    private static volatile DepthPrefs prefs;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> controller = new WeakReference<>(null);
    private static WeakReference<View> clockView = new WeakReference<>(null);
    private static final Rect lastClock = new Rect();

    private DepthHooks() {
    }

    static DepthPrefs prefs() {
        DepthPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new DepthPrefs();
        synchronized (DepthHooks.class) {
            if (prefs == null) {
                prefs = DepthPrefs.read(app.getContentResolver());
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(DepthPrefs.KEY), false,
                        new ContentObserver(MAIN) {
                            @Override
                            public void onChange(boolean selfChange) {
                                prefs = DepthPrefs.read(app.getContentResolver());
                                refresh();
                            }
                        });
            }
            return prefs;
        }
    }

    // ───────── «Обои и стиль» ─────────

    public static void picker(ClassLoader cl) {
        Class<?> util = XposedHelpers.findClassIfExists("com.nothing.wallpaper.common.bitmap.BitmapOpaqueUtil", cl);
        Class<?> pct = XposedHelpers.findClassIfExists("com.nothing.wallpaper.common.bitmap.BitmapOpaqueUtil$OpaquePercentage", cl);
        if (util == null || pct == null) {
            XposedBridge.log("ntweaker: глубина — BitmapOpaqueUtil не найден");
            return;
        }
        Object satisfied = Enum.valueOf(pct.asSubclass(Enum.class), "SATISFIED");
        int n = 0;
        for (Method m : util.getDeclaredMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (m.getReturnType() != pct || t.length < 2 || t[0] != Bitmap.class || t[1] != Rect.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (prefs().enabled && p.getResult() != satisfied) p.setResult(satisfied);
                }
            });
            n++;
        }
        XposedBridge.log("ntweaker: глубина в «Обоях и стиле» — перехвачено " + n);
        anyWallpaper(cl);
    }

    /**
     * «Для любых обоев»: «Обои и стиль» (EditWallpaperViewModel, j7.j) пишут в поток состояния (StateFlow, I8.P)
     * DepthNotApplicable с причиной — запрет из-за стекла обоев, широкого виджета или «объект не заходит на часы».
     * Такую запись подменяем на DepthOff: переключатель эффекта становится доступным.
     */
    private static void anyWallpaper(ClassLoader cl) {
        Class<?> na = XposedHelpers.findClassIfExists("com.nothing.wallpaper.picker.lockscreen.depth.DepthEffectState$DepthNotApplicable", cl);
        Class<?> off = XposedHelpers.findClassIfExists("com.nothing.wallpaper.picker.lockscreen.depth.DepthEffectState$DepthOff", cl);
        Class<?> flow = XposedHelpers.findClassIfExists("I8.P", cl);
        if (na == null || off == null || flow == null) {
            XposedBridge.log("ntweaker: глубина для любых обоев — не найдено");
            return;
        }
        Object offState = XposedHelpers.getStaticObjectField(off, "INSTANCE");
        java.util.Set<String> lift = new java.util.HashSet<>(java.util.Arrays.asList("APPLIED_GLASS_EFFECT", "EXTENDED_WIDGET", "NO_OVERLAP"));
        XposedHelpers.findAndHookMethod(flow, "setValue", Object.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!na.isInstance(p.args[0]) || !prefs().anyWallpaper) return;
                try {
                    Object reason = XposedHelpers.callMethod(p.args[0], "getReason");
                    if (reason != null && lift.contains(((Enum<?>) reason).name())) p.args[0] = offState;
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.log("ntweaker: глубина для любых обоев подключена");
    }

    // ───────── SystemUI ─────────

    public static void systemui(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.nothing.wallpaper.NTDepthEffectWallpaperController", cl);
        if (c == null) return;
        Hook.all(c, "setFinalDepthEffectWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                controller = new WeakReference<>(p.thisObject);
                if (p.args.length < 1 || !(p.args[0] instanceof Bitmap)) return;
                DepthPrefs d = prefs();
                if (!d.enabled) return;
                try {
                    Bitmap b = clip(p.thisObject, (Bitmap) p.args[0], d.cover);
                    if (b != null) p.args[0] = b;
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: глубина — " + t);
                }
            }
        });
        // своя маска: вырезку объекта ставит SystemUI — файлу вырезки Nothing дан свой тип SELinux (depth_wallpaper_file),
        // дескриптор на него система передаёт только системным приложениям, derealization его не получит
        Hook.ctors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                controller = new WeakReference<>(p.thisObject);
                if (p.args.length > 0 && p.args[0] instanceof android.content.Context) listenCutout((android.content.Context) p.args[0]);
            }
        });
        XposedBridge.log("ntweaker: глубина в SystemUI подключена");
    }

    public static final String CUT_REQUEST = "ntweaker_depth_cut", CUT_DONE = "ntweaker_depth_cut_done";
    private static boolean listening;

    /**
     * Заявка от приложения (Settings.Global ntweaker_depth_cut): «<id обоев>:<метка>» — взять вырезку у провайдера
     * derealization и поставить её под эти обои экрана блокировки; «clear:<метка>» — убрать вырезку;
     * «shot:<метка>» — снимок часов экрана блокировки с их местом на экране (для редактора маски).
     * Ответ — ntweaker_depth_cut_done: «<метка>:ok» или «<метка>:<ошибка>».
     */
    private static synchronized void listenCutout(android.content.Context ctx) {
        if (listening) return;
        listening = true;
        android.content.ContentResolver cr = ctx.getContentResolver();
        android.os.HandlerThread th = new android.os.HandlerThread("derealization-depth");
        th.start();
        Handler bg = new Handler(th.getLooper());
        cr.registerContentObserver(Settings.Global.getUriFor(CUT_REQUEST), false, new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange) {
                String req = Settings.Global.getString(cr, CUT_REQUEST);
                if (req == null || req.indexOf(':') < 0) return;
                String what = req.substring(0, req.indexOf(':')), tag = req.substring(req.indexOf(':') + 1);
                String res;
                try {
                    res = cutout(ctx, what);
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    res = c.getClass().getSimpleName() + (c.getMessage() != null ? " " + c.getMessage() : "");
                    XposedBridge.log("ntweaker: своя маска — " + t);
                }
                Settings.Global.putString(cr, CUT_REQUEST, null);
                Settings.Global.putString(cr, CUT_DONE, tag + ":" + res);
            }
        });
    }

    private static String cutout(android.content.Context ctx, String what) throws Exception {
        android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
        int user = android.os.Process.myUserHandle().hashCode();
        if (what.equals("shot")) return clockShot(ctx);
        if (what.equals("clear")) {
            wm.getClass().getMethod("clearDepthEffectWallpaper", int.class).invoke(wm, user);
            Settings.Secure.putInt(ctx.getContentResolver(), "depth_effect_state", 0);
            return "ok";
        }
        int id = Integer.parseInt(what);
        Bitmap cut;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(
                android.net.Uri.parse("content://dev.novacain.ntclocks.prefs/depth_cutout"))) {
            cut = in != null ? android.graphics.BitmapFactory.decodeStream(in) : null;
        }
        if (cut == null) return "нет вырезки";
        // как «Обои и стиль» (WallpaperPersister): вырезка под id обоев экрана блокировки, затем depth_effect_state
        Method set = wm.getClass().getMethod("setDepthEffectWallpaperWithWallpaperType", Bitmap.class, boolean.class, int.class, int.class, int.class);
        int depthId = (Integer) set.invoke(wm, cut, true, user, android.app.WallpaperManager.FLAG_LOCK, id);
        Settings.Secure.putInt(ctx.getContentResolver(), "depth_effect_state", depthId != 0 ? 1 : 0);
        return depthId != 0 ? "ok" : "система не приняла вырезку";
    }

    /**
     * Часы экрана блокировки как есть — любые (Nothing, наши, порты crDroid), в их настоящем размере: рисуем вид часов
     * в картинку на главном потоке и отдаём провайдеру derealization PNG и место на экране (left,top,right,bottom).
     */
    private static String clockShot(android.content.Context ctx) throws Exception {
        Object ctl = controller.get();
        if (ctl == null) return "часы не найдены";
        final Object[] out = new Object[2];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        MAIN.post(() -> {
            try {
                View v = findClock(ctl);
                Rect r = v != null ? screenRect(v) : null;
                if (r != null) {
                    Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
                    v.draw(new Canvas(b));
                    out[0] = b;
                    out[1] = r;
                }
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: снимок часов — " + t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS) || out[0] == null) return "часы не найдены";
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ((Bitmap) out[0]).compress(Bitmap.CompressFormat.PNG, 100, png);
        Rect r = (Rect) out[1];
        android.os.Bundle b = new android.os.Bundle();
        b.putByteArray("png", png.toByteArray());
        ctx.getContentResolver().call(android.net.Uri.parse("content://dev.novacain.ntclocks.prefs"), "clockShot",
                r.left + "," + r.top + "," + r.right + "," + r.bottom, b);
        return "ok";
    }

    /** Часы экрана блокировки (lockscreen_clock_view) — ищем в корне окна шторки/экрана блокировки. */
    private static View findClock(Object ctl) {
        View v = clockView.get();
        if (v != null && v.isAttachedToWindow()) return v;
        try {
            Object wc = XposedHelpers.getObjectField(ctl, "notificationShadeWindowController");
            ViewGroup root = (ViewGroup) XposedHelpers.callMethod(wc, "getWindowRootView");
            if (root == null) return null;
            int id = root.getResources().getIdentifier("lockscreen_clock_view", "id", root.getContext().getPackageName());
            v = id != 0 ? root.findViewById(id) : null;
            if (v != null) {
                clockView = new WeakReference<>(v);
                // часы поменяли размер или место (растяг, сдвиг) — пересчитать вырезку
                v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                    Rect now = screenRect(view);
                    if (now != null && !now.equals(lastClock)) MAIN.postDelayed(DepthHooks::refresh, 250);
                });
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Место часов на экране по раскладке, без translation: при пробуждении экрана часы въезжают анимацией, и снимок
     * посреди неё (getLocationOnScreen) сдвигал линию обрезки на сотни пикселей.
     */
    private static Rect screenRect(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int x = 0, y = 0;
        View cur = v;
        while (true) {
            x += cur.getLeft();
            y += cur.getTop();
            if (!(cur.getParent() instanceof View)) break;
            View parent = (View) cur.getParent();
            x -= parent.getScrollX();
            y -= parent.getScrollY();
            cur = parent;
        }
        int[] win = new int[2];
        cur.getLocationOnScreen(win);                                 // окно экрана блокировки (обычно 0, 0)
        x += win[0] - Math.round(cur.getTranslationX());
        y += win[1] - Math.round(cur.getTranslationY());
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }

    /** Копия вырезки, где над часами выше линии (верх 1 − cover высоты часов) объект стёрт; край — мягкий. */
    private static Bitmap clip(Object ctl, Bitmap src, float cover) {
        View clock = findClock(ctl);
        Rect r = clock != null ? screenRect(clock) : null;
        if (r == null) return null;
        lastClock.set(r);
        Point pos = (Point) XposedHelpers.callMethod(ctl, "getDepthEffectWallpaperPosition", src);
        Rect b = new Rect(r);
        b.offset(pos.x, pos.y);                                     // экран → координаты вырезки
        float line = b.top + b.height() * (1f - cover);
        float feather = Math.max(8f, b.height() * 0.08f);
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas cv = new Canvas(out);
        Paint erase = new Paint(Paint.ANTI_ALIAS_FLAG);
        erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // выше линии − feather — стереть полностью, в полосе feather — плавно сойти на нет
        erase.setShader(new LinearGradient(0, line - feather, 0, line, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(b.left, Math.min(b.top, line - feather), b.right, line, erase);
        XposedBridge.log("ntweaker: глубина — часы " + r + ", объект закрывает до " + Math.round(cover * 100) + "% снизу");
        return out;
    }

    /** Заново взять вырезку у Nothing и наложить обрезку (новые настройки или новые размеры часов). */
    private static void refresh() {
        Object c = controller.get();
        if (c == null) return;
        new Thread(() -> {
            try {
                XposedHelpers.callMethod(c, "updateDepthEffectWallpaper");
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: глубина, обновление — " + t);
            }
        }, "ntweaker-depth").start();
    }
}
