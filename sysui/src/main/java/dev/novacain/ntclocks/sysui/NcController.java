package dev.novacain.ntclocks.sysui;

import android.content.Context;
import android.content.res.Resources;

import com.android.systemui.plugins.clocks.AlarmData;
import com.android.systemui.plugins.clocks.ClockConfig;
import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.plugins.clocks.ClockEventListener;
import com.android.systemui.plugins.clocks.ClockEvents;
import com.android.systemui.plugins.clocks.ClockFaceController;
import com.android.systemui.plugins.clocks.ThemeConfig;
import com.android.systemui.plugins.clocks.WeatherData;
import com.android.systemui.plugins.clocks.ZenData;
import com.nothing.quicklook.calendar.CalendarSimpleData;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.TimeZone;

import dev.novacain.ntclocks.faces.ClockFace;
import dev.novacain.ntclocks.faces.Clocks;

/** Часы целиком — как NTClockController у Nothing: три лица с теми же id видов, общие события, конфиг. */
final class NcController implements ClockController {
    private final String id;
    private final NcFace small, large, snapshot;
    private final ClockFace[] views;
    private final ClockConfig config;

    NcController(Context context, String id) {
        this.id = id;
        PrefsClient prefs = PrefsClient.get(context);
        ClockFace vs = Clocks.create(context, id, ClockFace.FACE_SMALL);
        ClockFace vl = Clocks.create(context, id, ClockFace.FACE_LARGE);
        ClockFace vn = Clocks.create(context, id, ClockFace.FACE_SNAPSHOT);
        views = new ClockFace[]{vs, vl, vn};
        for (ClockFace v : views) prefs.attach(id, v);
        small = new NcFace(vs, "lockscreen_clock_view");
        large = new NcFace(vl, "lockscreen_clock_view_large");
        snapshot = new NcFace(vn, "lockscreen_clock_snapview");
        config = new ClockConfig(id, Clocks.title(id), "", false, false);
    }

    @Override public ClockConfig getConfig() { return config; }
    @Override public ClockFaceController getSmallClock() { return small; }
    @Override public ClockFaceController getLargeClock() { return large; }
    @Override public ClockFaceController getSnapshotClock() { return snapshot; }
    @Override public ClockEvents getEvents() { return events; }

    @Override
    public void initialize(boolean isDarkTheme, float dozeFraction, float foldFraction, ClockEventListener listener) {
        for (NcFace f : new NcFace[]{small, large, snapshot}) {
            f.getEvents().onThemeChanged(new ThemeConfig(isDarkTheme, f.getTheme().getSeedColor()));
            f.getEvents().onTimeTick();
            f.getEvents().onClockFaceChanged(true, true);
        }
        for (ClockFace v : views) {
            v.setTimeZone(TimeZone.getDefault());
            v.setDoze(dozeFraction);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("NtClocks " + id + " prefs=" + views[0].getPrefs().toJson());
    }

    private final ClockEvents events = new ClockEvents() {
        @Override public boolean isReactiveTouchInteractionEnabled() { return false; }
        @Override public void setReactiveTouchInteractionEnabled(boolean enabled) { }
        @Override public void onTimeZoneChanged(TimeZone tz) { for (ClockFace v : views) v.setTimeZone(tz); }
        @Override public void onTimeFormatChanged(boolean is24Hr) { for (ClockFace v : views) v.setSystem24(is24Hr); }
        @Override public void onLocaleChanged(Locale locale) { for (ClockFace v : views) v.refreshTime(); }
        @Override public void onDateChanged() { for (ClockFace v : views) v.refreshTime(); }
        @Override public void onUiModeChanged(Resources resources) { for (ClockFace v : views) v.asView().invalidate(); }
        @Override public void onAlarmDataChanged(AlarmData data) { }
        @Override public void onCalendarDataChanged(CalendarSimpleData data) { }
        @Override public void onWeatherDataChanged(WeatherData data) { }
        @Override public void onWeatherDataChanged(com.nothing.quicklook.weather.WeatherData data) { }
        @Override public void onZenDataChanged(ZenData data) { }
    };
}
