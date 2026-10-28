package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * «Стекло»: прозрачность и размытие шторки (SystemUI) и меню приложений (лаунчер). Settings.Global ntweaker_glass
 * (JSON). enabled = false — ничего не трогаем. Размытие работает, только если телефон его поддерживает
 * (ro.surface_flinger.supports_background_blur; на CMF Phone 2 Pro его включает модуль Magisk из приложения).
 */
public final class GlassPrefs {
    public static final String KEY = "ntweaker_glass";

    public boolean enabled = false;
    public float shadeAlpha = 0.7f;     // непрозрачность подложки шторки и быстрых настроек (у Nothing — 1)
    public float shadeBlur = 40f;       // размытие под шторкой, dp (у Nothing — 23 px, на этом телефоне выключено)
    public float drawerAlpha = 0.6f;    // непрозрачность подложки меню приложений
    public float drawerBlur = 40f;      // размытие обоев под меню приложений, dp
    public float shadeGrain = 0.15f;    // зерно поверх шторки, 0…1 (как в Nothing OS 5)
    public float drawerGrain = 0.15f;    // зерно поверх меню приложений
    public float panelAlpha = 0.45f;    // стеклянные панели: громкость, меню долгого нажатия на иконку
    public boolean notifRound = false;  // уведомления отдельными скруглёнными карточками, как в One UI
    public float notifRadius = 26f;     // dp (у Nothing 16, у соседних — 4)
    public float notifGap = 8f;         // dp между карточками (у Nothing 2)
    public float searchAlpha = 0.3f;    // непрозрачность панели поиска в меню приложений (у Nothing — 1)

    public static GlassPrefs fromJson(String json) {
        GlassPrefs p = new GlassPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", p.enabled);
            p.shadeAlpha = AnimPrefs.clamp((float) o.optDouble("shadeAlpha", p.shadeAlpha), 0f, 1f);
            p.shadeBlur = AnimPrefs.clamp((float) o.optDouble("shadeBlur", p.shadeBlur), 0f, 120f);
            p.drawerAlpha = AnimPrefs.clamp((float) o.optDouble("drawerAlpha", p.drawerAlpha), 0f, 1f);
            p.drawerBlur = AnimPrefs.clamp((float) o.optDouble("drawerBlur", p.drawerBlur), 0f, 120f);
            p.shadeGrain = AnimPrefs.clamp((float) o.optDouble("shadeGrain", p.shadeGrain), 0f, 1f);
            p.drawerGrain = AnimPrefs.clamp((float) o.optDouble("drawerGrain", p.drawerGrain), 0f, 1f);
            p.panelAlpha = AnimPrefs.clamp((float) o.optDouble("panelAlpha", p.panelAlpha), 0f, 1f);
            p.notifRound = o.optBoolean("notifRound", p.notifRound);
            p.notifRadius = AnimPrefs.clamp((float) o.optDouble("notifRadius", p.notifRadius), 8f, 40f);
            p.notifGap = AnimPrefs.clamp((float) o.optDouble("notifGap", p.notifGap), 0f, 24f);
            p.searchAlpha = AnimPrefs.clamp((float) o.optDouble("searchAlpha", p.searchAlpha), 0f, 1f);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("enabled", enabled).put("shadeAlpha", shadeAlpha).put("shadeBlur", shadeBlur)
                    .put("drawerAlpha", drawerAlpha).put("drawerBlur", drawerBlur)
                    .put("shadeGrain", shadeGrain).put("drawerGrain", drawerGrain).put("searchAlpha", searchAlpha)
                    .put("panelAlpha", panelAlpha).put("notifRound", notifRound).put("notifRadius", notifRadius)
                    .put("notifGap", notifGap).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public GlassPrefs copy() {
        return fromJson(toJson());
    }

    public static GlassPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new GlassPrefs();
        }
    }

    public static void write(ContentResolver cr, GlassPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }
}
