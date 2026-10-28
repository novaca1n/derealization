package dev.novacain.ntclocks.anim;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Панель громкости как на iPhone (AnimPrefs.volume = ios). Окно, таймер и логика остаются у Nothing
 * (VolumeDialogImpl), их панель только прячется (transitionAlpha 0 — её анимации это свойство не трогают), а поверх,
 * в корне того же окна, рисуется своя капсула: матовое стекло (размытие между окнами), белая заливка снизу, значок
 * потока внизу. Как в iOS: по нажатию капсула широкая, через 1.4 с простоя сжимается в тонкую линию у края, по
 * таймауту Nothing уезжает за край. Её можно тянуть пальцем (относительно, как в iOS); за краями шкалы — резина.
 * Все движения — пружины с сохранением скорости, так что любое прерывание продолжается без рывка.
 */
final class IosVolume {
    private IosVolume() {
    }

    private static final int TAG = 0x7e4e5406;
    private static WeakReference<Hud> current = new WeakReference<>(null);

    /** Показ окна (onShowListener Nothing): капсула на месте панели Nothing, панель Nothing — невидима. */
    static void show(Object dlg) {
        Hud h = attach(dlg);
        if (h != null) h.show();
    }

    /** Режим сменился — вернуть панель Nothing и убрать капсулу. */
    static void detach(Object dlg) {
        try {
            View dv = (View) XposedHelpers.getObjectField(dlg, "mDialogView");
            if (dv == null) return;
            dv.setTransitionAlpha(1f);
            if (dv.getParent() instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) dv.getParent();
                View h = root.findViewWithTag(TAG);
                if (h != null) root.removeView(h);
            }
        } catch (Throwable ignored) {
        }
    }

    static void hide() {
        Hud h = current.get();
        if (h != null) h.hide();
    }

    /** Уровень/поток поменялись (onStateChangedH) — заливка доезжает пружиной. */
    static void sync(Object dlg) {
        Hud h = current.get();
        if (h != null && h.dlg == dlg) h.sync(true);
    }

    /** Нажатие кнопки громкости при открытой панели: раскрыть, «вздрогнуть», на краю — резина. */
    static void press(Object dlg, boolean stuckMax, boolean stuckMin) {
        Hud h = current.get();
        if (h != null && h.dlg == dlg) h.press(stuckMax, stuckMin);
    }

    private static Hud attach(Object dlg) {
        try {
            View dv = (View) XposedHelpers.getObjectField(dlg, "mDialogView");
            if (dv == null || !(dv.getParent() instanceof ViewGroup)) return null;
            ViewGroup root = (ViewGroup) dv.getParent();
            Hud h = (Hud) root.findViewWithTag(TAG);
            if (h == null) {
                h = new Hud(root.getContext(), dlg, dv);
                h.setTag(TAG);
                root.addView(h, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Hud hud = h;
                root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> hud.layout(0, 0, r - l, b - t));
            }
            h.right = XposedHelpers.getBooleanField(dlg, "mIsVolumeKeyOnRight");
            dv.setTransitionAlpha(0f);
            current = new WeakReference<>(h);
            return h;
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: громкость iPhone — " + t);
            detach(dlg);
            return null;
        }
    }

    /** Пружина в физическом времени (response — период, с; damping — доля критического), скорость сохраняется. */
    static final class Sp {
        float x, v, t;
        private float k, c;
        private final float eps;

        Sp(float response, float damping, float eps) {
            set(response, damping);
            this.eps = eps;
        }

        void set(float response, float damping) {
            float w = (float) (2 * Math.PI / response);
            k = w * w;
            c = 2 * damping * w;
        }

        void snap(float value) {
            x = t = value;
            v = 0f;
        }

        /** Шаг на dt секунд; false — пружина успокоилась. */
        boolean step(float dt) {
            if (x == t && v == 0f) return false;
            int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                v += (-k * (x - t) - c * v) * h;
                x += v * h;
            }
            if (Math.abs(x - t) < eps && Math.abs(v) < eps * 10f) {
                x = t;
                v = 0f;
                return false;
            }
            return true;
        }
    }

    private enum Glyph { SPEAKER, BELL, HEADPHONES, ALARM }

    @SuppressLint("ViewConstructor")
    static final class Hud extends View implements Runnable {
        final Object dlg;
        private final View dialogView;
        boolean right;
        private final float d;
        private final float wExp, wThin, hgt;

        // presence — на месте (1) / за краем (0); expand — широкая (1) / тонкая линия (0); fill — заливка 0..1;
        // stretch — резина, px (+ вверх, − вниз); pop — «вздрагивание», доля ширины
        private final Sp presence = new Sp(0.42f, 0.8f, 0.0005f);
        private final Sp expand = new Sp(0.38f, 0.82f, 0.0005f);
        private final Sp fill = new Sp(0.3f, 0.92f, 0.0003f);
        private final Sp stretch;
        private final Sp pop = new Sp(0.32f, 0.5f, 0.0003f);
        private long lastFrame;
        private boolean ticking;

        private final boolean glass;              // размытие между окнами включено в системе
        private boolean regionShown;
        private static Class<?> frameCb;
        private float alpha, rad;
        private final android.view.ViewTreeObserver.OnPreDrawListener pre = () -> {
            pushBlur();
            return true;
        };
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                rim = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path capsule = new Path(), icon = new Path();
        private final int[] loc = new int[2], own = new int[2];
        private float ax0, ax1, ay;
        private Glyph kind = Glyph.SPEAKER;
        private float glyphLevel = 1f;
        private boolean muted;

        private boolean tracking, outside, atEdge;
        private float downY, downFill;
        private int lastSent = Integer.MIN_VALUE;
        private final Runnable collapse = () -> {
            expand.t = 0f;
            kick();
        };

        Hud(Context ctx, Object dlg, View dialogView) {
            super(ctx);
            this.dlg = dlg;
            this.dialogView = dialogView;
            d = ctx.getResources().getDisplayMetrics().density;
            wExp = 50 * d;
            wThin = 7 * d;
            hgt = 176 * d;
            stretch = new Sp(0.36f, 0.62f, 0.2f);
            fillPaint.setColor(0xFFFFFFFF);
            boolean g;
            try {
                g = ctx.getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled();
            } catch (Throwable t) {
                g = false;
            }
            glass = g;
            track.setColor(glass ? 0x8C1C1C1E : 0xE61C1C1E);   // на стекле — тонировка, без него — плотнее
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1f * d);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            presence.snap(0f);
            expand.snap(1f);
        }

        /**
         * Окно громкости — wrap_content: своим размером капсула окно не раздувает (0 × 0), а на весь корень её
         * растягивает слушатель раскладки корня (FrameLayout перемеряет MATCH_PARENT-детей, только если их больше одного).
         */
        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : 0,
                    MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0);
        }

        void show() {
            removeCallbacks(collapse);
            presence.set(0.42f, 0.78f);
            presence.t = 1f;
            if (presence.x <= 0.001f) {          // выезжает из-за края — сразу широкой и с текущим уровнем
                expand.snap(1f);
                stretch.snap(0f);
                pop.snap(0f);
                sync(false);
            } else {
                expand.t = 1f;
                sync(true);
            }
            postDelayed(collapse, 1400);
            kick();
        }

        void hide() {
            removeCallbacks(collapse);
            tracking = false;
            presence.set(0.3f, 1f);              // без перелёта; окно Nothing закроется через 420 мс
            presence.t = 0f;
            kick();
        }

        void press(boolean stuckMax, boolean stuckMin) {
            sync(true);
            removeCallbacks(collapse);
            boolean wasThin = expand.x < 0.5f;
            expand.t = 1f;
            if (stuckMax || stuckMin) {
                float w = (float) (2 * Math.PI / 0.36f);
                stretch.v += (stuckMax ? 1 : -1) * 15 * d * w / 0.55f;
            } else if (!wasThin) {
                pop.v += 2.6f;
            }
            postDelayed(collapse, 1400);
            kick();
        }

        /** Поток, уровень, значок — из активной строки Nothing. */
        void sync(boolean animate) {
            try {
                Object row = XposedHelpers.callMethod(dlg, "getActiveRow");
                Object ss = XposedHelpers.getObjectField(row, "ss");
                if (ss == null) return;
                int level = XposedHelpers.getIntField(ss, "level");
                int min = XposedHelpers.getIntField(ss, "levelMin"), max = XposedHelpers.getIntField(ss, "levelMax");
                muted = XposedHelpers.getBooleanField(ss, "muted");
                int stream = XposedHelpers.getIntField(row, "stream");
                boolean bt = XposedHelpers.getBooleanField(ss, "routedToBluetooth");
                kind = stream == 2 || stream == 5 ? Glyph.BELL : stream == 4 ? Glyph.ALARM : bt ? Glyph.HEADPHONES : Glyph.SPEAKER;
                float f = muted || max <= min ? 0f : (level - min) / (float) (max - min);
                glyphLevel = f;
                if (tracking) return;
                lastSent = level;
                if (animate) fill.t = f;
                else fill.snap(f);
                kick();
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: громкость iPhone, уровень — " + t);
            }
        }

        private void kick() {
            if (!ticking) {
                ticking = true;
                lastFrame = System.nanoTime();
                postOnAnimation(this);
            }
            invalidate();
        }

        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = Math.min((now - lastFrame) / 1e9f, 1 / 30f);
            lastFrame = now;
            if (!isShown() || !anchor()) {           // окно Nothing ещё не разложено — ждём, иначе выезд пройдёт
                if (presence.t == 0f && presence.x == 0f) ticking = false;   // невидимым и капсула возникнет
                else postOnAnimation(this);                                   // сразу на месте
                return;
            }
            boolean busy = presence.step(dt);
            busy |= expand.step(dt);
            busy |= fill.step(dt);
            busy |= stretch.step(dt);
            busy |= pop.step(dt);
            invalidate();
            if (busy || tracking) postOnAnimation(this);
            else ticking = false;
        }

        /** Где у Nothing колонка ползунков (без сдвига их анимации) — капсула встаёт туда же. */
        private boolean anchor() {
            try {
                View rows = (View) XposedHelpers.getObjectField(dlg, "mDialogRowsView");
                View col = rows != null && rows.getParent() instanceof View ? (View) rows.getParent() : rows;
                if (col == null || col.getWidth() == 0) return false;
                col.getLocationInWindow(loc);
                getLocationInWindow(own);
                float x = loc[0] - dialogView.getTranslationX() - own[0];
                ax0 = x;
                ax1 = x + col.getWidth();
                ay = loc[1] - own[1] + col.getHeight() / 2f;
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        /** Капсула в текущем кадре (без сдвига появления). */
        private void geometry() {
            float e = expand.x;
            float w = (wThin + (wExp - wThin) * e) * (1f + pop.x);
            float s = stretch.x, sa = Math.abs(s);
            w *= 1f - 0.16f * sa / (sa + 40 * d);
            float top = ay - hgt / 2f - Math.max(s, 0f), bottom = ay + hgt / 2f + Math.max(-s, 0f);
            float inset = 3 * d * (1f - clamp(e));        // тонкая линия ближе к краю
            if (right) {
                float edge = ax1 + inset;
                r.set(edge - w, top, edge, bottom);
            } else {
                float edge = ax0 - inset;
                r.set(edge, top, edge + w, bottom);
            }
        }

        /** Капсула этого кадра с учётом появления: r, alpha, rad; false — рисовать нечего. */
        private boolean frame() {
            if (presence.x <= 0.0005f && presence.t == 0f || !anchor()) return false;
            geometry();
            float p = presence.x;
            float off = right ? (1f - p) * (getWidth() - r.left + 24 * d) : -(1f - p) * (r.right + 24 * d);
            r.offset(off, 0f);
            alpha = clamp(p * 2.2f);
            rad = r.width() / 2f;
            return true;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(pre);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(pre);
            regionShown = false;
            super.onDetachedFromWindow();
        }

        /**
         * Матовое стекло: регион размытия окна под капсулой. Как BackgroundBlurDrawable.Aggregator — через кадровый
         * колбэк RenderThread и ViewRootImpl.dispatchBlurRegions, только прямоугольник свой (у drawable он берётся из
         * слушателя позиции RenderNode, а тот в этом окне не срабатывает — регион оставался пустым).
         */
        private void pushBlur() {
            if (!glass) return;
            float[][] regions;
            if (frame()) {
                getLocationInWindow(own);
                regions = new float[][]{{(int) (30 * d), alpha, r.left + own[0], r.top + own[1], r.right + own[0], r.bottom + own[1], rad, rad, rad, rad}};
                regionShown = true;
            } else if (regionShown) {
                regions = new float[0][];
                regionShown = false;
            } else {
                return;
            }
            try {
                Object vri = XposedHelpers.callMethod(this, "getViewRootImpl");
                if (vri == null) return;
                // HardwareRenderer.FrameDrawingCallback скрыт в SDK — через Proxy; номер кадра — последний аргумент
                // (onFrameDraw(long) или onFrameDraw(int, long), второй ждёт FrameCommitCallback — null)
                if (frameCb == null) frameCb = Class.forName("android.graphics.HardwareRenderer$FrameDrawingCallback");
                Object cb = java.lang.reflect.Proxy.newProxyInstance(frameCb.getClassLoader(), new Class<?>[]{frameCb}, (proxy, m, args) -> {
                    if ("onFrameDraw".equals(m.getName()) && args != null && args[args.length - 1] instanceof Long) {
                        try {
                            XposedHelpers.callMethod(vri, "dispatchBlurRegions", regions, args[args.length - 1]);
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                    if ("hashCode".equals(m.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(m.getName())) return proxy == args[0];
                    if ("toString".equals(m.getName())) return "ntweaker-blur";
                    return null;
                });
                XposedHelpers.callMethod(vri, "registerRtFrameCallback", cb);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: громкость iPhone, стекло — " + t);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!frame()) return;
            float a = alpha;
            track.setAlpha((int) (Color.alpha(track.getColor()) * a));
            c.drawRoundRect(r, rad, rad, track);

            capsule.reset();
            capsule.addRoundRect(r, rad, rad, Path.Direction.CW);
            float f = clamp(fill.x);
            float fillTop = r.bottom - r.height() * f;
            c.save();
            c.clipPath(capsule);
            fillPaint.setAlpha((int) (255 * a));
            c.drawRect(r.left, fillTop, r.right, r.bottom, fillPaint);

            float ia = clamp((expand.x - 0.45f) / 0.55f) * a;
            if (ia > 0.01f) {
                float size = 19 * d * (0.8f + 0.2f * clamp(expand.x));
                float cx = r.centerX(), cy = r.bottom - 24 * d;
                buildGlyph(cx, cy, size);
                c.save();                               // над пустой частью — светлый
                c.clipRect(r.left, r.top, r.right, fillTop);
                drawGlyph(c, 0xB3FFFFFF, ia);
                c.restore();
                c.save();                               // над заливкой — серый, как в iOS
                c.clipRect(r.left, fillTop, r.right, r.bottom);
                drawGlyph(c, 0xFF8A8A8E, ia);
                c.restore();
            }
            c.restore();

            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x59FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            rim.setAlpha((int) (255 * a));
            float h = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, rad - h, rad - h, rim);
        }

        private float strokeW;

        /** Значки в духе SF Symbols: динамик с волнами по уровню (или перечёркнутый), колокол, наушники, будильник. */
        private void buildGlyph(float cx, float cy, float s) {
            icon.reset();
            strokeW = 0.09f * s;
            switch (kind) {
                case BELL: {
                    float t = cy - 0.36f * s, b = cy + 0.2f * s;
                    icon.moveTo(cx - 0.4f * s, b);
                    icon.cubicTo(cx - 0.3f * s, b - 0.08f * s, cx - 0.3f * s, cy, cx - 0.3f * s, cy - 0.08f * s);
                    icon.cubicTo(cx - 0.3f * s, t + 0.02f * s, cx - 0.16f * s, t, cx, t);
                    icon.cubicTo(cx + 0.16f * s, t, cx + 0.3f * s, t + 0.02f * s, cx + 0.3f * s, cy - 0.08f * s);
                    icon.cubicTo(cx + 0.3f * s, cy, cx + 0.3f * s, b - 0.08f * s, cx + 0.4f * s, b);
                    icon.close();
                    icon.addCircle(cx, cy + 0.32f * s, 0.09f * s, Path.Direction.CW);
                    break;
                }
                case HEADPHONES: {
                    icon.addRoundRect(cx - 0.46f * s, cy + 0.02f * s, cx - 0.24f * s, cy + 0.42f * s, 0.07f * s, 0.07f * s, Path.Direction.CW);
                    icon.addRoundRect(cx + 0.24f * s, cy + 0.02f * s, cx + 0.46f * s, cy + 0.42f * s, 0.07f * s, 0.07f * s, Path.Direction.CW);
                    break;
                }
                case ALARM: {
                    icon.addCircle(cx, cy + 0.04f * s, 0.36f * s, Path.Direction.CW);
                    break;
                }
                default: {
                    icon.addRoundRect(cx - 0.5f * s, cy - 0.15f * s, cx - 0.26f * s, cy + 0.15f * s, 0.04f * s, 0.04f * s, Path.Direction.CW);
                    icon.moveTo(cx - 0.3f * s, cy - 0.15f * s);
                    icon.lineTo(cx + 0.02f * s, cy - 0.4f * s);
                    icon.lineTo(cx + 0.02f * s, cy + 0.4f * s);
                    icon.lineTo(cx - 0.3f * s, cy + 0.15f * s);
                    icon.close();
                    break;
                }
            }
        }

        private final RectF arc = new RectF();

        private void drawGlyph(Canvas c, int color, float alpha) {
            glyph.setColor(color);
            glyph.setAlpha((int) (Color.alpha(color) * alpha));
            float s = 19 * d * (0.8f + 0.2f * clamp(expand.x));
            float cx = r.centerX(), cy = r.bottom - 24 * d;
            glyph.setStyle(Paint.Style.FILL);
            if (kind == Glyph.ALARM) {
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(strokeW);
                c.drawPath(icon, glyph);
                c.drawLine(cx, cy + 0.04f * s, cx, cy - 0.16f * s, glyph);
                c.drawLine(cx, cy + 0.04f * s, cx + 0.14f * s, cy + 0.12f * s, glyph);
                return;
            }
            c.drawPath(icon, glyph);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(strokeW);
            if (kind == Glyph.HEADPHONES) {
                arc.set(cx - 0.4f * s, cy - 0.38f * s, cx + 0.4f * s, cy + 0.42f * s);
                c.drawArc(arc, 180, 180, false, glyph);
            } else if (kind == Glyph.SPEAKER) {
                if (muted || glyphLevel <= 0f) {
                    c.drawLine(cx + 0.14f * s, cy - 0.16f * s, cx + 0.46f * s, cy + 0.16f * s, glyph);
                    c.drawLine(cx + 0.46f * s, cy - 0.16f * s, cx + 0.14f * s, cy + 0.16f * s, glyph);
                } else {
                    int waves = glyphLevel <= 0.34f ? 1 : glyphLevel <= 0.67f ? 2 : 3;
                    for (int i = 0; i < waves; i++) {
                        float rr = (0.17f + 0.14f * i) * s;
                        arc.set(cx + 0.04f * s - rr, cy - rr, cx + 0.04f * s + rr, cy + rr);
                        c.drawArc(arc, -42, 84, false, glyph);
                    }
                }
            }
        }

        // ───────── касания: тянуть как в iOS ─────────

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (presence.t == 0f || !anchor()) return false;
                    geometry();
                    float slop = 16 * d;
                    // мимо капсулы — как касание вне панели (невидимые кнопки Nothing под ней не должны срабатывать)
                    outside = e.getX() < Math.min(r.left, ax0) - slop || e.getX() > Math.max(r.right, ax0 + wExp) + slop
                            || e.getY() < r.top - slop || e.getY() > r.bottom + slop;
                    if (outside) return true;
                    tracking = true;
                    removeCallbacks(collapse);
                    expand.t = 1f;
                    downY = e.getY();
                    downFill = clamp(fill.x);
                    fill.snap(downFill);
                    atEdge = downFill <= 0f || downFill >= 1f;
                    reschedule();
                    kick();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (outside || !tracking) return true;
                    float f = downFill + (downY - e.getY()) / hgt, over = 0f;
                    if (f > 1f) {
                        over = (f - 1f) * hgt;
                        f = 1f;
                    } else if (f < 0f) {
                        over = f * hgt;
                        f = 0f;
                    }
                    fill.snap(f);
                    float l = 30 * d, oa = Math.abs(over);
                    stretch.snap(Math.signum(over) * l * (1f - 1f / (1f + 0.55f * oa / l)));
                    boolean edge = f <= 0f || f >= 1f;
                    if (edge && !atEdge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    atEdge = edge;
                    send(f);
                    reschedule();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (outside) {
                        outside = false;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            try {
                                XposedHelpers.callMethod(dlg, "dismissH", 1);   // DISMISS_REASON_TOUCH_OUTSIDE
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                    tracking = false;
                    stretch.t = 0f;
                    sync(true);                          // заливка доезжает до ступени громкости
                    postDelayed(collapse, 1400);
                    reschedule();
                    kick();
                    return true;
                }
                default:
                    return true;
            }
        }

        private void send(float f) {
            try {
                Object row = XposedHelpers.callMethod(dlg, "getActiveRow");
                Object ss = XposedHelpers.getObjectField(row, "ss");
                int min = XposedHelpers.getIntField(ss, "levelMin"), max = XposedHelpers.getIntField(ss, "levelMax");
                int level = min + Math.round(f * (max - min));
                if (level == lastSent) return;
                lastSent = level;
                glyphLevel = (level - min) / (float) Math.max(1, max - min);
                muted = false;
                Object ctl = XposedHelpers.getObjectField(dlg, "mController");
                XposedHelpers.callMethod(ctl, "setStreamVolume", XposedHelpers.getIntField(row, "stream"), level);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: громкость iPhone, установка — " + t);
            }
        }

        private void reschedule() {
            try {
                XposedHelpers.callMethod(dlg, "rescheduleTimeoutH");
            } catch (Throwable ignored) {
            }
        }
    }
}
