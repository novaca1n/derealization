package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Calendar;

/** Минималистичные стрелочные: метки, кольцо, скруглённые стрелки, секундная — акцентом (оранжевый CMF). */
public final class AnalogClockView extends ClockFaceView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnalogClockView(Context context, int face) {
        super(context, face);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float diameter(int width) {
        float byH = baseHeight() * 1.45f;
        return Math.max(8f, Math.min(byH, width - 2 * paddingH()));
    }

    @Override
    protected float contentHeight(int width) {
        return diameter(width);
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float d = diameter(width), r = d / 2f;
        float cx;
        switch (alignment()) {
            case ClockPrefs.ALIGN_LEFT: cx = paddingH() + r; break;
            case ClockPrefs.ALIGN_RIGHT: cx = width - paddingH() - r; break;
            default: cx = width / 2f;
        }
        float cy = r;
        int color = mainColor();
        float thin = 1f - 0.45f * doze;                                   // в AOD — тоньше
        float unit = d / 100f;

        paint.setStyle(Paint.Style.STROKE);
        if (prefs.dialRing) {
            paint.setColor(withAlpha(color, 0.35f));
            paint.setStrokeWidth(unit * 0.9f * thin);
            c.drawCircle(cx, cy, r - unit, paint);
        }
        int n = prefs.ticks == 1 ? 4 : prefs.ticks == 2 ? 12 : prefs.ticks == 3 ? 60 : 0;
        for (int i = 0; i < n; i++) {
            boolean major = n == 60 ? i % 5 == 0 : true;
            double a = Math.toRadians(i * 360.0 / n);
            float outer = r - unit * 4f, inner = outer - unit * (major ? 6f : 2.5f);
            paint.setColor(withAlpha(color, major ? 0.9f : 0.45f));
            paint.setStrokeWidth(unit * (major ? 1.8f : 0.9f) * thin);
            c.drawLine(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * outer, cy - (float) Math.cos(a) * outer, paint);
        }

        int h = calendar.get(Calendar.HOUR), m = calendar.get(Calendar.MINUTE), s = calendar.get(Calendar.SECOND);
        boolean sec = showSeconds();
        float minuteFrac = m + (sec ? s / 60f : 0f);
        hand(c, cx, cy, (h + minuteFrac / 60f) * 30f, r * 0.52f, unit * 4.2f * prefs.handWidth * thin, color);
        hand(c, cx, cy, minuteFrac * 6f, r * 0.78f, unit * 2.8f * prefs.handWidth * thin, color);
        if (sec) {
            int sc = prefs.accentSecond ? accentColor() : color;
            paint.setStrokeWidth(unit * 1.1f);
            paint.setColor(sc);
            double a = Math.toRadians(s * 6f);
            c.drawLine(cx - (float) Math.sin(a) * r * 0.14f, cy + (float) Math.cos(a) * r * 0.14f,
                    cx + (float) Math.sin(a) * r * 0.86f, cy - (float) Math.cos(a) * r * 0.86f, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, unit * 2.2f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(prefs.accentSecond && doze < 0.5f ? accentColor() : color);
            c.drawCircle(cx, cy, unit * 2.2f * thin, paint);
        }
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float w, int color) {
        boolean outline = prefs.aodOutline && doze > 0.5f;
        double a = Math.toRadians(deg);
        float ex = cx + (float) Math.sin(a) * len, ey = cy - (float) Math.cos(a) * len;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        if (outline) {
            // контур стрелки: две линии-края вместо сплошной
            paint.setStrokeWidth(Math.max(1f, w * 0.22f));
            float nx = (float) Math.cos(a) * w / 2f, ny = (float) Math.sin(a) * w / 2f;
            c.drawLine(cx + nx, cy + ny, ex + nx, ey + ny, paint);
            c.drawLine(cx - nx, cy - ny, ex - nx, ey - ny, paint);
            c.drawLine(ex + nx, ey + ny, ex - nx, ey - ny, paint);
        } else {
            paint.setStrokeWidth(w);
            c.drawLine(cx, cy, ex, ey, paint);
        }
    }
}
