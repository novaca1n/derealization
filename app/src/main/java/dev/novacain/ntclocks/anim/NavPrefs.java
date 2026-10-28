package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/** Полоска жестов внизу экрана (Settings.Global ntweaker_nav, JSON). enabled = false — как у Nothing. */
public final class NavPrefs {
    public static final String KEY = "ntweaker_nav";

    public boolean enabled = false;
    public String color = "system";   // system — как у Nothing (белая/чёрная), a1/a2/a3 — тон палитры Material You
    public float width = 132f;        // длина, dp (у Nothing 132)
    public float thickness = 4f;      // толщина, dp (у Nothing 4 — радиус 2)
    public float opacity = 1f;        // непрозрачность

    public static NavPrefs fromJson(String json) {
        NavPrefs p = new NavPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.color = o.optString("color", p.color);
            p.width = AnimPrefs.clamp((float) o.optDouble("width", p.width), 40f, 300f);
            p.thickness = AnimPrefs.clamp((float) o.optDouble("thickness", p.thickness), 2f, 10f);
            p.opacity = AnimPrefs.clamp((float) o.optDouble("opacity", p.opacity), 0f, 1f);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("enabled", enabled).put("color", color).put("width", width)
                    .put("thickness", thickness).put("opacity", opacity).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public NavPrefs copy() {
        return fromJson(toJson());
    }

    public static NavPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new NavPrefs();
        }
    }

    public static void write(ContentResolver cr, NavPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
