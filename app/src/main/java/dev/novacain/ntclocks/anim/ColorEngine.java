package dev.novacain.ntclocks.anim;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.om.FabricatedOverlay;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.TypedValue;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Движок адаптивных цветов (внутри SystemUI). Палитры Material You система уже считает из обоев в
 * ThemeOverlayController.createOverlays (mDarkColorScheme / mLightColorScheme). После этого мы собираем свои
 * fabricated overlay для пакетов Nothing: каждый серый цвет из ColorMap (system_neutral1_<тон>) заменяется тем же
 * тоном другой палитры — акцент (accent1), тонированный серый (neutral2), значки (accent2/accent1). Контраст Nothing
 * сохраняется, меняется оттенок. Отдельно для дня (конфигурация "") и ночи ("night"). Смена обоев → система заново
 * считает палитры → мы заново перекрашиваем. Выключено — свои наложения снимаем.
 */
public final class ColorEngine {
    private static final String PREFIX = "ntweaker_color_";
    /** Отпечаток цветов виджетов: лаунчер перечитывает ресурсы виджетов только при запуске — меняется отпечаток, он перезапускается. */
    public static final String WIDGETS_STAMP = "ntweaker_color_widgets";
    private static final int[] SHADES = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
    private static volatile Object controller;
    private static Handler bg;
    private static volatile ColorPrefs prefs;

    private ColorEngine() {
    }

    public static void hook(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.android.systemui.theme.ThemeOverlayController", cl);
        if (c == null) {
            XposedBridge.log("derealization: ThemeOverlayController not found — colors untouched");
            return;
        }
        Hook.all(c, "createOverlays", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                controller = p.thisObject;
                schedule();
            }
        });
        XposedBridge.log("derealization: color engine hooked");
    }

    private static synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("ntweaker-color");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    private static ColorPrefs prefs() {
        ColorPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new ColorPrefs();
        synchronized (ColorEngine.class) {
            if (prefs == null) {
                prefs = ColorPrefs.read(app.getContentResolver());
                app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ColorPrefs.KEY), false,
                        new ContentObserver(bg()) {
                            @Override
                            public void onChange(boolean selfChange) {
                                prefs = ColorPrefs.read(app.getContentResolver());
                                schedule();
                            }
                        });
            }
            return prefs;
        }
    }

    private static void schedule() {
        Handler h = bg();
        h.removeCallbacksAndMessages(null);
        h.postDelayed(ColorEngine::apply, 150);          // createOverlays зовут несколько раз подряд — применяем один раз
    }

    // ───────── тоны и палитры ─────────

    private static int index(int shade) {
        for (int i = 0; i < SHADES.length; i++) if (SHADES[i] == shade) return i;
        return -1;
    }

    private static int clampIdx(int i, int lo, int hi) {
        return Math.max(index(lo), Math.min(index(hi), i));
    }

    /** Область настроек для роли (виджетные пакеты целиком — «виджеты»). */
    private static String area(String role, boolean widget) {
        switch (role) {
            case "ACCENT": case "WON": return "active";
            case "NOTIF": return "notif";
            case "ICON_BG": case "ICON_FG": return "icons";
            case "WBG": case "WFG": return "widgets";
            default: return widget ? "widgets" : "second";
        }
    }

    /** Знаки/цифры на значках и виджетах — цветом «включённого», чтобы всё было одной гаммы. */
    private static boolean foreground(String role) {
        return "ICON_FG".equals(role) || "WFG".equals(role);
    }

    /**
     * Тон: у акцентов (и знаков) — в насыщенной части палитры 100…800, «насыщеннее» — шаг к 500; у подложек —
     * исходная светлота Nothing (с крайних белого/чёрного сдвигаем к 50/900, иначе цвета не видно). Потом — тон
     * области: плюс — светлее, минус — темнее.
     */
    private static int shade(String role, int src, boolean vivid, int tone) {
        int i = index(src);
        if (i < 0) return src;
        if ("ACCENT".equals(role) || foreground(role)) {
            i = clampIdx(i, 100, 800);
            if (vivid) i += i < index(500) ? 1 : -1;
        } else if ("ICON_BG".equals(role) || "WBG".equals(role)) {
            // фоны значков и виджетов — насыщенная часть палитры; от папки/плитки их отделяет separate (двигает контейнер)
            i = clampIdx(i, 100, 800);
        } else if (!"WON".equals(role)) {
            i = clampIdx(i, 50, 900);
        }
        i -= tone;
        return SHADES[Math.max(0, Math.min(SHADES.length - 1, i))];
    }

    private static String palette(String id) {
        switch (id) {
            case "a2": return "getAccent2";
            case "a3": return "getAccent3";
            case "n2": return "getNeutral2";
            case "n1": return "getNeutral1";
            default: return "getAccent1";
        }
    }

    @SuppressWarnings("unchecked")
    private static int color(Object scheme, String palette, int shade) {
        Object pal = XposedHelpers.callMethod(scheme, palette);
        Map<Integer, Integer> m = (Map<Integer, Integer>) XposedHelpers.getObjectField(pal, "allShadesMapped");
        Integer c = m.get(shade);
        return c != null ? (0xFF000000 | c) : 0;
    }

    /** Сила цвета: смесь цвета палитры с исходным серым Nothing (neutral1 той же светлоты). */
    private static int mix(Object scheme, String pal, int shade, int src, float strength) {
        int c = color(scheme, pal, shade);
        if (strength >= 0.999f) return c;
        int g = color(scheme, "getNeutral1", src);
        return android.graphics.Color.rgb(
                Math.round(android.graphics.Color.red(g) + (android.graphics.Color.red(c) - android.graphics.Color.red(g)) * strength),
                Math.round(android.graphics.Color.green(g) + (android.graphics.Color.green(c) - android.graphics.Color.green(g)) * strength),
                Math.round(android.graphics.Color.blue(g) + (android.graphics.Color.blue(c) - android.graphics.Color.blue(g)) * strength));
    }

    /** Строка перекраски: исходные тоны Nothing, выбранные тоны и итоговые цвета днём и ночью. */
    private static final class Entry {
        final String pkg, name, role, pal;
        final boolean widget;
        final float strength;
        final int day, night;
        int dayShade, nightShade, cd, cn;
        double ld, ln;      // светлота L* итоговых цветов (кэш для separate)
        int moves;          // сколько ступеней уже сдвинут (не больше двух — без ухода в чёрное)

        Entry(String pkg, String name, String role, boolean widget, String pal, float strength, int day, int night) {
            this.pkg = pkg;
            this.name = name;
            this.role = role;
            this.widget = widget;
            this.pal = pal;
            this.strength = strength;
            this.day = day;
            this.night = night;
        }

        /** Контейнер (папка, плитка, карточка, подложка) — его и двигаем, а значки и виджеты на нём не трогаем. */
        boolean container() {
            return "SURFACE".equals(role) || "NOTIF".equals(role);
        }

        boolean background() {
            return "SURFACE".equals(role) || "ICON_BG".equals(role) || "WBG".equals(role) || "NOTIF".equals(role);
        }
    }

    /** Светлота L* (0…100) — как её видит глаз. */
    private static double lstar(int c) {
        double[] v = {android.graphics.Color.red(c) / 255.0, android.graphics.Color.green(c) / 255.0, android.graphics.Color.blue(c) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.04045 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        double y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    private static final double MIN_DL = 4.0;

    /**
     * Защита от слияния слоёв: фоны с разной исходной ступенью Nothing (папка и значок, плитка и виджет…) после
     * палитры, тона и силы могли получить почти одну светлоту. Тогда контейнер (папку, плитку) уводим на ступень
     * темнее ночью и светлее днём, пока разница не станет ≥ 4 L*; значки и виджеты остаются насыщенными.
     */
    private static void separate(java.util.List<Entry> list, Object scheme, boolean night) {
        java.util.List<Entry> bg = new java.util.ArrayList<>();
        for (Entry e : list) {
            if (!e.background() || (night ? e.night : e.day) < 0) continue;
            if (night) e.ln = lstar(e.cn); else e.ld = lstar(e.cd);
            e.moves = 0;
            bg.add(e);
        }
        for (int pass = 0; pass < 4; pass++) {
            boolean moved = false;
            for (Entry e : bg) {
                int orig = night ? e.night : e.day;
                double le = night ? e.ln : e.ld;
                for (Entry o : bg) {
                    int oo = night ? o.night : o.day;
                    // слои лежат друг на друге только внутри одного приложения (папка и значок — в лаунчере)
                    if (o == e || oo == orig || !o.pkg.equals(e.pkg)) continue;
                    if (Math.abs(le - (night ? o.ln : o.ld)) >= MIN_DL) continue;
                    // двигаем контейнер (папку, плитку), а не значок/виджет на нём; если оба одного рода — исходно более тёмный
                    boolean mine = e.container() && (!o.container() || orig > oo);   // значки и виджеты не двигаем никогда
                    if (!mine || e.moves >= 2) continue;
                    e.moves++;
                    int idx = index(night ? e.nightShade : e.dayShade);
                    int next = idx + (night ? 1 : -1);
                    if (idx < 0 || next < 0 || next >= SHADES.length) break;
                    if (night) { e.nightShade = SHADES[next]; e.cn = mix(scheme, e.pal, e.nightShade, e.night, e.strength); e.ln = lstar(e.cn); }
                    else { e.dayShade = SHADES[next]; e.cd = mix(scheme, e.pal, e.dayShade, e.day, e.strength); e.ld = lstar(e.cd); }
                    moved = true;
                    break;
                }
            }
            if (!moved) break;
        }
    }

    // ───────── применение ─────────

    private static void apply() {
        Application app = AndroidAppHelper.currentApplication();
        Object ctl = controller;
        if (app == null || ctl == null) return;
        ColorPrefs p = prefs();
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            Object dark = XposedHelpers.getObjectField(ctl, "mDarkColorScheme");
            Object light = XposedHelpers.getObjectField(ctl, "mLightColorScheme");
            Map<String, FabricatedOverlay> overlays = new LinkedHashMap<>();
            Map<String, Integer> count = new LinkedHashMap<>();
            StringBuilder stamp = new StringBuilder();
            if (p.enabled && dark != null && light != null) {
                // 1) цвета всех строк
                java.util.List<Entry> list = new java.util.ArrayList<>();
                for (String[] r : ColorMap.ROWS) {
                    String pkg = r[0], role = r[2];
                    // виджеты: свои пакеты (часы, погода) и виджеты SystemUI на рабочем столе (Modes) — роли W*
                    boolean widget = role.startsWith("W") || (!"com.android.systemui".equals(pkg) && !"com.nothing.launcher".equals(pkg));
                    ColorPrefs.Area ar = p.area(area(role, widget));
                    if (ar == null || !ar.on) continue;
                    ColorPrefs.Area tint = foreground(role) || "WON".equals(role) ? p.area("active")
                            // фон виджетов — под цвет значков приложений (та же палитра, тон и сила)
                            : "WBG".equals(role) && p.area("icons").on ? p.area("icons") : ar;
                    Entry e = new Entry(pkg, r[1], role, widget, palette(tint.palette), tint.strength,
                            Integer.parseInt(r[3]), Integer.parseInt(r[4]));
                    if (e.day >= 0) { e.dayShade = shade(role, e.day, p.vivid, tint.tone); e.cd = mix(light, e.pal, e.dayShade, e.day, e.strength); }
                    if (e.night >= 0) { e.nightShade = shade(role, e.night, p.vivid, tint.tone); e.cn = mix(dark, e.pal, e.nightShade, e.night, e.strength); }
                    list.add(e);
                }
                // 2) фоны, которые у Nothing были разными ступенями, не должны слиться (папка ↔ значок, плитка ↔ виджет…)
                separate(list, light, false);
                separate(list, dark, true);
                // 3) наложения
                for (Entry e : list) {
                    FabricatedOverlay fo = overlays.get(e.pkg);
                    if (fo == null) {
                        // владелец — сам SystemUI, как у ThemeOverlayController (публичный конструктор оставляет его
                        // пустым, и OverlayManager отказывает: «UID does not own packageName»)
                        Class<?> builder = Class.forName("android.content.om.FabricatedOverlay$Builder");
                        Object fb = builder.getConstructor(String.class, String.class, String.class)
                                .newInstance(app.getPackageName(), PREFIX + e.pkg.replace('.', '_'), e.pkg);
                        fo = (FabricatedOverlay) builder.getMethod("build").invoke(fb);
                        overlays.put(e.pkg, fo);
                    }
                    String res = e.pkg + ":color/" + e.name;
                    if (e.day >= 0) fo.setResourceValue(res, TypedValue.TYPE_INT_COLOR_ARGB8, e.cd, "");
                    if (e.night >= 0) fo.setResourceValue(res, TypedValue.TYPE_INT_COLOR_ARGB8, e.cn, "night");
                    if (e.widget || "com.nothing.launcher".equals(e.pkg)) stamp.append(e.name).append(e.cd).append(e.cn);
                    count.merge(e.pkg, 1, Integer::sum);
                }
            }
            commit(app, overlays);
            String hash = Integer.toHexString(stamp.toString().hashCode());
            if (!hash.equals(Settings.Global.getString(app.getContentResolver(), WIDGETS_STAMP))) {
                Settings.Global.putString(app.getContentResolver(), WIDGETS_STAMP, hash);
            }
            XposedBridge.log("derealization: colors " + (overlays.isEmpty() ? "removed" : "applied " + count)
                    + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable t) {
            XposedBridge.log("derealization: colors — " + t);
        }
    }

    /** Транзакции OverlayManager (скрытый Builder — SystemUI платформенный). Снятие — отдельно: если наложения нет, ошибка не мешает остальному. */
    private static void commit(Application app, Map<String, FabricatedOverlay> overlays) throws Exception {
        Object om = app.getSystemService("overlay");
        Class<?> txb = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Class<?> oid = Class.forName("android.content.om.OverlayIdentifier");
        Method commit = om.getClass().getMethod("commit", Class.forName("android.content.om.OverlayManagerTransaction"));
        int user = (Integer) XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityManager"), "getCurrentUser");
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        for (String[] r : ColorMap.ROWS) targets.add(r[0]);
        for (String pkg : targets) {
            Object b = txb.getConstructor().newInstance();
            FabricatedOverlay fo = overlays.get(pkg);
            try {
                if (fo != null) {
                    txb.getMethod("registerFabricatedOverlay", FabricatedOverlay.class).invoke(b, fo);
                    txb.getMethod("setEnabled", oid, boolean.class, int.class).invoke(b, fo.getIdentifier(), true, user);
                } else {
                    Object id = oid.getConstructor(String.class, String.class).newInstance(app.getPackageName(), PREFIX + pkg.replace('.', '_'));
                    txb.getMethod("unregisterFabricatedOverlay", oid).invoke(b, id);
                }
                commit.invoke(om, txb.getMethod("build").invoke(b));
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (fo != null) XposedBridge.log("derealization: color overlay " + pkg + " — " + e.getCause());
            }
        }
    }
}
