package dev.novacain.ntclocks.anim;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Прозрачные папки и виджеты (как Lucent в Nothing OS 5). Папки: фон рисует PreviewBackground (и у больших —
 * «обложка»), цвет — поле mBgColor на время отрисовки (drawBackground) → убавляем альфу, если папка выбрана. Виджеты: у фонов (Drawable) корневых
 * элементов внутри хоста убавляем альфу перед каждой отрисовкой (виджет при обновлении ставит фон заново).
 * Список папок и виджетов рабочего стола лаунчер отдаёт приложению (провайдер, метод homeItems) при каждом
 * возвращении на рабочий стол — по нему в приложении выбирают, что сделать прозрачным.
 */
public final class LucentHooks {
    private static volatile LucentPrefs prefs;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> launcher = new WeakReference<>(null);
    private static Class<?>[] hosts = new Class<?>[0];
    private static String lastList = "";
    // переиспользуемые объекты для отрисовки (только UI-поток) — без мусора в каждом кадре
    private static final android.graphics.Outline OUTLINE = new android.graphics.Outline();
    private static final android.graphics.Rect RECT = new android.graphics.Rect();
    private static final android.graphics.Path PATH = new android.graphics.Path();
    private static final RectF RECTF = new RectF(), FOLDER_R = new RectF();

    private LucentHooks() {
    }

    static LucentPrefs prefs() {
        LucentPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new LucentPrefs();
        synchronized (LucentHooks.class) {
            if (prefs == null) {
                prefs = LucentPrefs.read(app.getContentResolver());
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(LucentPrefs.KEY), false,
                        new ContentObserver(MAIN) {
                            @Override
                            public void onChange(boolean selfChange) {
                                prefs = LucentPrefs.read(app.getContentResolver());
                                Activity l = launcher.get();
                                if (l != null) invalidateAll(l.getWindow().getDecorView());
                            }
                        });
            }
            return prefs;
        }
    }

    /** id элемента рабочего стола у вида (тег — ItemInfo). */
    static int itemId(View v) {
        Object tag = v != null ? v.getTag() : null;
        if (tag == null) return -1;
        try {
            return XposedHelpers.getIntField(tag, "id");
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Прозрачна ли папка, которой принадлежит открытая панель (Folder). */
    private static boolean lucentFolder(Object folder) {
        LucentPrefs l = prefs();
        if (!l.enabled) return false;
        Object icon = XposedHelpers.getObjectField(folder, "mFolderIcon");
        return icon instanceof View && l.applies(itemId((View) icon));
    }

    /**
     * Панель открытой папки вырастает из значка и в него же сжимается. Лаунчер ведёт её цвет от цвета значка к своему
     * — подставляем на это время цвет значка с нашей альфой, а под панель кладём то же размытие обоев: иначе в конце
     * закрытия глухая панель скачком сменяется полупрозрачным значком.
     */
    private static void hookFolderPanel(ClassLoader cl) {
        Class<?> anim = XposedHelpers.findClassIfExists("com.android.launcher3.folder.NTFolderAnimationManager", cl);
        Class<?> folder = XposedHelpers.findClassIfExists("com.android.launcher3.folder.Folder", cl);
        if (anim == null || folder == null) return;
        Hook.all(anim, "initFolderBackgroundAnimator", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object f = XposedHelpers.getObjectField(p.thisObject, "folder");
                if (!lucentFolder(f)) return;
                Object bg = XposedHelpers.callMethod(XposedHelpers.getObjectField(p.thisObject, "folderIcon"), "getFolderBackground");
                int c = XposedHelpers.getIntField(bg, "mBgColor");
                XposedHelpers.setAdditionalInstanceField(p.thisObject, "ntColor", c);
                XposedHelpers.setIntField(bg, "mBgColor", Color.argb(Math.round(Color.alpha(c) * prefs().opacity), Color.red(c), Color.green(c), Color.blue(c)));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object c = XposedHelpers.removeAdditionalInstanceField(p.thisObject, "ntColor");
                if (c == null) return;
                Object bg = XposedHelpers.callMethod(XposedHelpers.getObjectField(p.thisObject, "folderIcon"), "getFolderBackground");
                XposedHelpers.setIntField(bg, "mBgColor", (Integer) c);
            }
        });
        // фон панели — свой Drawable лаунчера (имя класса обфусцировано): берём тип поля Folder.mBackground и его Paint
        Class<?> panel = XposedHelpers.findField(folder, "mBackground").getType();
        java.lang.reflect.Field paintF = null;
        for (java.lang.reflect.Field f : panel.getDeclaredFields())
            if (f.getType() == android.graphics.Paint.class) paintF = f;
        if (paintF == null) return;
        paintF.setAccessible(true);
        java.lang.reflect.Field pf = paintF;
        Hook.all(panel, "draw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Drawable d = (Drawable) p.thisObject;
                if (!(d.getCallback() instanceof View)) return;
                View owner = (View) d.getCallback();
                LucentPrefs l = prefs();
                if (!l.blur || !lucentFolder(owner)) return;
                android.graphics.Paint paint = (android.graphics.Paint) pf.get(d);
                if (paint.getAlpha() == 255) return;              // панель раскрылась — под ней ничего не видно
                android.graphics.Shader sh = LucentBlur.wallpaperShader(owner, Math.round(l.blurRadius * owner.getResources().getDisplayMetrics().density));
                if (sh == null) return;
                // тот же прямоугольник и радиус, что у панели: рисуем её же методом, но кистью с обоями
                int color = paint.getColor();
                paint.setShader(sh);
                paint.setColor(0xFFFFFFFF);
                try {
                    XposedBridge.invokeOriginalMethod(p.method, d, p.args);     // мимо хуков — без рекурсии
                } finally {
                    paint.setShader(null);
                    paint.setColor(color);
                }
            }
        });
    }

    public static void hook(ClassLoader cl) {
        // папки: фон PreviewBackground
        Class<?> preview = XposedHelpers.findClassIfExists("com.android.launcher3.folder.PreviewBackground", cl);
        // папки: настоящее размытие под фоном — до того, как PreviewBackground зальёт его полупрозрачным цветом
        if (preview != null) {
            Hook.all(preview, "drawBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    LucentPrefs l = prefs();
                    Object bg = p.thisObject;
                    View owner = (View) XposedHelpers.getObjectField(bg, "mInvalidateDelegate");
                    boolean on = l.enabled && owner != null && l.applies(itemId(owner));
                    // прозрачность фона: поле mBgColor на время этой отрисовки. Хук на геттер getBgColor ненадёжен —
                    // он однострочный, ART встраивает его в drawBackground, и подмена то срабатывала, то нет
                    if (on) {
                        int c = XposedHelpers.getIntField(bg, "mBgColor");
                        XposedHelpers.setAdditionalInstanceField(bg, "ntColor", c);
                        XposedHelpers.setIntField(bg, "mBgColor", Color.argb(Math.round(Color.alpha(c) * l.opacity), Color.red(c), Color.green(c), Color.blue(c)));
                    }
                    if (!on || !l.blur) {
                        LucentBlur.clear(bg);
                        return;
                    }
                    try {
                        float x = (Integer) XposedHelpers.callMethod(bg, "getOffsetX");
                        float y = (Integer) XposedHelpers.callMethod(bg, "getOffsetY");
                        float size = 2f * (Integer) XposedHelpers.callMethod(bg, "getScaledRadius");
                        float corner = (Float) XposedHelpers.callMethod(bg, "getCornerRadius") * XposedHelpers.getFloatField(bg, "mScale");
                        FOLDER_R.set(x, y, x + size, y + size);
                        LucentBlur.draw((android.graphics.Canvas) p.args[0], bg, owner, FOLDER_R, corner, l);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: размытие папки — " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object c = XposedHelpers.removeAdditionalInstanceField(p.thisObject, "ntColor");
                    if (c != null) XposedHelpers.setIntField(p.thisObject, "mBgColor", (Integer) c);     // исходный цвет назад
                }
            });
        }
        hookFolderPanel(cl);
        // виджеты: хосты AOSP и карточек Nothing
        java.util.List<Class<?>> h = new java.util.ArrayList<>();
        for (String n : new String[]{"com.android.launcher3.widget.LauncherAppWidgetHostView", "com.nothing.launcher.card.BaseLauncherCardHostView"}) {
            Class<?> c = XposedHelpers.findClassIfExists(n, cl);
            if (c != null) h.add(c);
        }
        hosts = h.toArray(new Class<?>[0]);
        XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", android.graphics.Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                float sg = GlassHooks.sheetFrame(v);
                if (sg >= 0f) {
                    // открытая папка меню: зерно на весь экран под содержимым
                    if (sg > 0f) LucentBlur.grain((android.graphics.Canvas) p.args[0], sg);
                    return;
                }
                Object grain = GlassHooks.GRAIN.isEmpty() ? null : GlassHooks.GRAIN.get(v);
                if (grain == null) {
                    float dg = GlassHooks.drawerGlass(v);
                    if (dg > 0f) grain = dg;
                }
                if (grain != null && (Float) grain > 0f && v.getBackground() != null) {
                    // панель поиска меню (GlassHooks.searchBg): зерно по форме её фона
                    android.graphics.Outline o = OUTLINE;
                    android.graphics.Rect r = RECT;
                    o.setEmpty();
                    v.getBackground().getOutline(o);
                    if (o.getRect(r)) {
                        android.graphics.Canvas c = (android.graphics.Canvas) p.args[0];
                        android.graphics.Path path = PATH;
                        path.reset();
                        RECTF.set(r);
                        path.addRoundRect(RECTF, o.getRadius(), o.getRadius(), android.graphics.Path.Direction.CW);
                        int save = c.save();
                        c.clipPath(path);
                        LucentBlur.grain(c, (Float) grain);
                        c.restoreToCount(save);
                    }
                    return;
                }
                for (Class<?> c : hosts) {
                    if (c.isInstance(v)) {
                        LucentPrefs l = prefs();
                        int id = itemId(v);
                        int alpha = l.applies(id) ? Math.round(255 * l.opacity) : 255;
                        Object last = XposedHelpers.getAdditionalInstanceField(v, "ntLucent");
                        if (alpha == 255 && last == null) return;
                        XposedHelpers.setAdditionalInstanceField(v, "ntLucent", alpha);
                        fade((ViewGroup) v, alpha, 0);
                        blurWidget((ViewGroup) v, (android.graphics.Canvas) p.args[0], l.blur && alpha != 255, l);
                        return;
                    }
                }
            }
        });
        // список папок и виджетов — при каждом возвращении на рабочий стол
        Class<?> l = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        if (l != null) {
            Hook.all(l, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    launcher = new WeakReference<>(a);
                    prefs();
                    MAIN.postDelayed(() -> publish(a), 600);
                }
            });
        }
        XposedBridge.log("ntweaker: прозрачные папки и виджеты подключены (хостов " + hosts.length + ")");
    }

    /** Фоны внутри виджета (до 3 уровней) — с нужной альфой; Drawable сначала mutate, чтобы не задеть общие. */
    private static void fade(ViewGroup g, int alpha, int depth) {
        if (depth > 3) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            Drawable bg = c.getBackground();
            if (bg != null && bg.getAlpha() != alpha) {
                if (XposedHelpers.getAdditionalInstanceField(bg, "ntMut") == null) {
                    bg = bg.mutate();
                    XposedHelpers.setAdditionalInstanceField(bg, "ntMut", true);
                }
                bg.setAlpha(alpha);
            }
            if (c instanceof ViewGroup) fade((ViewGroup) c, alpha, depth + 1);
        }
    }

    /**
     * Размытие под «карточками» виджета: верхние (не вложенные друг в друга) виды с фоном-скруглённым прямоугольником
     * или кругом размером от 10 % виджета. Картинки и прочие фоны непонятной формы не трогаем — там была бы
     * размытая прямоугольная «плашка» за краями.
     */
    private static void blurWidget(ViewGroup host, android.graphics.Canvas c, boolean on, LucentPrefs l) {
        java.util.ArrayList<View> cards = new java.util.ArrayList<>();
        if (on) cards(host, host.getWidth() * host.getHeight(), cards, 0);
        Object prev = XposedHelpers.getAdditionalInstanceField(host, "ntCards");
        if (prev != null) {
            for (Object o : (java.util.List<?>) prev) {
                View v = ((WeakReference<View>) o).get();
                if (v != null && !cards.contains(v)) LucentBlur.clear(v);
            }
        }
        java.util.ArrayList<WeakReference<View>> keep = new java.util.ArrayList<>();
        android.graphics.Outline o = new android.graphics.Outline();
        android.graphics.Rect rr = new android.graphics.Rect();
        for (View v : cards) {
            keep.add(new WeakReference<>(v));
            Drawable bg = v.getBackground();
            o.setEmpty();
            bg.getOutline(o);
            if (!o.getRect(rr)) continue;
            float x = rr.left, y = rr.top;
            View cur = v;
            while (cur != host && cur != null) {
                x += cur.getLeft() + cur.getTranslationX() - (cur.getParent() == host ? 0 : ((View) cur.getParent()).getScrollX());
                y += cur.getTop() + cur.getTranslationY() - (cur.getParent() == host ? 0 : ((View) cur.getParent()).getScrollY());
                cur = (View) cur.getParent();
            }
            LucentBlur.draw(c, v, v, host, new RectF(x, y, x + rr.width(), y + rr.height()), o.getRadius(), l);
        }
        XposedHelpers.setAdditionalInstanceField(host, "ntCards", keep);
    }

    private static void cards(ViewGroup g, int hostArea, java.util.List<View> out, int depth) {
        if (depth > 3 || hostArea <= 0) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            Drawable bg = c.getBackground();
            if (bg != null && c.getWidth() * c.getHeight() >= hostArea / 10 && roundShape(bg)) {
                out.add(c);
                continue;
            }
            if (c instanceof ViewGroup) cards((ViewGroup) c, hostArea, out, depth + 1);
        }
    }

    /** Фон с понятной формой: заливка/фигура (GradientDrawable, ColorDrawable, их обёртки) и контур — скруглённый прямоугольник. */
    private static boolean roundShape(Drawable d) {
        Drawable cur = d;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur instanceof android.graphics.drawable.GradientDrawable || cur instanceof android.graphics.drawable.ColorDrawable
                    || cur instanceof android.graphics.drawable.ShapeDrawable) break;
            if (cur instanceof android.graphics.drawable.LayerDrawable && ((android.graphics.drawable.LayerDrawable) cur).getNumberOfLayers() > 0) {
                cur = ((android.graphics.drawable.LayerDrawable) cur).getDrawable(0);
            } else if (cur instanceof android.graphics.drawable.DrawableWrapper) {
                cur = ((android.graphics.drawable.DrawableWrapper) cur).getDrawable();
            } else if (cur instanceof android.graphics.drawable.DrawableContainer) {
                cur = cur.getCurrent() == cur ? null : cur.getCurrent();
            } else {
                return false;
            }
        }
        if (cur == null) return false;
        android.graphics.Outline o = new android.graphics.Outline();
        d.getOutline(o);
        return o.getRect(new android.graphics.Rect());
    }

    private static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }

    /** Папки и виджеты рабочего стола → приложению (провайдер ntweaker, метод homeItems). */
    private static void publish(Activity a) {
        try {
            Object ws = XposedHelpers.callMethod(a, "getWorkspace");
            ViewGroup pages = (ViewGroup) ws;
            JSONArray list = new JSONArray();
            PackageManager pm = a.getPackageManager();
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup items = (ViewGroup) XposedHelpers.callMethod(pages.getChildAt(i), "getShortcutsAndWidgets");
                for (int j = 0; j < items.getChildCount(); j++) {
                    View v = items.getChildAt(j);
                    Object info = v.getTag();
                    if (info == null) continue;
                    String kind = info.getClass().getSimpleName();
                    boolean folder = kind.contains("Folder");
                    boolean widget = false;
                    for (Class<?> c : hosts) widget |= c.isInstance(v);
                    if (!folder && !widget) continue;
                    String title = null;
                    if (folder) {
                        Object t = XposedHelpers.getObjectField(info, "title");
                        title = t != null && t.toString().length() > 0 ? t.toString() : "Папка";
                    } else {
                        try {
                            ComponentName cn = (ComponentName) XposedHelpers.callMethod(info, "getTargetComponent");
                            if (cn != null) title = pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0)).toString();
                        } catch (Throwable ignored) {
                        }
                        if (title == null) title = "Виджет";
                    }
                    list.put(new JSONObject().put("id", itemId(v)).put("type", folder ? "folder" : "widget")
                            .put("title", title).put("page", i + 1));
                }
            }
            String json = list.toString();
            if (json.equals(lastList)) return;
            lastList = json;
            a.getContentResolver().call(Uri.parse("content://dev.novacain.ntclocks.prefs"), "homeItems", json, null);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: список рабочего стола — " + t);
        }
    }
}
