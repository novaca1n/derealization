package dev.novacain.ntclocks.anim;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * derealization внутри «Обоев и стиля» Nothing: в родной ряд кнопок «Colors / Icon Pack / Layout» (главный экран) и
 * «Widgets / Shortcuts / More» (экран блокировки) дописывается четвёртая такая же кнопка «derealization» — из той же
 * разметки general_linked_option. Она открывает настройки derealization (SettingsActivity: разделы этого экрана).
 * Ряд строит общий класс h7.c (у Nothing экран берёт секции по классу — чужую секцию он бы не показал).
 */
public final class PickerHooks {
    private PickerHooks() {
    }

    private static final String PKG = "dev.novacain.ntclocks";
    private static final String SETTINGS = PKG + ".SettingsActivity";


    public static void hook(ClassLoader cl) {
        // ряд кнопок «Colors / Icon Pack / Layout» (h7.g) и «Widgets / Shortcuts / More» (h7.k) строит общий h7.c:
        // c(View, Context) — мост к g(…), который надувает nt_linked_section и раскладывает кнопки. Экран Nothing берёт
        // секции по классу (instanceof h7.g / h7.k), поэтому свою секцию не добавить — дописываем кнопку в их ряд
        Class<?> group = XposedHelpers.findClassIfExists("h7.c", cl);
        if (group == null) {
            XposedBridge.log("ntweaker: «Обои и стиль» — ряд кнопок не найден");
            return;
        }
        XC_MethodHook add = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.getResult() instanceof ViewGroup) || !(p.args[1] instanceof Context)) return;
                    ViewGroup row = (ViewGroup) p.getResult();
                    if (row.findViewWithTag(TAG) != null) return;            // мост c → g: не дважды
                    boolean lock = p.thisObject.getClass().getName().equals("h7.k");
                    addButton(row, (Context) p.args[1], lock ? "lock" : "home");
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: «Обои и стиль» — " + t);
                }
            }
        };
        int n = Hook.all(group, "g", add).size();
        if (n == 0) Hook.all(group, "c", add);
        XposedBridge.log("ntweaker: derealization в «Обоях и стиле» подключён");
    }

    private static final String TAG = "derealization";

    /** Кнопка «derealization» — та же разметка general_linked_option, что у соседей, с весом 1. */
    private static void addButton(ViewGroup row, Context ctx, String screen) {
        String pkg = ctx.getPackageName();
        int optId = ctx.getResources().getIdentifier("general_linked_option", "layout", pkg);
        int iconId = ctx.getResources().getIdentifier("option_icon", "id", pkg);
        int textId = ctx.getResources().getIdentifier("option_description", "id", pkg);
        if (optId == 0) return;
        LinearLayout opt = (LinearLayout) LayoutInflater.from(ctx).inflate(optId, row, false);
        opt.setTag(TAG);
        TextView tv = textId != 0 ? opt.findViewById(textId) : null;
        ImageView iv = iconId != 0 ? opt.findViewById(iconId) : null;
        if (tv != null) tv.setText("derealization");
        if (iv != null) {
            int color = tv != null ? tv.getCurrentTextColor() : 0xFFFFFFFF;
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setImageDrawable(new Glyph(4, color, ctx.getResources().getDisplayMetrics().density));
        }
        opt.setContentDescription("derealization");
        opt.setOnClickListener(v -> {
            try {
                v.getContext().startActivity(new Intent().setClassName(PKG, SETTINGS).putExtra("section", screen));
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: открыть настройки — " + t);
            }
        });
        row.addView(opt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    /** Значок кнопки: простые линии цветом подписи, 28 dp — в духе штатных значков «Обоев и стиля». */
    static final class Glyph extends Drawable {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private final RectF r = new RectF();

        Glyph(int kind, int color, float density) {
            this.kind = kind;
            this.size = Math.round(28 * density);
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width(), h = b.height(), x0 = b.left, y0 = b.top;
            float sw = w * 0.09f;
            switch (kind) {
                case 0: // анимации: окно вылетает из точки
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x0 + w * 0.2f, y0 + h * 0.8f, w * 0.09f, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    r.set(x0 + w * 0.4f, y0 + h * 0.12f, x0 + w * 0.88f, y0 + h * 0.66f);
                    c.drawRoundRect(r, w * 0.12f, w * 0.12f, p);
                    c.drawLine(x0 + w * 0.3f, y0 + h * 0.7f, x0 + w * 0.42f, y0 + h * 0.58f, p);
                    break;
                case 1: // стекло: два перекрытых прозрачных квадрата
                    p.setStyle(Paint.Style.FILL);
                    int a = p.getAlpha();
                    p.setAlpha(Math.round(a * 0.45f));
                    r.set(x0 + w * 0.1f, y0 + h * 0.1f, x0 + w * 0.62f, y0 + h * 0.62f);
                    c.drawRoundRect(r, w * 0.14f, w * 0.14f, p);
                    p.setAlpha(a);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    r.set(x0 + w * 0.38f, y0 + h * 0.38f, x0 + w * 0.9f, y0 + h * 0.9f);
                    c.drawRoundRect(r, w * 0.14f, w * 0.14f, p);
                    break;
                case 2: // цвета: три капли
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    c.drawCircle(x0 + w * 0.5f, y0 + h * 0.34f, w * 0.2f, p);
                    c.drawCircle(x0 + w * 0.32f, y0 + h * 0.64f, w * 0.2f, p);
                    c.drawCircle(x0 + w * 0.68f, y0 + h * 0.64f, w * 0.2f, p);
                    break;
                case 4: // derealization: кольцо с волной внутри
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    c.drawCircle(x0 + w / 2, y0 + h / 2, w * 0.4f, p);
                    android.graphics.Path wave = new android.graphics.Path();
                    wave.moveTo(x0 + w * 0.22f, y0 + h * 0.5f);
                    wave.cubicTo(x0 + w * 0.36f, y0 + h * 0.28f, x0 + w * 0.5f, y0 + h * 0.72f, x0 + w * 0.64f, y0 + h * 0.5f);
                    wave.cubicTo(x0 + w * 0.7f, y0 + h * 0.4f, x0 + w * 0.74f, y0 + h * 0.42f, x0 + w * 0.78f, y0 + h * 0.5f);
                    c.drawPath(wave, p);
                    break;
                default: // часы: циферблат
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    c.drawCircle(x0 + w / 2, y0 + h / 2, w * 0.4f, p);
                    c.drawLine(x0 + w / 2, y0 + h / 2, x0 + w / 2, y0 + h * 0.26f, p);
                    c.drawLine(x0 + w / 2, y0 + h / 2, x0 + w * 0.68f, y0 + h * 0.6f, p);
                    break;
            }
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
