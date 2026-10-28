package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Calendar;

/**
 * В духе OriginOS: очень тонкие крупные цифры в одну строку, часы и минуты разведены, а между ними —
 * узкая колонка с днём недели и числом, отделённая тонкими линиями.
 */
public final class OriginClockView extends TextFaceView {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OriginClockView(Context context, int face) {
        super(context, face);
    }

    private float midWidth(float s) {
        return prefs.showDate ? s * 0.42f : s * 0.18f;
    }

    private float size(int width) {
        font(Fonts.INTER, prefs.weight, 100f, prefs.letterSpacing);
        float dh = digitHeight() / 100f;
        float byH = baseHeight() * 0.95f / (dh * prefs.stretchY);
        float perPx = (textWidth("00") * 2 / 100f) * prefs.stretchX + 0.42f;
        float byW = (width - 2 * paddingH()) / perPx;
        return Math.max(4f, Math.min(byH, byW));
    }

    @Override
    protected float contentHeight(int width) {
        font(Fonts.INTER, prefs.weight, size(width), prefs.letterSpacing);
        return digitHeight() * prefs.stretchY;
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float s = size(width);
        font(Fonts.INTER, prefs.weight, s, prefs.letterSpacing);
        float dh = digitHeight() * prefs.stretchY;
        String h = two(hour(), true), m = two(calendar.get(Calendar.MINUTE), true);
        float wh = textWidth(h) * prefs.stretchX, wm = textWidth(m) * prefs.stretchX, mid = midWidth(s);
        float x = alignX(width, wh + mid + wm);
        int color = mainColor();
        drawDigits(c, h, x, 0f, prefs.stretchX, prefs.stretchY, color, false);
        drawDigits(c, m, x + wh + mid, 0f, prefs.stretchX, prefs.stretchY, color, false);

        float cx = x + wh + mid / 2f;
        line.setColor(withAlpha(color, 0.5f));
        line.setStrokeWidth(Math.max(1f, s * 0.008f));
        if (prefs.showDate) {
            font(Fonts.INTER, 400, s * 0.13f, 0.08f);
            paint.setTextAlign(Paint.Align.CENTER);
            String day = localDate("EEE").replace(".", "").toUpperCase(), num = localDate("d");
            float lineH = dh * 0.18f;
            c.drawLine(cx, dh * 0.08f, cx, dh * 0.08f + lineH, line);
            paint.setColor(withAlpha(color, 0.75f));
            c.drawText(day, cx, dh * 0.5f - s * 0.02f, paint);
            font(Fonts.INTER, Math.max(prefs.weight, 300), s * 0.2f, 0f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(color);
            c.drawText(num, cx, dh * 0.5f + s * 0.17f, paint);
            c.drawLine(cx, dh * 0.92f - lineH, cx, dh * 0.92f, line);
            paint.setTextAlign(Paint.Align.LEFT);
        } else {
            c.drawLine(cx, dh * 0.3f, cx, dh * 0.7f, line);
        }
    }
}
