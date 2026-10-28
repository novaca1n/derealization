package dev.novacain.ntclocks.sysui;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;

import com.android.systemui.plugins.clocks.ClockAnimations;
import com.android.systemui.plugins.clocks.ClockAxisStyle;
import com.android.systemui.plugins.clocks.ClockFaceConfig;
import com.android.systemui.plugins.clocks.ClockFaceController;
import com.android.systemui.plugins.clocks.ClockFaceEvents;
import com.android.systemui.plugins.clocks.ClockFaceLayout;
import com.android.systemui.plugins.clocks.ClockTickRate;
import com.android.systemui.plugins.clocks.ThemeConfig;

import dev.novacain.ntclocks.faces.ClockFace;

/**
 * Одно «лицо» часов (основное / большое / снимок) — как NTClockFaceController у Nothing: вид часов, id для разметки
 * экрана блокировки, DefaultClockFaceLayout, события и анимации проброшены в вид.
 */
final class NcFace implements ClockFaceController {
    private final ClockFace view;
    private final NcLayout layout;
    private final ClockFaceConfig config = new ClockFaceConfig(ClockTickRate.PER_MINUTE, false, false, false);
    private ThemeConfig theme = new ThemeConfig(true, null);

    NcFace(ClockFace view, String viewIdName) {
        this.view = view;
        // тот же id, что у часов Nothing: по нему разметка экрана блокировки ставит часы на место
        View v = view.asView();
        int id = v.getResources().getIdentifier(viewIdName, "id", v.getContext().getPackageName());
        if (id != 0) v.setId(id);
        layout = new NcLayout(v);
    }

    @Override public View getView() { return view.asView(); }
    @Override public ClockFaceLayout getLayout() { return layout; }
    @Override public ClockFaceConfig getConfig() { return config; }
    @Override public ThemeConfig getTheme() { return theme; }
    @Override public ClockFaceEvents getEvents() { return events; }
    @Override public ClockAnimations getAnimations() { return animations; }

    @Override
    public void updateClockface(boolean isCentered, boolean isNormalSize) {
        events.onClockFaceChanged(isCentered, isNormalSize);
    }

    private final ClockFaceEvents events = new ClockFaceEvents() {
        @Override public void onTimeTick() { view.refreshTime(); }
        @Override public void onThemeChanged(ThemeConfig t) {
            theme = t;
            view.setRegionDark(t.isDarkTheme());
            view.setSeedColor(t.getSeedColor());
        }
        @Override public void onFontSettingChanged(float fontSizePx) { view.asView().requestLayout(); }
        @Override public void onTargetRegionChanged(Rect targetRegion) { }
        @Override public void onSecondaryDisplayChanged(boolean onSecondaryDisplay) { }
        @Override public void onDozeChanged(boolean dozing) { if (!dozing) view.setDoze(0f); }
        @Override public void onDozeAmountChanged(float linear, float eased) { view.setDoze(eased); }
        @Override public void onScreenOff(boolean screenOff) { }
        @Override public void onStartedWakingUp() { view.refreshTime(); }
        @Override public void onStartedGoingToSleep(boolean isKeyguardVisible) { }
        @Override public void onWakefulnessStateChanged(boolean isWakingUp, Point tapPosition) { }
        @Override public void onClockFaceChanged(boolean isCentered, boolean isNormalSize) {
            view.setSystemLayout(isCentered, isNormalSize);
        }
    };

    private final ClockAnimations animations = new ClockAnimations() {
        @Override public void enter() { view.refreshTime(); }
        @Override public void doze(float fraction) { view.setDoze(fraction); }
        @Override public void fold(float fraction) { }
        @Override public void charge() { }
        @Override public void onPositionUpdated(float distance, float fraction) { }
        @Override public void onPositionUpdated(int fromLeft, int direction, float fraction) { }
        @Override public void onPickerCarouselSwiping(float swipingFraction) { }
        @Override public void onFidgetTap(float x, float y) { }
        @Override public void onFontAxesChanged(ClockAxisStyle style) { }
    };
}
