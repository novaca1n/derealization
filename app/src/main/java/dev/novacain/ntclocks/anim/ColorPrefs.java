package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Адаптивные цвета: перекраска серых элементов Nothing в палитру Material You (Settings.Global ntweaker_color, JSON).
 * Палитру (стиль и исходный цвет) считает сама система — их приложение пишет в theme_customization_overlay_packages.
 * Элементы разбиты на области; у каждой — вкл/выкл, палитра (a1 — основная, a2 — вторая, a3 — третья, n2 — серый
 * в тон обоям) и тон: шаги по палитре, плюс — светлее, минус — темнее.
 */
public final class ColorPrefs {
    public static final String KEY = "ntweaker_color";
    public static final String[] AREAS = {"active", "second", "notif", "icons", "widgets"};

    public static final class Area {
        public boolean on;
        public String palette;
        public int tone;
        public float strength;     // сила цвета: 1 — палитра целиком, 0 — исходный серый Nothing

        Area(boolean on, String palette, int tone, float strength) {
            this.on = on;
            this.palette = palette;
            this.tone = tone;
            this.strength = strength;
        }
    }

    public boolean enabled = false;
    public boolean vivid = false;      // акценты на шаг насыщеннее (ближе к середине палитры)
    public final java.util.Map<String, Area> areas = new java.util.LinkedHashMap<>();

    public ColorPrefs() {
        areas.put("active", new Area(true, "a1", 0, 1f));     // включённое: плитки, ползунки, индикаторы
        areas.put("second", new Area(true, "a2", 0, 1f));     // второй серый: выключенные плитки, папки, группы меню
        areas.put("notif", new Area(true, "a2", 0, 0.6f));    // карточки уведомлений — спокойнее остального
        areas.put("icons", new Area(true, "a2", 0, 1f));      // монохромные значки лаунчера (фон; знак — цветом «включённого»)
        areas.put("widgets", new Area(true, "a2", 0, 1f));    // виджеты Nothing (фон; цифры/значки — цветом «включённого»)
    }

    public Area area(String id) {
        return areas.get(id);
    }

    public static ColorPrefs fromJson(String json) {
        ColorPrefs p = new ColorPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.vivid = o.optBoolean("vivid", p.vivid);
            // прежний формат (до областей)
            if (o.has("surfaces")) p.area("second").on = o.optBoolean("surfaces");
            if (o.has("second")) p.area("second").palette = o.optString("second").replace("accent", "a").replace("neutral", "n");
            if (o.has("icons")) p.area("icons").on = o.optBoolean("icons");
            if (o.has("widgets")) p.area("widgets").on = o.optBoolean("widgets");
            JSONObject a = o.optJSONObject("areas");
            if (a != null) {
                for (String id : AREAS) {
                    JSONObject x = a.optJSONObject(id);
                    if (x == null) continue;
                    Area ar = p.area(id);
                    ar.on = x.optBoolean("on", ar.on);
                    ar.palette = x.optString("palette", ar.palette);
                    ar.tone = Math.max(-3, Math.min(3, x.optInt("tone", ar.tone)));
                    ar.strength = AnimPrefs.clamp((float) x.optDouble("strength", ar.strength), 0f, 1f);
                }
            }
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            JSONObject a = new JSONObject();
            for (java.util.Map.Entry<String, Area> e : areas.entrySet()) {
                a.put(e.getKey(), new JSONObject().put("on", e.getValue().on).put("palette", e.getValue().palette).put("tone", e.getValue().tone).put("strength", e.getValue().strength));
            }
            return new JSONObject().put("enabled", enabled).put("vivid", vivid).put("areas", a).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public ColorPrefs copy() {
        return fromJson(toJson());
    }

    public static ColorPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new ColorPrefs();
        }
    }

    public static void write(ContentResolver cr, ColorPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
