package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Calendar;

/** Часы точечной матрицей: цифры 5×7 точек, как табло; погашенные точки видны еле-еле, горящие — светятся. */
public final class DotClockView extends ClockFaceView {
    /** Цифры 0–9 по строкам сверху вниз, 5 бит на строку (старший — левая точка). */
    private static final int[][] DIGITS = {
            {0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110},
            {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110},
            {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111},
            {0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110},
            {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010},
            {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110},
            {0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110},
            {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000},
            {0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110},
            {0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100},
    };
    private static final int[] COLON = {0, 0, 1, 0, 1, 0, 0};
    private static final int ROWS = 7;

    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DotClockView(Context context, int face) {
        super(context, face);
    }

    /** Строка «ЧЧ:ММ» в столбцах точек: цифра — 5, двоеточие — 1, между знаками — 1. */
    private String timeText() {
        boolean zero = prefs.leadingZero || is24();
        return two(hour(), zero) + ":" + two(calendar.get(Calendar.MINUTE), true);
    }

    private static int columns(String s) {
        int cols = 0;
        for (int i = 0; i < s.length(); i++) {
            cols += s.charAt(i) == ':' ? 1 : 5;
            if (i < s.length() - 1) cols += 1;
        }
        return cols;
    }

    /** Шаг точки так, чтобы строка влезла и по ширине, и в «родную» высоту часов Nothing. */
    private float pitch(int width) {
        String s = timeText();
        float cols = columns(s) + (showSeconds() ? 0.5f * (columns("00") + 2) : 0f);
        float byWidth = (width - 2 * paddingH()) / Math.max(1f, cols);
        float byHeight = baseHeight() / ROWS;
        return Math.max(1f, Math.min(byWidth, byHeight));
    }

    @Override
    protected float contentHeight(int width) {
        return pitch(width) * ROWS;
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float p = pitch(width);
        String s = timeText();
        boolean sec = showSeconds();
        float mainW = columns(s) * p;
        float secW = sec ? (columns("00") + 2) * p * 0.5f : 0f;
        float total = mainW + secW;
        float x0;
        switch (alignment()) {
            case ClockPrefs.ALIGN_LEFT: x0 = paddingH(); break;
            case ClockPrefs.ALIGN_RIGHT: x0 = width - paddingH() - total; break;
            default: x0 = (width - total) / 2f;
        }
        int color = mainColor();
        float r = 0.5f * p * prefs.dotSize / prefs.dotSpacing;
        boolean outline = prefs.aodOutline && doze > 0.5f;

        lit.setColor(color);
        lit.setStyle(outline ? Paint.Style.STROKE : Paint.Style.FILL);
        lit.setStrokeWidth(Math.max(1f, r * 0.35f));
        float glow = prefs.dotGlow * (1f - doze);
        if (glow > 0.01f && !outline) lit.setShadowLayer(r * (0.8f + 2.2f * glow), 0, 0, withAlpha(color, 0.9f * glow));
        else lit.clearShadowLayer();
        ghost.setColor(withAlpha(color, 0.11f * (1f - doze)));
        ghost.setStyle(Paint.Style.FILL);
        boolean drawGhost = prefs.dotGhost && doze < 0.99f;

        float x = x0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            x = glyph(c, ch, x, 0f, p, r, drawGhost);
            if (i < s.length() - 1) x += p;
        }
        if (sec) {
            float sp = p * 0.5f;
            String ss = two(calendar.get(Calendar.SECOND), true);
            float sx = x + sp * 2f;
            float sy = p * ROWS - sp * ROWS;                         // секунды прижаты к нижнему краю
            for (int i = 0; i < ss.length(); i++) {
                sx = glyph(c, ss.charAt(i), sx, sy, sp, r * 0.5f, drawGhost);
                if (i == 0) sx += sp;
            }
        }
    }

    /** Нарисовать один знак; вернуть x правого края. */
    private float glyph(Canvas c, char ch, float x, float y, float p, float r, boolean drawGhost) {
        int[] rows = ch == ':' ? COLON : DIGITS[ch - '0'];
        int w = ch == ':' ? 1 : 5;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < w; col++) {
                boolean on = ((rows[row] >> (w - 1 - col)) & 1) == 1;
                if (!on && !drawGhost) continue;
                float cx = x + col * p + p / 2f, cy = y + row * p + p / 2f;
                Paint paint = on ? lit : ghost;
                if (prefs.dotSquare) c.drawRect(cx - r, cy - r, cx + r, cy + r, paint);
                else c.drawCircle(cx, cy, r, paint);
            }
        }
        return x + w * p;
    }
}
