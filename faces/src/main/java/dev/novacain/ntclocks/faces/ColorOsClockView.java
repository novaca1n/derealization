package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;

import java.util.Calendar;

/**
 * В духе ColorOS: крупные геометрические цифры (Outfit) часами над минутами, плотно; два тона — часы ярче,
 * минуты приглушены (или акцентным цветом); рядом с минутами — день недели и дата мелко.
 */
public final class ColorOsClockView extends TextFaceView {
    public ColorOsClockView(Context context, int face) {
        super(context, face);
    }

    private float gap(float dh) {
        return dh * (0.1f + prefs.lineGap);
    }

    private float size(int width) {
        font(Fonts.OUTFIT, prefs.weight, 100f, prefs.letterSpacing - 0.03f);
        float dh = digitHeight() / 100f;
        float byH = baseHeight() * 1.25f / (2 * dh * prefs.stretchY + gap(dh));
        float dateRoom = prefs.showDate ? 1.8f : 1f;
        float byW = (width - 2 * paddingH()) / (textWidth("00") / 100f * prefs.stretchX * dateRoom);
        return Math.max(4f, Math.min(byH, byW));
    }

    @Override
    protected float contentHeight(int width) {
        font(Fonts.OUTFIT, prefs.weight, size(width), prefs.letterSpacing - 0.03f);
        float dh = digitHeight();
        return 2 * dh * prefs.stretchY + gap(dh);
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float s = size(width);
        font(Fonts.OUTFIT, prefs.weight, s, prefs.letterSpacing - 0.03f);
        float dh = digitHeight();
        String h = two(hour(), true), m = two(calendar.get(Calendar.MINUTE), true);
        float wDigits = Math.max(textWidth(h), textWidth(m)) * prefs.stretchX;
        String day = localDate("EEEE"), date = localDate("dMMMM");
        float dateSize = s * 0.2f, dateW = 0f;
        if (prefs.showDate) {
            font(Fonts.OUTFIT, 500, dateSize, 0f);
            dateW = Math.max(textWidth(day), textWidth(date)) + s * 0.12f;
            font(Fonts.OUTFIT, prefs.weight, s, prefs.letterSpacing - 0.03f);
        }
        float x = alignX(width, wDigits + dateW);
        int main = mainColor();
        int second = prefs.twoTone ? withAlpha(main, 0.5f) : main;      // два тона: минуты приглушены
        drawDigits(c, h, x, 0f, prefs.stretchX, prefs.stretchY, main, false);
        float y2 = dh * prefs.stretchY + gap(dh);
        drawDigits(c, m, x, y2, prefs.stretchX, prefs.stretchY, second, false);
        if (prefs.showDate) {
            font(Fonts.OUTFIT, 500, dateSize, 0f);
            float dx = x + wDigits + s * 0.12f;
            float bottom = y2 + dh * prefs.stretchY;
            paint.setColor(withAlpha(main, 0.85f));
            c.drawText(date, dx, bottom, paint);
            paint.setColor(withAlpha(main, 0.55f));
            c.drawText(day, dx, bottom - dateSize * 1.25f, paint);
        }
    }
}
