package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.util.Locale;

/** База «текстовых» часов: вариативный шрифт, растяжение глифов, контур в AOD, «жидкое стекло». */
abstract class TextFaceView extends ClockFaceView {
    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    protected final Rect bounds = new Rect();
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    TextFaceView(Context context, int face) {
        super(context, face);
    }

    /** Шрифт, толщина ('wght') и оптический размер ('opsz', если есть), моноширинные цифры. */
    protected void font(String asset, int weight, float size, float tracking) {
        // выбранный в приложении шрифт заменяет «фирменный» шрифт этих часов
        Typeface t = Fonts.get(getContext(), prefs.font.isEmpty() ? asset : prefs.font);
        if (paint.getTypeface() != t) paint.setTypeface(t);
        paint.setTextSize(size);
        String axes = "'wght' " + weight + (Fonts.INTER.equals(prefs.font.isEmpty() ? asset : prefs.font) ? ", 'opsz' 32" : "");
        if (!axes.equals(paint.getFontVariationSettings())) paint.setFontVariationSettings(axes);
        paint.setFontFeatureSettings("'tnum'");
        paint.setLetterSpacing(tracking);
    }

    /** Высота цифр (от базовой линии до верха) на текущем кегле. */
    protected float digitHeight() {
        paint.getTextBounds("0123456789", 0, 10, bounds);
        return bounds.height();
    }

    protected float textWidth(String s) {
        return paint.measureText(s);
    }

    protected String localDate(String skeleton) {
        Locale l = getResources().getConfiguration().getLocales().get(0);
        return DateFormat.format(DateFormat.getBestDateTimePattern(l, skeleton), calendar).toString();
    }

    /**
     * Нарисовать строку с верхним левым углом цифр в (x, top), растянутую sx×sy.
     * В AOD с «контуром» — обводка; иначе заливка или стекло.
     */
    protected void drawDigits(Canvas c, String s, float x, float top, float sx, float sy, int color, boolean glass) {
        paint.getTextBounds("0123456789", 0, 10, bounds);
        float baseline = -bounds.top;
        int save = c.save();
        c.translate(x, top);
        c.scale(sx, sy);
        boolean outline = prefs.aodOutline && doze > 0.5f;
        if (outline) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, paint.getTextSize() * 0.02f));
            paint.setColor(color);
            paint.clearShadowLayer();
            drawTextOutline(c, s, 0, baseline, paint);
        } else if (glass && doze < 0.5f) {
            drawGlass(c, s, baseline, color);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.clearShadowLayer();
            c.drawText(s, 0, baseline, paint);
        }
        c.restoreToCount(save);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.clearShadowLayer();
    }

    /**
     * «Жидкое стекло» в духе iOS 26: полупрозрачное тело цифр с мягкой тенью, сверху вниз гаснущий блик
     * и светлая кромка. Настоящего размытия фона под текстом в SystemUI-виде нет — это его имитация.
     */
    private void drawGlass(Canvas c, String s, float baseline, int color) {
        float size = paint.getTextSize();
        float topY = baseline + bounds.top, botY = baseline + bounds.bottom;
        // тело: цвет с прозрачностью + мягкая тень
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, 0.42f));
        paint.setShadowLayer(size * 0.05f, 0, size * 0.02f, 0x44000000);
        c.drawText(s, 0, baseline, paint);
        paint.clearShadowLayer();
        // блик сверху
        glassPaint.set(paint);
        glassPaint.setShader(new LinearGradient(0, topY, 0, botY,
                new int[]{withAlpha(0xFFFFFFFF, 0.55f), withAlpha(0xFFFFFFFF, 0.08f), withAlpha(0xFFFFFFFF, 0.18f)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawText(s, 0, baseline, glassPaint);
        // кромка
        glassPaint.setStyle(Paint.Style.STROKE);
        glassPaint.setStrokeWidth(Math.max(1f, size * 0.012f));
        glassPaint.setShader(new LinearGradient(0, topY, 0, botY,
                withAlpha(0xFFFFFFFF, 0.95f), withAlpha(0xFFFFFFFF, 0.25f), Shader.TileMode.CLAMP));
        c.drawText(s, 0, baseline, glassPaint);
    }

    /** x левого края блока ширины w по выбранному выравниванию. */
    protected float alignX(int width, float w) {
        switch (alignment()) {
            case ClockPrefs.ALIGN_LEFT: return paddingH();
            case ClockPrefs.ALIGN_RIGHT: return width - paddingH() - w;
            default: return (width - w) / 2f;
        }
    }
}
