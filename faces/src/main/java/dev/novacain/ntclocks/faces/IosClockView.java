package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;

import java.util.Calendar;

/**
 * В духе экрана блокировки iOS: крупное время «9:41» одним блоком, плотный гротеск (Inter — ближайший к SF Pro
 * свободный шрифт), плавная толщина как ползунок в iOS, «жидкое стекло» iOS 26 и растяжение по высоте
 * (как адаптивные цифры iOS 26). Дату над часами рисует сама Nothing — она стоит ровно там, где у iOS.
 */
public final class IosClockView extends TextFaceView {
    public IosClockView(Context context, int face) {
        super(context, face);
    }

    private String time() {
        return two(hour(), prefs.leadingZero && is24()) + ":" + two(calendar.get(Calendar.MINUTE), true);
    }

    private float size(int width) {
        font(Fonts.INTER, prefs.weight, 100f, prefs.letterSpacing - 0.02f);
        float dh = digitHeight() / 100f;
        String t = time() + (showSeconds() ? "000" : "");
        float byH = baseHeight() * 0.95f / dh;
        float byW = (width - 2 * paddingH()) / (textWidth(t) / 100f * prefs.stretchX);
        return Math.max(4f, Math.min(byH, byW));
    }

    @Override
    protected float contentHeight(int width) {
        font(Fonts.INTER, prefs.weight, size(width), prefs.letterSpacing - 0.02f);
        return digitHeight() * prefs.stretchY;
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float s = size(width);
        font(Fonts.INTER, prefs.weight, s, prefs.letterSpacing - 0.02f);
        String t = time();
        float w = textWidth(t) * prefs.stretchX;
        float secW = 0f;
        String sec = null;
        if (showSeconds()) {
            sec = two(calendar.get(Calendar.SECOND), true);
            secW = s * 0.9f;
        }
        float x = alignX(width, w + secW);
        int color = mainColor();
        drawDigits(c, t, x, 0f, prefs.stretchX, prefs.stretchY, color, prefs.glass);
        if (sec != null) {
            float dh = digitHeight() * prefs.stretchY;
            font(Fonts.INTER, prefs.weight, s * 0.32f, 0f);
            float sh = digitHeight();
            drawDigits(c, sec, x + w + s * 0.08f, dh - sh, 1f, 1f, withAlpha(color, 0.8f), prefs.glass);
        }
    }
}
