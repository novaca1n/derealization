package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.Calendar;

/**
 * Растянутые цифры: глифы тянутся по высоте/ширине (масштаб холста), толщина — плавно через ось 'wght'
 * вариативного системного шрифта. «Часы над минутами» — по умолчанию, как высокие часы на экранах блокировки.
 */
public final class StretchClockView extends ClockFaceView {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect bounds = new Rect();
    private String appliedFont = null;

    public StretchClockView(Context context, int face) {
        super(context, face);
        text.setTextAlign(Paint.Align.LEFT);
        onPrefsChanged();
    }

    @Override
    protected void onPrefsChanged() {
        String key = prefs.weight + "|" + prefs.font;
        if (!key.equals(appliedFont)) {
            appliedFont = key;
            Typeface base = prefs.font.isEmpty() ? Typeface.DEFAULT : Fonts.get(getContext(), prefs.font);
            text.setTypeface(Typeface.create(base, prefs.weight, false));
            text.setFontVariationSettings("'wght' " + prefs.weight);   // плавно между жирностями, если шрифт вариативный
        }
        text.setLetterSpacing(prefs.letterSpacing);
    }

    private boolean stacked() {
        return prefs.stacked;
    }

    private String[] lines() {
        boolean zero = prefs.leadingZero || is24();
        String h = two(hour(), zero || stacked()), m = two(calendar.get(Calendar.MINUTE), true);
        return stacked() ? new String[]{h, m} : new String[]{h + ":" + m};
    }

    /** Подбор кегля: по высоте — «родная» высота часов, по ширине — с учётом растяжения по X. */
    private float layoutTextSize(int width) {
        String[] ls = lines();
        text.setTextSize(100f);
        text.getTextBounds("0123456789", 0, 10, bounds);
        float digitH = bounds.height() / 100f;                         // высота цифры на 1 px кегля
        float maxW = 0;
        for (String l : ls) maxW = Math.max(maxW, text.measureText(l) / 100f);
        float targetH = baseHeight();
        float byH = targetH / (ls.length * digitH + (ls.length - 1) * digitH * Math.max(0f, 0.12f + prefs.lineGap));
        float secRoom = showSeconds() && !stacked() ? 1.35f : 1f;
        float byW = (width - 2 * paddingH()) / (maxW * prefs.stretchX * secRoom);
        return Math.max(4f, Math.min(byH, byW));
    }

    private float digitHeight() {
        text.getTextBounds("0123456789", 0, 10, bounds);
        return bounds.height();
    }

    private float gap(float digitH) {
        return digitH * prefs.stretchY * (0.12f + prefs.lineGap);
    }

    @Override
    protected float contentHeight(int width) {
        text.setTextSize(layoutTextSize(width));
        float dh = digitHeight();
        int n = lines().length;
        return n * dh * prefs.stretchY + (n - 1) * gap(dh);
    }

    @Override
    protected void drawClock(Canvas c, int width, int height) {
        float size = layoutTextSize(width);
        text.setTextSize(size);
        float dh = digitHeight();
        int top = bounds.top;                                           // отрицательный: от базовой линии до верха цифр
        String[] ls = lines();
        int color = mainColor();
        boolean outline = prefs.aodOutline && doze > 0.5f;
        text.setColor(color);
        text.setStyle(outline ? Paint.Style.STROKE : Paint.Style.FILL);
        text.setStrokeWidth(Math.max(1f, size * 0.022f));

        float y = 0f;
        float lastRight = 0f, lastBottom = 0f;
        for (String l : ls) {
            float w = text.measureText(l) * prefs.stretchX;
            float x;
            switch (alignment()) {
                case ClockPrefs.ALIGN_LEFT: x = paddingH(); break;
                case ClockPrefs.ALIGN_RIGHT: x = width - paddingH() - w; break;
                default: x = (width - w) / 2f;
            }
            int save = c.save();
            c.translate(x, y);
            c.scale(prefs.stretchX, prefs.stretchY);
            if (outline) drawTextOutline(c, l, 0, -top, text);
            else c.drawText(l, 0, -top, text);
            c.restoreToCount(save);
            lastRight = x + w;
            lastBottom = y + dh * prefs.stretchY;
            y += dh * prefs.stretchY + gap(dh);
        }
        if (showSeconds()) {
            float s = size * 0.3f;
            text.setTextSize(s);
            text.setColor(stacked() ? withAlpha(color, 0.75f) : color);
            text.getTextBounds("00", 0, 2, bounds);
            String ss = two(calendar.get(Calendar.SECOND), true);
            float sw = text.measureText(ss);
            float sx = Math.min(lastRight + size * 0.06f, width - sw - 2f);
            c.drawText(ss, sx, lastBottom, text);
        }
    }
}
