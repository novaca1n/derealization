package dev.novacain.ntclocks.anim;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONObject;

/**
 * Карточки виджетов экрана блокировки в Material 3 Expressive: тона Material You системы (surfaceContainerHigh,
 * primary, tertiary), крупная типографика, выразительные формы и пружины. Наушники 1×1 — процент и полосы M3 (активная
 * часть, зазор, дорожка, точка-стоп); шаги 1×1 — волнистое кольцо прогресса M3E; музыка 2×1 — обложка в форме
 * «печенье», волнистая полоса прогресса трека (течёт, пока играет) и кнопка, которая меняет форму на пружине
 * (круг на паузе ↔ скруглённый квадрат при игре). Клетка — до 94 dp, карточки без данных скрыты.
 * Отрисовка без выделения памяти в кадре: пути, прямоугольники и краски переиспользуются.
 */
public final class WidgetCards {
    private WidgetCards() {
    }

    /** Тона Material You системы (тёмная или светлая тема). */
    static final class Tones {
        final int container, onSurface, onSurfaceVariant, primary, onPrimary, primaryContainer, onPrimaryContainer,
                secondaryContainer, tertiary, error;

        Tones(Context c) {
            boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            container = alpha(c.getColor(night ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_50), 0.94f);
            onSurface = c.getColor(night ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
            onSurfaceVariant = c.getColor(night ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
            primary = c.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            onPrimary = c.getColor(night ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
            primaryContainer = c.getColor(night ? android.R.color.system_accent1_700 : android.R.color.system_accent1_100);
            onPrimaryContainer = c.getColor(night ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900);
            secondaryContainer = c.getColor(night ? android.R.color.system_accent2_700 : android.R.color.system_accent2_100);
            tertiary = c.getColor(night ? android.R.color.system_accent3_200 : android.R.color.system_accent3_600);
            error = night ? 0xFFF2B8B5 : 0xFFB3261E;
        }

        static int alpha(int c, float a) {
            return (c & 0x00FFFFFF) | (Math.round(255 * a) << 24);
        }
    }

    private static final Typeface REGULAR = Typeface.create(Typeface.DEFAULT, 400, false);
    private static final Typeface MEDIUM = Typeface.create(Typeface.DEFAULT, 500, false);
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, 700, false);
    private static final Typeface HEAVY = Typeface.create(Typeface.DEFAULT, 800, false);

    /** Ряд карточек по центру; клетка считается от ширины (4 клетки + 3 зазора), но не больше 94 dp. */
    public static final class Row extends ViewGroup {
        final Card[] cards;
        private final float d;
        private boolean dozing;

        public Row(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            Tones t = new Tones(c);
            cards = new Card[]{new Buds(c, t), new Steps(c, t), new Music(c, t)};
            for (Card k : cards) addView(k);
            setClipChildren(false);
        }

        void setDozing(boolean z) {
            dozing = z;
            update();
        }

        public void update() {
            WidgetPrefs p = LockWidgets.prefs;
            boolean any = false;
            for (Card k : cards) {
                boolean on = p.enabled && k.allowed(p) && k.bind();
                k.setVisibility(on ? VISIBLE : GONE);
                any |= on;
                if (on) k.invalidate();
            }
            // в AOD — невидим, но место держим: часы и уведомления не прыгают при пробуждении
            setVisibility(!any ? GONE : dozing ? INVISIBLE : VISIBLE);
            requestLayout();
        }

        private int cell(int width) {
            float gap = 10 * d;
            return Math.round(Math.min(94 * d, (width - 32 * d - 3 * gap) / 4f));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int cell = cell(w);
            int gap = Math.round(10 * d);
            for (Card k : cards) {
                if (k.getVisibility() == GONE) continue;
                k.measure(MeasureSpec.makeMeasureSpec(cell * k.span + gap * (k.span - 1), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, cell);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int gap = Math.round(10 * d);
            int total = 0, n = 0;
            for (Card k : cards) if (k.getVisibility() != GONE) { total += k.getMeasuredWidth(); n++; }
            total += gap * Math.max(0, n - 1);
            int x = (r - l - total) / 2;
            for (Card k : cards) {
                if (k.getVisibility() == GONE) continue;
                k.layout(x, 0, x + k.getMeasuredWidth(), k.getMeasuredHeight());
                x += k.getMeasuredWidth() + gap;
            }
        }
    }

    /** Пружина (M3 Expressive): шаг по времени кадра. */
    static final class Spring {
        float value, velocity, target;

        void snap(float v) {
            value = target = v;
            velocity = 0f;
        }

        boolean step(float dt, float stiffness, float dampingRatio) {
            float damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
            velocity += (-stiffness * (value - target) - damping * velocity) * dt;
            value += velocity * dt;
            if (Math.abs(value - target) < 0.001f && Math.abs(velocity) < 0.01f) {
                value = target;
                velocity = 0f;
                return false;
            }
            return true;
        }
    }

    /** Карточка: контейнер M3 (скругление 28 dp × масштаб), общие краски. bind() — взять данные. */
    abstract static class Card extends View {
        final int span;
        final Tones t;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Path path = new Path();

        Card(Context c, int span, Tones t) {
            super(c);
            this.span = span;
            this.t = t;
            d = c.getResources().getDisplayMetrics().density;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        abstract boolean allowed(WidgetPrefs p);

        abstract boolean bind();

        abstract void content(Canvas c, float w, float h);

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            rect.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.container);
            float r = Math.min(28 * d, h * 0.3f);
            c.drawRoundRect(rect, r, r, p);
            content(c, w, h);
        }

        void text(Canvas c, String s, Typeface f, float size, int color, float x, float y, Paint.Align a) {
            tp.setTypeface(f);
            tp.setTextSize(size);
            tp.setColor(color);
            tp.setTextAlign(a);
            c.drawText(s, x, y, tp);
        }

        /** Линейный индикатор M3 (2024): активная часть, зазор, дорожка, точка-стоп на конце. */
        void linear(Canvas c, float x0, float x1, float y, float frac, float thick, int active, int track) {
            frac = Math.max(0f, Math.min(1f, frac));
            float gap = thick * 1.0f, ax = x0 + (x1 - x0) * frac;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thick);
            if (frac < 1f) {
                p.setColor(track);
                float tx0 = frac > 0f ? ax + gap + thick : x0 + thick / 2;
                if (tx0 < x1 - thick / 2) c.drawLine(tx0, y, x1 - thick / 2, y, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(active);
                c.drawCircle(x1 - thick / 2, y, thick * 0.32f, p);        // точка-стоп
                p.setStyle(Paint.Style.STROKE);
            }
            if (frac > 0f) {
                p.setColor(active);
                c.drawLine(x0 + thick / 2, y, Math.max(x0 + thick / 2, ax - thick / 2), y, p);
            }
        }
    }

    // ───────── наушники ─────────

    /** Наушники CMF 1×1: значок и подпись, крупный процент слабейшего наушника, полосы L / R и строка кейса. */
    static final class Buds extends Card {
        private int left = -1, right = -1, cas = -1, level = -1;
        private boolean lc, rc, cc;
        private String bigText = "";

        Buds(Context c, Tones t) {
            super(c, 1, t);
        }

        @Override
        boolean allowed(WidgetPrefs p) {
            return p.buds;
        }

        @Override
        boolean bind() {
            JSONObject b = LockWidgets.cmf != null ? LockWidgets.cmf.optJSONObject("buds") : null;
            if (b == null) return false;
            left = b.optInt("left", -1); right = b.optInt("right", -1); cas = b.optInt("case", -1);
            lc = b.optBoolean("leftCharging"); rc = b.optBoolean("rightCharging"); cc = b.optBoolean("caseCharging");
            level = left >= 0 && right >= 0 ? Math.min(left, right) : Math.max(Math.max(left, right), b.optInt("level", -1));
            int big = level >= 0 ? level : cas;
            bigText = String.valueOf(big);
            return level >= 0 || cas >= 0;
        }

        @Override
        void content(Canvas c, float w, float h) {
            float pad = h * 0.14f;
            // значок наушников (дуга оголовья и две чашки) цветом primary
            float ix = pad, iy = pad, is = h * 0.15f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(is * 0.16f);
            p.setColor(t.primary);
            rect.set(ix, iy, ix + is, iy + is * 1.4f);
            c.drawArc(rect, 180, 180, false, p);
            p.setStyle(Paint.Style.FILL);
            rect.set(ix - is * 0.04f, iy + is * 0.62f, ix + is * 0.3f, iy + is * 1.05f);
            c.drawRoundRect(rect, is * 0.12f, is * 0.12f, p);
            rect.set(ix + is * 0.7f, iy + is * 0.62f, ix + is * 1.04f, iy + is * 1.05f);
            c.drawRoundRect(rect, is * 0.12f, is * 0.12f, p);
            // процент: крупно, жирно (M3E — выразительная типографика)
            int big = level >= 0 ? level : cas;
            int col = big <= 15 ? t.error : t.onSurface;
            text(c, bigText, HEAVY, h * 0.27f, col, pad, pad + h * 0.42f, Paint.Align.LEFT);
            float nw = tp.measureText(bigText);
            text(c, "%", BOLD, h * 0.13f, t.onSurfaceVariant, pad + nw + h * 0.01f, pad + h * 0.42f, Paint.Align.LEFT);
            if (lc || rc || cc) bolt(c, w - pad - h * 0.06f, pad + h * 0.07f, h * 0.11f);
            // полосы L / R / C (кейс) — ровными строками снизу вверх, у кейса дорожка приглушённее
            float thick = h * 0.05f, step = h * 0.105f, y = h - pad * 0.95f;
            if (cas >= 0 && level >= 0) { bar(c, "C", cas, pad, w - pad, y, h, thick); y -= step; }
            if (right >= 0) { bar(c, "R", right, pad, w - pad, y, h, thick); y -= step; }
            if (left >= 0) bar(c, "L", left, pad, w - pad, y, h, thick);
        }

        private void bar(Canvas c, String name, int lvl, float x0, float x1, float y, float h, float thick) {
            text(c, name, BOLD, h * 0.075f, t.onSurfaceVariant, x0, y + h * 0.027f, Paint.Align.LEFT);
            linear(c, x0 + h * 0.1f, x1, y, lvl / 100f, thick, lvl <= 15 ? t.error : t.primary, t.secondaryContainer);
        }

        /** Молния «заряжается» цветом tertiary. */
        private void bolt(Canvas c, float cx, float cy, float s) {
            path.reset();
            path.moveTo(cx + s * 0.1f, cy - s * 0.6f);
            path.lineTo(cx - s * 0.35f, cy + s * 0.08f);
            path.lineTo(cx - s * 0.02f, cy + s * 0.08f);
            path.lineTo(cx - s * 0.1f, cy + s * 0.6f);
            path.lineTo(cx + s * 0.35f, cy - s * 0.08f);
            path.lineTo(cx + s * 0.02f, cy - s * 0.08f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.tertiary);
            c.drawPath(path, p);
        }
    }

    // ───────── шаги ─────────

    /** Шаги 1×1: волнистое кольцо прогресса M3 Expressive (волна — пройдено, гладкая дорожка — осталось), число в центре. */
    static final class Steps extends Card {
        private int steps, goal = 8000;
        private String num = "";
        private final Path wave = new Path();
        private float builtFor = -1, builtW, builtH;

        Steps(Context c, Tones t) {
            super(c, 1, t);
        }

        @Override
        boolean allowed(WidgetPrefs p) {
            goal = p.goal;
            return p.steps;
        }

        @Override
        boolean bind() {
            JSONObject w = LockWidgets.cmf != null ? LockWidgets.cmf.optJSONObject("watch") : null;
            if (w == null) return false;
            steps = w.optInt("steps", 0);
            num = steps >= 10000 ? String.format(java.util.Locale.US, "%.1fK", steps / 1000f) : String.valueOf(steps);
            return true;
        }

        @Override
        void content(Canvas c, float w, float h) {
            float cx = w / 2, cy = h / 2, R = h * 0.36f, thick = h * 0.06f;
            float frac = Math.min(1f, steps / (float) goal);
            float sweep = 360f * frac;
            // дорожка — от конца волны (с зазором) до старта
            float gapDeg = (float) Math.toDegrees((thick * 1.6f) / R);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thick);
            if (frac < 1f) {
                p.setColor(t.secondaryContainer);
                rect.set(cx - R, cy - R, cx + R, cy + R);
                float start = -90 + sweep + (frac > 0 ? gapDeg : 0), end = 270 - gapDeg;
                if (end > start) c.drawArc(rect, start, end - start, false, p);
            }
            if (frac > 0f) {
                if (builtFor != frac || builtW != w || builtH != h) buildWave(cx, cy, R, thick, sweep);
                p.setColor(steps >= goal ? t.tertiary : t.primary);
                c.drawPath(wave, p);
            }
            float size = h * (num.length() > 4 ? 0.18f : 0.22f);
            text(c, num, HEAVY, size, t.onSurface, cx, cy + size * 0.18f, Paint.Align.CENTER);
            text(c, L.t("steps", "шагов"), MEDIUM, h * 0.08f, t.onSurfaceVariant, cx, cy + h * 0.17f, Paint.Align.CENTER);
        }

        /** Волна вдоль дуги: радиус R + амплитуда·sin(частота·угол); строится только при смене прогресса или размера. */
        private void buildWave(float cx, float cy, float R, float thick, float sweep) {
            builtFor = Math.min(1f, steps / (float) goal);
            builtW = getWidth();
            builtH = getHeight();
            wave.reset();
            float amp = thick * 0.38f;
            int waves = 10;                                            // гребней на полный круг
            int n = Math.max(8, Math.round(sweep * 1.2f));
            for (int i = 0; i <= n; i++) {
                double a = Math.toRadians(-90 + sweep * i / n);
                double phase = Math.toRadians(sweep * i / n) * waves;
                double r = R + amp * Math.sin(phase);
                float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
                if (i == 0) wave.moveTo(x, y);
                else wave.lineTo(x, y);
            }
        }
    }

    // ───────── музыка ─────────

    /**
     * Музыка 2×1: обложка в форме «печенье» (M3E Cookie, 9 выступов), название и исполнитель, волнистая полоса
     * прогресса (течёт, пока играет), кнопки: «назад», играть/пауза — заливка primary, форма на пружине
     * (пауза: круг; играет: скруглённый квадрат), «вперёд». Касания — только по кнопкам.
     */
    static final class Music extends Card {
        private final float[] btnX = new float[3];
        private float btnY, btnR;
        private int pressed = Integer.MIN_VALUE;
        private final Spring morph = new Spring(), press = new Spring();
        private long last;
        private float phase;
        private final Path cookie = new Path(), progress = new Path();
        private final Matrix artM = new Matrix();
        private BitmapShader artShader;
        private Bitmap artFor;
        private String title = "", artist = "";
        private float titleW = -1;
        private CharSequence titleShown = "", artistShown = "";
        private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Music(Context c, Tones t) {
            super(c, 2, t);
        }

        @Override
        boolean allowed(WidgetPrefs p) {
            return p.music;
        }

        @Override
        boolean bind() {
            if (!((LockWidgets.Media.controller != null || LockWidgets.Media.demo) && LockWidgets.Media.title != null)) return false;
            String nt = LockWidgets.Media.title, na = LockWidgets.Media.artist != null ? LockWidgets.Media.artist : "";
            if (!nt.equals(title) || !na.equals(artist)) { title = nt; artist = na; titleW = -1; }
            morph.target = LockWidgets.Media.playing ? 1f : 0f;
            return true;
        }

        @Override
        void content(Canvas c, float w, float h) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.033f, (now - last) / 1000f);
            last = now;
            boolean moving = morph.step(dt, 600f, 0.6f) | press.step(dt, 900f, 0.55f);
            boolean playing = LockWidgets.Media.playing;
            if (playing) phase += dt * 2.2f;                           // волна течёт, пока играет

            float pad = h * 0.12f, s = h - 2 * pad;
            drawArt(c, pad, pad, s);
            float x0 = pad + s + h * 0.12f, tw = w - x0 - pad;
            if (titleW != tw) {
                tp.setTypeface(BOLD);
                tp.setTextSize(h * 0.15f);
                titleShown = TextUtils.ellipsize(title, tp, tw, TextUtils.TruncateAt.END);
                tp.setTypeface(REGULAR);
                tp.setTextSize(h * 0.105f);
                artistShown = TextUtils.ellipsize(artist, tp, tw, TextUtils.TruncateAt.END);
                titleW = tw;
            }
            text(c, titleShown.toString(), BOLD, h * 0.15f, t.onSurface, x0, pad + h * 0.12f, Paint.Align.LEFT);
            if (artistShown.length() > 0) text(c, artistShown.toString(), REGULAR, h * 0.105f, t.onSurfaceVariant, x0, pad + h * 0.27f, Paint.Align.LEFT);

            // волнистый прогресс трека
            float py = pad + h * 0.40f, thick = h * 0.035f;
            float frac = LockWidgets.Media.progress();
            wavyLinear(c, x0, x0 + tw, py, frac, thick, playing);

            // кнопки
            btnR = h * 0.15f;
            btnY = h - pad - btnR;
            btnX[0] = x0 + btnR * 0.6f;
            btnX[1] = x0 + tw / 2;
            btnX[2] = x0 + tw - btnR * 0.6f;
            skip(c, btnX[0], btnY, btnR * 0.5f, false, pressed == -1);
            skip(c, btnX[2], btnY, btnR * 0.5f, true, pressed == 1);
            playButton(c, btnX[1], btnY, btnR);

            // волна течёт, только пока карточку действительно видно: экран блокировки после разблокировки прячется
            // прозрачностью — без этой проверки она рисовала бы невидимые кадры всё время, пока играет музыка
            if (moving || (playing && onScreen())) postInvalidateOnAnimation();
            else last = 0;
        }

        private boolean onScreen() {
            if (getWindowVisibility() != VISIBLE || !isShown()) return false;
            float a = 1f;
            View v = this;
            while (v != null) {
                a *= v.getAlpha() * v.getTransitionAlpha();
                if (a <= 0.01f) return false;
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            return true;
        }

        /** Обложка в форме «печенье» (9 мягких выступов); без обложки — тон primaryContainer и нота. */
        private void drawArt(Canvas c, float x, float y, float s) {
            cookie.reset();
            float cx = x + s / 2, cy = y + s / 2, R = s / 2, depth = R * 0.07f;
            int n = 72;
            for (int i = 0; i <= n; i++) {
                double a = 2 * Math.PI * i / n;
                double r = R - depth + depth * Math.cos(9 * a);
                float px = cx + (float) (r * Math.cos(a)), py = cy + (float) (r * Math.sin(a));
                if (i == 0) cookie.moveTo(px, py);
                else cookie.lineTo(px, py);
            }
            cookie.close();
            Bitmap b = LockWidgets.Media.art;
            if (b != null && !b.isRecycled()) {
                if (artShader == null || artFor != b) {
                    artShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    artFor = b;
                }
                float k = Math.max(s / b.getWidth(), s / b.getHeight());
                artM.setScale(k, k);
                artM.postTranslate(x + (s - b.getWidth() * k) / 2, y + (s - b.getHeight() * k) / 2);
                artShader.setLocalMatrix(artM);
                art.setShader(artShader);
                c.drawPath(cookie, art);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(t.primaryContainer);
                c.drawPath(cookie, p);
                // нота
                p.setColor(t.onPrimaryContainer);
                c.drawCircle(cx - s * 0.08f, cy + s * 0.12f, s * 0.09f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.045f);
                c.drawLine(cx + s * 0.0f, cy + s * 0.12f, cx + s * 0.0f, cy - s * 0.2f, p);
                c.drawLine(cx + s * 0.0f, cy - s * 0.2f, cx + s * 0.14f, cy - s * 0.14f, p);
            }
        }

        /** Волнистый линейный индикатор M3E: пройденная часть — волна (фаза бежит, пока играет), остаток — дорожка. */
        private void wavyLinear(Canvas c, float x0, float x1, float y, float frac, float thick, boolean playing) {
            frac = Math.max(0f, Math.min(1f, frac));
            float ax = x0 + (x1 - x0) * frac, gap = thick * 1.2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thick);
            p.setColor(t.secondaryContainer);
            if (ax + gap + thick < x1) c.drawLine(Math.max(x0 + thick / 2, ax + gap + thick / 2), y, x1 - thick / 2, y, p);
            if (ax - x0 > thick) {
                progress.reset();
                float amp = playing ? thick * 0.9f : 0f, wl = thick * 7f;
                int n = Math.max(2, Math.round((ax - x0) / 3f));
                for (int i = 0; i <= n; i++) {
                    float xx = x0 + thick / 2 + (ax - x0 - thick) * i / n;
                    float yy = y + amp * (float) Math.sin((xx - x0) / wl * 2 * Math.PI - phase * 2 * Math.PI);
                    if (i == 0) progress.moveTo(xx, yy);
                    else progress.lineTo(xx, yy);
                }
                p.setColor(t.primary);
                c.drawPath(progress, p);
            }
        }

        /** Кнопка играть/пауза: заливка primary, форма — от круга (пауза) к скруглённому квадрату (играет) по пружине. */
        private void playButton(Canvas c, float cx, float cy, float r) {
            float k = Math.max(0f, Math.min(1.2f, morph.value));
            float scale = 1f - 0.08f * Math.max(0f, press.value);
            float rr = r * scale, corner = rr * (1f - 0.55f * k);
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.primary);
            c.drawRoundRect(rect, corner, corner, p);
            p.setColor(t.onPrimary);
            float s = rr * 0.42f;
            if (LockWidgets.Media.playing) {
                float bw = s * 0.36f;
                rect.set(cx - s * 0.62f, cy - s, cx - s * 0.62f + bw, cy + s);
                c.drawRoundRect(rect, bw / 2, bw / 2, p);
                rect.set(cx + s * 0.62f - bw, cy - s, cx + s * 0.62f, cy + s);
                c.drawRoundRect(rect, bw / 2, bw / 2, p);
            } else {
                path.reset();
                path.moveTo(cx - s * 0.55f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.55f, cy + s);
                path.close();
                c.drawPath(path, p);
            }
        }

        /** «Назад» / «вперёд»: треугольник и черта цветом onSurface; нажатие — лёгкая подложка тона primaryContainer. */
        private void skip(Canvas c, float cx, float cy, float s, boolean next, boolean down) {
            if (down) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(t.primaryContainer);
                c.drawCircle(cx, cy, s * 1.9f, p);
            }
            float dir = next ? 1f : -1f;
            path.reset();
            path.moveTo(cx - dir * s * 0.7f, cy - s * 0.8f);
            path.lineTo(cx + dir * s * 0.5f, cy);
            path.lineTo(cx - dir * s * 0.7f, cy + s * 0.8f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.onSurface);
            c.drawPath(path, p);
            rect.set(cx + dir * s * 0.55f - s * 0.11f, cy - s * 0.8f, cx + dir * s * 0.55f + s * 0.11f, cy + s * 0.8f);
            c.drawRoundRect(rect, s * 0.11f, s * 0.11f, p);
        }

        private int hit(float x, float y) {
            if (Math.abs(y - btnY) > btnR * 1.4f) return Integer.MIN_VALUE;
            for (int i = 0; i < 3; i++) if (Math.abs(x - btnX[i]) < btnR * 1.2f) return i - 1;
            return Integer.MIN_VALUE;
        }

        /** Нажатия — только по кнопкам; остальное (свайп разблокировки) проходит мимо. */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = hit(e.getX(), e.getY());
                    if (pressed == Integer.MIN_VALUE) return false;
                    if (pressed == 0) press.target = 1f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressed != Integer.MIN_VALUE && hit(e.getX(), e.getY()) == pressed) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        LockWidgets.Media.action(pressed);
                    }
                    pressed = Integer.MIN_VALUE;
                    press.target = 0f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = Integer.MIN_VALUE;
                    press.target = 0f;
                    invalidate();
                    return true;
            }
            return pressed != Integer.MIN_VALUE;
        }
    }
}
