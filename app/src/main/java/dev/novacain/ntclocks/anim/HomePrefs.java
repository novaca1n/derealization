package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Стол и док (Settings.Global ntweaker_home): док-стекло как в iOS 26, подпрыгивание значка дока при запуске,
 * подписи (как у Nothing / без подписей у дока / без подписей), подписи шрифтом Ndot, свайп двумя пальцами вниз —
 * быстрые настройки, растягивание значков за угол. Читает лаунчер (HomeHooks, ResizeFrame).
 */
public final class HomePrefs {
    public static final String KEY = "ntweaker_home";
    public static final int LABELS_NOTHING = 0, LABELS_NO_DOCK = 1, LABELS_NONE = 2;

    public boolean dockGlass = true;
    public float dockBlur = 40f;        // dp
    public float dockTint = 0.16f;      // белый оттенок стекла
    public boolean bounce = true;
    public int labels = LABELS_NOTHING;
    public boolean ndot = false;
    public boolean twoFingerQs = true;
    public boolean cornerResize = true;

    public static HomePrefs fromJson(String json) {
        HomePrefs p = new HomePrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.dockGlass = o.optBoolean("dockGlass", p.dockGlass);
            p.dockBlur = AnimPrefs.clamp((float) o.optDouble("dockBlur", p.dockBlur), 0f, 120f);
            p.dockTint = AnimPrefs.clamp((float) o.optDouble("dockTint", p.dockTint), 0f, 0.6f);
            p.bounce = o.optBoolean("bounce", p.bounce);
            p.labels = Math.max(0, Math.min(2, o.optInt("labels", p.labels)));
            p.ndot = o.optBoolean("ndot", p.ndot);
            p.twoFingerQs = o.optBoolean("twoFingerQs", p.twoFingerQs);
            p.cornerResize = o.optBoolean("cornerResize", p.cornerResize);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("dockGlass", dockGlass).put("dockBlur", dockBlur).put("dockTint", dockTint)
                    .put("bounce", bounce).put("labels", labels).put("ndot", ndot).put("twoFingerQs", twoFingerQs)
                    .put("cornerResize", cornerResize).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public HomePrefs copy() {
        return fromJson(toJson());
    }

    public static HomePrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new HomePrefs();
        }
    }

    public static void write(ContentResolver cr, HomePrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
