package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Прозрачные папки и виджеты на рабочем столе — как тема Lucent в Nothing OS 5 (Settings.Global ntweaker_lucent).
 * all — все папки и виджеты; иначе только отмеченные (id элементов рабочего стола лаунчера).
 */
public final class LucentPrefs {
    public static final String KEY = "ntweaker_lucent";

    public boolean enabled = false;
    public float opacity = 0.35f;      // непрозрачность подложки папки/виджета
    public boolean all = false;
    public boolean blur = true;        // настоящее размытие обоев под подложкой (как в Nothing OS 5)
    public float blurRadius = 28f;     // dp
    public float grain = 0.5f;         // зерно поверх размытия, 0…1
    public final Set<Integer> ids = new LinkedHashSet<>();

    public boolean applies(int id) {
        return enabled && (all || ids.contains(id));
    }

    public static LucentPrefs fromJson(String json) {
        LucentPrefs p = new LucentPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.opacity = AnimPrefs.clamp((float) o.optDouble("opacity", p.opacity), 0f, 1f);
            p.all = o.optBoolean("all", p.all);
            p.blur = o.optBoolean("blur", p.blur);
            p.blurRadius = AnimPrefs.clamp((float) o.optDouble("blurRadius", p.blurRadius), 4f, 80f);
            p.grain = AnimPrefs.clamp((float) o.optDouble("grain", p.grain), 0f, 1f);
            JSONArray a = o.optJSONArray("ids");
            if (a != null) for (int i = 0; i < a.length(); i++) p.ids.add(a.optInt(i));
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            JSONArray a = new JSONArray();
            for (int id : ids) a.put(id);
            return new JSONObject().put("enabled", enabled).put("opacity", opacity).put("all", all)
                    .put("blur", blur).put("blurRadius", blurRadius).put("grain", grain).put("ids", a).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public LucentPrefs copy() {
        return fromJson(toJson());
    }

    public static LucentPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new LucentPrefs();
        }
    }

    public static void write(ContentResolver cr, LucentPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
