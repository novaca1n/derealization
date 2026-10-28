package dev.novacain.ntclocks.faces;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Настройки часов. Хранятся в приложении (JSON), в SystemUI приезжают через ContentProvider.
 * Значения по умолчанию подобраны так, чтобы часы выглядели «как у Nothing» сразу после выбора.
 */
public final class ClockPrefs {
    public static final int COLOR_THEME = 0;      // белые/тёмные — по яркости обоев под часами, как у Nothing
    public static final int COLOR_WALLPAPER = 1;  // акцент из обоев (seed color)
    public static final int COLOR_CUSTOM = 2;

    public static final int ALIGN_SYSTEM = 0, ALIGN_LEFT = 1, ALIGN_CENTER = 2, ALIGN_RIGHT = 3;
    public static final int FORMAT_SYSTEM = 0, FORMAT_12 = 1, FORMAT_24 = 2;

    // ── общие ──
    public int colorMode = COLOR_THEME;
    public int customColor = 0xFFF0471E;          // оранжевый CMF
    public float size = 1f;                        // 0.5…1.6 от «родного» размера часов Nothing
    public float offsetX = 0f, offsetY = 0f;       // смещение, dp
    public int align = ALIGN_SYSTEM;
    public boolean seconds = false;
    public int hourFormat = FORMAT_SYSTEM;
    public boolean leadingZero = true;
    public float aodBrightness = 0.65f;            // яркость в режиме Always-on (0.2…1)
    public boolean aodOutline = true;              // в AOD — контуром (меньше светящихся пикселей)

    // ── точечная матрица ──
    public float dotSize = 0.8f;                   // доля клетки, занятая точкой
    public float dotSpacing = 1f;                  // шаг между точками (×)
    public boolean dotSquare = false;
    public float dotGlow = 0.35f;                  // свечение 0…1
    public boolean dotGhost = true;                // показывать погашенные точки

    // ── растянутые цифры ──
    public float stretchY = 1.9f;                  // растяжение по высоте
    public float stretchX = 1f;                    // по ширине
    public int weight = 700;                       // толщина 100…900
    public boolean stacked = true;                 // часы над минутами
    public float lineGap = 0f;                     // зазор между строками (доля высоты цифры)
    public float letterSpacing = 0f;               // разрядка (em)

    // ── стрелочные ──
    public int ticks = 2;                          // 0 — нет, 1 — 4 метки, 2 — 12, 3 — 60
    public float handWidth = 1f;                   // толщина стрелок (×)
    public boolean accentSecond = true;            // секундная — акцентным цветом
    public boolean dialRing = true;

    // ── iOS / ColorOS / Origin ──
    public boolean glass = true;                   // iOS: «жидкое стекло» вместо сплошной заливки
    public boolean twoTone = true;                 // ColorOS: часы ярче минут
    public boolean showDate = true;                // ColorOS / Origin: своя дата рядом с цифрами

    // ── шрифт: "" — родной шрифт часов; иначе ключ из Fonts.all() ──
    public String font = "";

    // ── дата Nothing над часами: прятать, если у часов своя дата (иначе дублируется) ──
    public boolean hideSystemDate = false;

    public static ClockPrefs fromJson(String json) {
        return fromJson(json, new ClockPrefs());
    }

    /** Наложить сохранённое поверх значений по умолчанию конкретных часов (см. Clocks.defaults). */
    public static ClockPrefs fromJson(String json, ClockPrefs base) {
        ClockPrefs p = base;
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.colorMode = o.optInt("colorMode", p.colorMode);
            p.customColor = o.optInt("customColor", p.customColor);
            p.size = clamp((float) o.optDouble("size", p.size), 0.5f, 2.5f);
            p.offsetX = (float) o.optDouble("offsetX", p.offsetX);
            p.offsetY = (float) o.optDouble("offsetY", p.offsetY);
            p.align = o.optInt("align", p.align);
            p.seconds = o.optBoolean("seconds", p.seconds);
            p.hourFormat = o.optInt("hourFormat", p.hourFormat);
            p.leadingZero = o.optBoolean("leadingZero", p.leadingZero);
            p.aodBrightness = clamp((float) o.optDouble("aodBrightness", p.aodBrightness), 0.2f, 1f);
            p.aodOutline = o.optBoolean("aodOutline", p.aodOutline);
            p.dotSize = clamp((float) o.optDouble("dotSize", p.dotSize), 0.3f, 1f);
            p.dotSpacing = clamp((float) o.optDouble("dotSpacing", p.dotSpacing), 0.6f, 2f);
            p.dotSquare = o.optBoolean("dotSquare", p.dotSquare);
            p.dotGlow = clamp((float) o.optDouble("dotGlow", p.dotGlow), 0f, 1f);
            p.dotGhost = o.optBoolean("dotGhost", p.dotGhost);
            p.stretchY = clamp((float) o.optDouble("stretchY", p.stretchY), 0.6f, 3.5f);
            p.stretchX = clamp((float) o.optDouble("stretchX", p.stretchX), 0.5f, 1.6f);
            p.weight = Math.round(clamp(o.optInt("weight", p.weight), 100, 900));
            p.stacked = o.optBoolean("stacked", p.stacked);
            p.lineGap = clamp((float) o.optDouble("lineGap", p.lineGap), -0.3f, 0.6f);
            p.letterSpacing = clamp((float) o.optDouble("letterSpacing", p.letterSpacing), -0.15f, 0.5f);
            p.ticks = o.optInt("ticks", p.ticks);
            p.handWidth = clamp((float) o.optDouble("handWidth", p.handWidth), 0.4f, 2.5f);
            p.accentSecond = o.optBoolean("accentSecond", p.accentSecond);
            p.dialRing = o.optBoolean("dialRing", p.dialRing);
            p.glass = o.optBoolean("glass", p.glass);
            p.twoTone = o.optBoolean("twoTone", p.twoTone);
            p.showDate = o.optBoolean("showDate", p.showDate);
            p.font = o.optString("font", p.font);
            p.hideSystemDate = o.optBoolean("hideSystemDate", p.hideSystemDate);
        } catch (JSONException ignored) {
            // испорченные настройки — остаются значения по умолчанию, часы не падают
        }
        return p;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("colorMode", colorMode).put("customColor", customColor).put("size", size)
                    .put("offsetX", offsetX).put("offsetY", offsetY).put("align", align)
                    .put("seconds", seconds).put("hourFormat", hourFormat).put("leadingZero", leadingZero)
                    .put("aodBrightness", aodBrightness).put("aodOutline", aodOutline)
                    .put("dotSize", dotSize).put("dotSpacing", dotSpacing).put("dotSquare", dotSquare)
                    .put("dotGlow", dotGlow).put("dotGhost", dotGhost)
                    .put("stretchY", stretchY).put("stretchX", stretchX).put("weight", weight)
                    .put("stacked", stacked).put("lineGap", lineGap).put("letterSpacing", letterSpacing)
                    .put("ticks", ticks).put("handWidth", handWidth).put("accentSecond", accentSecond)
                    .put("dialRing", dialRing).put("glass", glass).put("twoTone", twoTone)
                    .put("showDate", showDate).put("font", font)
                    .put("hideSystemDate", hideSystemDate);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    public ClockPrefs copy() {
        return fromJson(toJson());
    }

    static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
