package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Эффект глубины под большие часы (Settings.Global ntweaker_depth). Сам эффект — родной Nothing (вырезка объекта в
 * «Обоях и стиле», слой поверх часов в SystemUI). enabled: «Обои и стиль» не запрещает эффект из-за своих маленьких
 * часов, а SystemUI обрезает вырезку по настоящим часам — объект заходит только на нижнюю долю cover их высоты.
 */
public final class DepthPrefs {
    public static final String KEY = "ntweaker_depth";

    public boolean enabled = false;
    public float cover = 0.35f;     // какую долю высоты часов (снизу) объекту можно закрывать
    public boolean anyWallpaper = false;   // «Обои и стиль» не запрещают эффект из-за стекла, широкого виджета, «нет пересечения»

    public static DepthPrefs fromJson(String json) {
        DepthPrefs p = new DepthPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.cover = AnimPrefs.clamp((float) o.optDouble("cover", p.cover), 0f, 1f);
            p.anyWallpaper = o.optBoolean("anyWallpaper", p.anyWallpaper);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("enabled", enabled).put("cover", cover).put("anyWallpaper", anyWallpaper).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public DepthPrefs copy() {
        return fromJson(toJson());
    }

    public static DepthPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new DepthPrefs();
        }
    }

    public static void write(ContentResolver cr, DepthPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
