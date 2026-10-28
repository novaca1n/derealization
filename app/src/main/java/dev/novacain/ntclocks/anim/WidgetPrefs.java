package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Виджеты экрана блокировки derealization (Settings.Global ntweaker_lockwidgets): наушники CMF и шаги с часов —
 * из cmf link, музыка — из медиасессий системы. Рисует их SystemUI (LockWidgets) под виджетами Nothing.
 */
public final class WidgetPrefs {
    public static final String KEY = "ntweaker_lockwidgets";

    public boolean enabled = true;
    public boolean buds = true;
    public boolean steps = true;
    public boolean music = true;
    public int goal = 8000;           // цель шагов — кольцо из точек

    public static WidgetPrefs fromJson(String json) {
        WidgetPrefs p = new WidgetPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.buds = o.optBoolean("buds", p.buds);
            p.steps = o.optBoolean("steps", p.steps);
            p.music = o.optBoolean("music", p.music);
            p.goal = Math.max(1000, o.optInt("goal", p.goal));
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("enabled", enabled).put("buds", buds).put("steps", steps).put("music", music)
                    .put("goal", goal).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public WidgetPrefs copy() {
        return fromJson(toJson());
    }

    public static WidgetPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new WidgetPrefs();
        }
    }

    public static void write(ContentResolver cr, WidgetPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
