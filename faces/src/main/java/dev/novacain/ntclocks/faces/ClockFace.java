package dev.novacain.ntclocks.faces;

import android.view.View;

import java.util.TimeZone;

/**
 * Циферблат для SystemUI и превью: всё равно, нарисован он одним видом (свои часы) или разметкой (стили crDroid).
 */
public interface ClockFace {
    int FACE_LARGE = 0, FACE_SMALL = 1, FACE_SNAPSHOT = 2;

    View asView();

    void setPrefs(ClockPrefs p);

    ClockPrefs getPrefs();

    void refreshTime();

    void setTimeZone(TimeZone tz);

    void setSystem24(boolean is24);

    void setRegionDark(boolean dark);

    void setSeedColor(Integer color);

    void setDoze(float amount);

    void setSystemLayout(boolean centered, boolean normal);
}
