package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Шрифты модуля. Ключ "inter.ttf"/"outfit.ttf" — файл в assets (OFL); ключ "crd_*" — шрифт из ресурсов (перенесён
 * из crDroid). Внутри SystemUI контекст чужой, поэтому ресурсы берутся через createPackageContext. Не получилось —
 * системный шрифт, часы всё равно рисуются.
 */
public final class Fonts {
    public static final String INTER = "inter.ttf";
    public static final String OUTFIT = "outfit.ttf";
    private static final String PKG = "dev.novacain.ntclocks";
    private static final Map<String, Typeface> cache = new HashMap<>();

    private Fonts() {
    }

    /** Контекст ресурсов модуля (в SystemUI — контекст пакета модуля). */
    public static Context moduleContext(Context ctx) throws Exception {
        return PKG.equals(ctx.getPackageName()) ? ctx : ctx.createPackageContext(PKG, Context.CONTEXT_IGNORE_SECURITY);
    }

    public static synchronized Typeface get(Context ctx, String key) {
        Typeface t = cache.get(key);
        if (t != null) return t;
        try {
            Context pc = moduleContext(ctx);
            if (key.endsWith(".ttf") || key.endsWith(".otf")) {
                t = Typeface.createFromAsset(pc.getAssets(), "fonts/" + key);
            } else {
                int id = pc.getResources().getIdentifier(key, "font", PKG);
                t = id != 0 ? pc.getResources().getFont(id) : null;
            }
        } catch (Throwable e) {
            Log.w("NtClocks", "font " + key + " not loaded: " + e);
        }
        if (t == null) t = Typeface.DEFAULT;
        cache.put(key, t);
        return t;
    }

    /** Системный шрифт часов Android (config_clockFontFamily) — им crDroid рисует большинство стилей. */
    public static Typeface systemClock() {
        try {
            int id = Resources.getSystem().getIdentifier("config_clockFontFamily", "string", "android");
            String family = id != 0 ? Resources.getSystem().getString(id) : null;
            if (family != null && !family.isEmpty()) return Typeface.create(family, Typeface.NORMAL);
        } catch (Throwable ignored) {
        }
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    /** Все шрифты для выбора: свои (OFL) и перенесённые из crDroid. */
    public static List<String> all(android.content.Context ctx) {
        List<String> list = new ArrayList<>();
        list.add(INTER);
        list.add(OUTFIT);
        // open stand-ins (public builds) are the same fonts under other names — not listed twice
        java.util.Set<String> aliases = new java.util.HashSet<>();
        int id = ctx.getResources().getIdentifier("nc_font_aliases", "array", ctx.getPackageName());
        if (id != 0) aliases.addAll(java.util.Arrays.asList(ctx.getResources().getStringArray(id)));
        for (Field f : R.font.class.getFields()) if (!aliases.contains(f.getName())) list.add(f.getName());
        return list;
    }

    /** Название шрифта для списка. */
    public static String title(String key) {
        if (key == null || key.isEmpty()) return L.t("Default", "Родной");
        if (INTER.equals(key)) return "Inter";
        if (OUTFIT.equals(key)) return "Outfit";
        String s = key.startsWith("crd_") ? key.substring(4) : key;
        StringBuilder b = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
