package dev.novacain.ntclocks.anim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * «Настройки → О телефоне»: вместо трёх плиток Nothing (версия ОС, имя телефона, картинка) — одна широкая карточка,
 * как в ColorOS: живой фон из медленно плавающих пятен акцентных цветов системы (Material You), слева «Nothing OS»,
 * версия и «patched with derealization», внизу имя телефона, справа картинка телефона. Тексты и картинку берём из
 * родных плиток (они остаются в разметке, но скрыты), нажатие — как у плитки версии.
 * Строку строит DeviceInfoPreference (разметка nt_settings_about_phone_device_info_header), привязка — o0(holder).
 */
public final class AboutHooks {
    private AboutHooks() {
    }

    private static final String TAG = "derealization_card";

    public static void hook(ClassLoader cl) {
        Class<?> pref = XposedHelpers.findClassIfExists("com.nothing.settings.deviceinfo.aboutphone.DeviceInfoPreference", cl);
        if (pref == null) {
            XposedBridge.log("derealization: About phone — not found");
            return;
        }
        Hook.all(pref, "o0", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Context ctx;
                    try {
                        ctx = (Context) XposedHelpers.getObjectField(p.thisObject, "p0");
                    } catch (Throwable t) {
                        ctx = (Context) XposedHelpers.callMethod(p.thisObject, "getContext");
                    }
                    replace(ctx, p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: About phone — " + t);
                }
            }
        });
        XposedBridge.log("derealization: About phone hooked");
    }

    private static View find(Object holder, Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
        if (id == 0) return null;
        try {
            return (View) XposedHelpers.callMethod(holder, "Q", id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void replace(Context ctx, Object holder) {
        if (ctx == null) return;
        // holder.Q(id) — findViewById у PreferenceViewHolder; корень строки — родитель плитки версии
        View pic = find(holder, ctx, "iv_device_pic");
        View osName = find(holder, ctx, "tv_nt_os_name"), osVer = find(holder, ctx, "tv_nt_os_version_name");
        View os = find(holder, ctx, "rl_os_info");
        View summary = find(holder, ctx, "summary");
        if (summary == null) {
            try {
                summary = (View) XposedHelpers.callMethod(holder, "Q", android.R.id.summary);
            } catch (Throwable ignored) {
            }
        }
        if (pic == null || os == null) return;
        ViewGroup root = (ViewGroup) os.getParent();
        Card card = (Card) root.findViewWithTag(TAG);
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c != card) c.setVisibility(View.GONE);
        }
        if (card == null) {
            card = new Card(root.getContext());
            card.setTag(TAG);
            root.addView(card, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.name = text(osName, "NOTHING OS");
        card.version = text(osVer, "");
        card.device = text(summary, "");
        card.phone = pic instanceof ImageView ? ((ImageView) pic).getDrawable() : null;
        View click = os;
        card.setOnClickListener(v -> click.performClick());
        card.invalidate();
    }

    private static String text(View v, String def) {
        if (v instanceof TextView) {
            CharSequence s = ((TextView) v).getText();
            if (s != null && s.length() > 0) return s.toString();
        }
        return def;
    }

    /** Сама карточка: живой фон, тексты, картинка. Рисуется целиком на Canvas. */
    static final class Card extends View {
        String name = "NOTHING OS", version = "", device = "";
        private String nameSrc = "", nameUpper = "";
        Drawable phone;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final int[] blobs;
        private final int base;
        private final float d;
        private final Typeface dot, mono;
        private final long t0 = SystemClock.uptimeMillis();
        // градиенты создаются один раз (единичный радиус в 0,0) и двигаются матрицей — без мусора в каждом кадре
        private RadialGradient[] blobShaders;
        private LinearGradient shade;
        private float shadeW = -1;
        private final android.graphics.Matrix m = new android.graphics.Matrix();

        Card(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            base = c.getColor(android.R.color.system_accent1_900);
            blobs = new int[]{
                    c.getColor(android.R.color.system_accent1_400),
                    c.getColor(android.R.color.system_accent3_400),
                    c.getColor(android.R.color.system_accent2_300),
                    c.getColor(android.R.color.system_accent1_600),
                    c.getColor(android.R.color.system_accent3_200),
            };
            dot = font("/system/fonts/Ndot-57.otf", Typeface.create("ndot", Typeface.NORMAL));
            mono = font("/system/fonts/LetteraMonoLL-Regular.otf", Typeface.MONOSPACE);
            setClickable(true);
        }

        private static Typeface font(String path, Typeface def) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) return Typeface.createFromFile(f);
            } catch (Throwable ignored) {
            }
            return def;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * 0.62f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = 28 * d;
            clip.reset();
            r.set(0, 0, w, h);
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            // живой фон: тёмная основа акцента и пятна, плывущие по своим орбитам (как фон патчера)
            c.drawColor(base);
            double t = (SystemClock.uptimeMillis() - t0) / 1000.0;
            p.setShader(null);
            if (blobShaders == null) {
                blobShaders = new RadialGradient[blobs.length];
                for (int i = 0; i < blobs.length; i++) {
                    int col = blobs[i];
                    blobShaders[i] = new RadialGradient(0, 0, 1f, new int[]{(col & 0x00FFFFFF) | 0xB0000000, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
                }
            }
            for (int i = 0; i < blobs.length; i++) {
                double sp = 0.11 + 0.035 * i, ph = i * 1.7;
                float cx = (float) (w * (0.5 + 0.42 * Math.sin(t * sp + ph)));
                float cy = (float) (h * (0.5 + 0.45 * Math.cos(t * sp * 1.3 + ph * 0.7)));
                float rr = (float) (Math.max(w, h) * (0.42 + 0.08 * Math.sin(t * 0.21 + i)));
                m.setScale(rr, rr);
                m.postTranslate(cx, cy);
                blobShaders[i].setLocalMatrix(m);
                p.setShader(blobShaders[i]);
                c.drawCircle(cx, cy, rr, p);
            }
            // лёгкое затемнение слева — под текст
            if (shade == null || shadeW != w) {
                shade = new LinearGradient(0, 0, w * 0.7f, 0, 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
                shadeW = w;
            }
            p.setShader(shade);
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // телефон справа
            if (phone != null) {
                float ph = h * 0.82f;
                float iw = phone.getIntrinsicWidth() > 0 ? phone.getIntrinsicWidth() : 1, ih = phone.getIntrinsicHeight() > 0 ? phone.getIntrinsicHeight() : 1;
                float pw = ph * iw / ih;
                int left = Math.round(w - pw - 22 * d), top = Math.round((h - ph) / 2f);
                phone.setBounds(left, top, Math.round(left + pw), Math.round(top + ph));
                phone.draw(c);
            }
            // тексты слева
            float x = 22 * d;
            p.setColor(Color.WHITE);
            p.setTypeface(dot);
            p.setTextSize(26 * d);
            p.setLetterSpacing(0.04f);
            if (!name.equals(nameSrc)) { nameSrc = name; nameUpper = name.toUpperCase(); }
            c.drawText(nameUpper, x, 48 * d, p);
            p.setTextSize(22 * d);
            p.setAlpha(225);
            if (!version.isEmpty()) c.drawText(version, x, 78 * d, p);
            p.setTypeface(mono);
            p.setTextSize(11 * d);
            p.setLetterSpacing(0.08f);
            p.setAlpha(210);
            c.drawText("PATCHED WITH DEREALIZATION", x, 102 * d, p);
            if (!device.isEmpty()) {
                p.setTypeface(Typeface.DEFAULT);
                p.setLetterSpacing(0.02f);
                p.setTextSize(15 * d);
                p.setAlpha(235);
                c.drawText(device, x, h - 22 * d, p);
            }
            p.setAlpha(255);
            c.restore();
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
        }
    }
}
