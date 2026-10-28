package dev.novacain.ntclocks.anim;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Настоящее размытие обоев под папками и виджетами — как Lucent в Nothing OS 5. Каждый элемент рисует у себя
 * BackgroundBlurDrawable (ViewRootImpl.createBackgroundBlurDrawable): это «дырка» в окне лаунчера, под которой
 * SurfaceFlinger размывает то, что ниже, — живые обои (Atmosphere) тоже, с параллаксом и анимациями. Поверх — зерно.
 *
 * Область размытия знает только своё место на экране, но не прозрачность и масштаб родителей. Поэтому перед каждым
 * кадром (OnPreDraw окна лаунчера) пересчитываем ей альфу (произведение альф до корня, 0 — если вид скрыт) и радиус
 * углов (с учётом масштаба: стол отъезжает при открытии приложения).
 */
public final class LucentBlur {
    private LucentBlur() {
    }

    private static final class Entry {
        final WeakReference<View> anchor;
        final Drawable d;
        float corner;
        int alpha = -1;
        float shownCorner = -1;
        float[] radii;          // tl, tr, bl, br — если углы разные (фон-подложка вида)
        boolean off;

        Entry(View anchor, Drawable d) {
            this.anchor = new WeakReference<>(anchor);
            this.d = d;
        }
    }

    private static final ArrayList<Entry> entries = new ArrayList<>();
    private static final java.util.WeakHashMap<View, Boolean> roots = new java.util.WeakHashMap<>();
    private static Method setCorner, setCorner4, setRadius, setColor;
    private static boolean broken;
    private static Paint grainPaint;
    private static final Path path = new Path();

    /** Размытие для ключа (вид с фоном или PreviewBackground папки); создаётся один раз и живёт в доп. поле ключа. */
    static Entry entry(Object key, View anchor) {
        if (broken) return null;
        Object e = XposedHelpers.getAdditionalInstanceField(key, "ntBlur");
        if (e != null) return (Entry) e;
        try {
            Object root = XposedHelpers.callMethod(anchor, "getViewRootImpl");
            if (root == null) return null;
            Drawable d = (Drawable) XposedHelpers.callMethod(root, "createBackgroundBlurDrawable");
            if (setCorner == null) {
                setCorner = d.getClass().getMethod("setCornerRadius", float.class);
                setRadius = d.getClass().getMethod("setBlurRadius", int.class);
                setCorner4 = d.getClass().getMethod("setCornerRadius", float.class, float.class, float.class, float.class);
                setColor = d.getClass().getMethod("setColor", int.class);
            }
            Entry n = new Entry(anchor, d);
            if (DEBUG) XposedBridge.log("ntweaker: системное размытие для " + anchor.getClass().getName() + " id " + anchor.getId()
                    + " (обои: live=" + wallLive + ", копия=" + (wallBlur != null) + ")\n" + android.util.Log.getStackTraceString(new Throwable()).split("\n", 8)[3]
                    + " " + android.util.Log.getStackTraceString(new Throwable()).split("\n", 9)[4]);
            XposedHelpers.setAdditionalInstanceField(key, "ntBlur", n);
            entries.add(n);
            watch(anchor.getRootView());
            return n;
        } catch (Throwable t) {
            broken = true;
            XposedBridge.log("ntweaker: размытие под папками/виджетами недоступно — " + t);
            return null;
        }
    }

    /** Рисует размытие (и зерно) в прямоугольнике r с углами corner — в координатах canvas вида anchor. */
    static void draw(Canvas c, Object key, View anchor, RectF r, float corner, LucentPrefs l) {
        draw(c, key, anchor, anchor, r, corner, l);
    }

    /** owner — вид, в координатах которого canvas (у виджета это хост, а anchor — его «карточка»). */
    static void draw(Canvas c, Object key, View anchor, View owner, RectF r, float corner, LucentPrefs l) {
        if (!c.isHardwareAccelerated() || r.isEmpty()) return;
        int px = Math.round(l.blurRadius * anchor.getResources().getDisplayMetrics().density);
        R4[0] = R4[1] = R4[2] = R4[3] = corner;
        boolean drawn = wallpaper(c, owner, r, R4, px);
        if (!drawn && !wallLive) {
            // обои статичные, копия ещё считается (десятки мс) — без размытия, но и без системного: заведённая область
            // композитора потом остаётся и грузит его, даже прозрачная
            clear(key);
            return;
        }
        if (drawn) {
            clear(key);                                       // системное размытие больше не нужно
            if (l.grain > 0f) {
                path.reset();
                path.addRoundRect(r, corner, corner, Path.Direction.CW);
                Paint p = grain();
                p.setAlpha(Math.round(l.grain * 70));
                c.drawPath(path, p);
            }
            return;
        }
        Entry e = entry(key, anchor);
        if (e == null) return;
        try {
            setRadius.invoke(e.d, Math.round(l.blurRadius * anchor.getResources().getDisplayMetrics().density));
        } catch (Throwable ignored) {
        }
        e.corner = corner;
        e.off = false;
        e.d.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
        update(e);
        e.d.draw(c);
        if (l.grain > 0f) {
            path.reset();
            path.addRoundRect(r, corner, corner, Path.Direction.CW);
            Paint p = grain();
            p.setAlpha(Math.round(l.grain * 70));
            c.drawPath(path, p);
        }
    }

    /** Только размытие (дырка в окне) в прямоугольнике r с углами radii (tl, tr, bl, br). */
    static boolean drawShape(Canvas c, Object key, View anchor, RectF r, float[] radii, int blurPx) {
        if (!c.isHardwareAccelerated() || r.isEmpty()) return false;
        Entry e = entry(key, anchor);
        if (e == null) return false;
        try {
            setRadius.invoke(e.d, Math.max(1, blurPx));
        } catch (Throwable ignored) {
        }
        if (e.radii == null || !java.util.Arrays.equals(e.radii, radii)) {
            e.radii = radii.clone();
            e.shownCorner = -1;
        }
        e.off = false;
        e.d.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
        update(e);
        fixPath(e);
        e.d.draw(c);
        return true;
    }

    /**
     * В BackgroundBlurDrawable путь «дырки» собран с перепутанными нижними углами (Path ждёт tl, tr, br, bl, а там
     * tl, tr, bl, br) — при разных нижних углах дырка не совпадает с заливкой. Собираем путь сами, правильно.
     */
    private static void fixPath(Entry e) {
        if (e.radii == null) return;
        try {
            Path p = (Path) XposedHelpers.getObjectField(e.d, "mRectPath");
            if (p.isEmpty()) return;
            float s = e.shownCorner > 0 ? e.shownCorner : 1f;
            float tl = e.radii[0] * s, tr = e.radii[1] * s, bl = e.radii[2] * s, br = e.radii[3] * s;
            android.graphics.Rect b = e.d.getBounds();
            p.reset();
            p.addRoundRect(b.left, b.top, b.right, b.bottom, new float[]{tl, tl, tr, tr, br, br, bl, bl}, Path.Direction.CW);
        } catch (Throwable ignored) {
        }
    }

    /** Зерно в прямоугольнике r с углами radii (tl, tr, bl, br). */
    static void grainShape(Canvas c, RectF r, float[] radii, float strength) {
        if (strength <= 0f) return;
        path.reset();
        path.addRoundRect(r, new float[]{radii[0], radii[0], radii[1], radii[1], radii[3], radii[3], radii[2], radii[2]}, Path.Direction.CW);
        Paint p = grain();
        p.setAlpha(Math.round(Math.min(1f, strength) * 70));
        c.drawPath(path, p);
    }

    /** Убрать размытие у ключа (элемент больше не прозрачный). */
    static void clear(Object key) {
        Object e = XposedHelpers.getAdditionalInstanceField(key, "ntBlur");
        if (e == null) return;
        Entry n = (Entry) e;
        if (n.off) return;
        n.off = true;
        n.d.setAlpha(0);
        n.alpha = 0;
        try {
            setRadius.invoke(n.d, 0);                         // прозрачная область с радиусом всё равно грузит композитор
        } catch (Throwable ignored) {
        }
    }

    private static void watch(View root) {
        if (roots.containsKey(root)) return;
        roots.put(root, true);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                checkOwners();
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Entry e = entries.get(i);
                    View v = e.anchor.get();
                    if (v == null || !v.isAttachedToWindow()) {
                        e.d.setAlpha(0);
                        entries.remove(i);
                    } else if (!e.off) {
                        update(e);
                    }
                }
                return true;
            }
        });
    }

    /** Альфа и углы области размытия — по родителям вида. */
    private static void update(Entry e) {
        View v = e.anchor.get();
        float a = 1f, s = 1f;
        View cur = v;
        while (cur != null) {
            if (cur.getVisibility() != View.VISIBLE) {
                a = 0f;
                break;
            }
            a *= cur.getAlpha();
            s *= cur.getScaleX();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        int alpha = Math.round(255 * Math.max(0f, Math.min(1f, a)));
        if (alpha != e.alpha) {
            e.alpha = alpha;
            e.d.setAlpha(alpha);
        }
        if (e.radii != null) {
            if (Math.abs(s - e.shownCorner) > 0.005f) {
                e.shownCorner = s;
                try {
                    setCorner4.invoke(e.d, e.radii[0] * s, e.radii[1] * s, e.radii[2] * s, e.radii[3] * s);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        float corner = e.corner * s;
        if (Math.abs(corner - e.shownCorner) > 0.5f) {
            e.shownCorner = corner;
            try {
                setCorner.invoke(e.d, corner);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Стеклянный фон для вида: системное размытие того, что за окном, полупрозрачный оттенок tint (рисуется самим
     * BackgroundBlurDrawable) и зерно — по форме radii (tl, tr, bl, br). Вид должен быть в окне. null — не вышло.
     */
    public static Drawable glassBackground(View v, float[] radii, int tint, int blurPx, float grain) {
        Entry e = entry(v, v);
        if (e == null) return null;
        try {
            setRadius.invoke(e.d, Math.max(1, blurPx));
            setColor.invoke(e.d, tint);
        } catch (Throwable t) {
            return null;
        }
        e.radii = radii.clone();
        e.shownCorner = -1;
        e.off = false;
        update(e);
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{e.d, new GrainDrawable(radii, grain)});
    }

    /** Зерно по форме скруглённого прямоугольника (radii: tl, tr, bl, br). */
    private static final class GrainDrawable extends Drawable {
        private final float[] r8 = new float[8];
        private final float strength;
        private final Path p = new Path();

        GrainDrawable(float[] radii, float strength) {
            for (int i = 0; i < 4; i++) r8[i * 2] = r8[i * 2 + 1] = radii[i == 2 ? 3 : i == 3 ? 2 : i];  // Path: tl,tr,br,bl
            this.strength = strength;
        }

        @Override
        public void draw(Canvas c) {
            if (strength <= 0f) return;
            p.reset();
            p.addRoundRect(new RectF(getBounds()), r8, Path.Direction.CW);
            int save = c.save();
            c.clipPath(p);
            grain(c, strength);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Зерно поверх всего canvas (clip — по желанию вызывающего); strength 0…1. */
    public static void grain(Canvas c, float strength) {
        if (strength <= 0f) return;
        Paint p = grain();
        p.setAlpha(Math.round(Math.min(1f, strength) * 70));
        c.drawPaint(p);
    }

    /** Зерно: 256×256 случайных светлых и тёмных точек, плиткой. */
    private static Paint grain() {
        if (grainPaint != null) return grainPaint;
        int n = 256;
        int[] px = new int[n * n];
        Random rnd = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = rnd.nextInt(256);
            px[i] = v < 128 ? ((255 - v * 2) << 24) : (((v - 128) * 2) << 24) | 0xFFFFFF;
        }
        Bitmap b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        grainPaint = new Paint();
        grainPaint.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        return grainPaint;
    }

    // ───────── размытие статичных обоев: без SurfaceFlinger ─────────
    //
    // Системное размытие (BackgroundBlurDrawable) SurfaceFlinger пересчитывает на видеокарте каждый кадр, пока на
    // экране что-то движется: с двумя прозрачными папками композитор терял ~15 % кадров. Под элементами стола лежат
    // только обои — если они статичные, размываем их ОДИН раз (маленькая копия, фоновый поток) и рисуем под элементом
    // нужный кусок готового размытия. Для живых обоев — по-старому, системным размытием.

    // копии по радиусу: у дока и у прозрачных папок он свой — с одной копией они пересчитывали бы её друг за другом
    private static final android.util.SparseArray<Bitmap> WALL_BLURS = new android.util.SparseArray<>();
    private static Bitmap wallBlur;                           // последняя готовая — показываем, пока считается новая
    private static int wallGen, wallW, wallH;
    private static boolean wallLive, wallLoading, wallListening;
    private static final int DOWN = 8;                       // копия в 8 раз меньше экрана
    private static final android.graphics.Matrix WM = new android.graphics.Matrix(), INV = new android.graphics.Matrix();
    private static final Paint WALL_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final java.util.WeakHashMap<View, float[]> wallOwners = new java.util.WeakHashMap<>();
    private static final ArrayList<WeakReference<View>> WAITING = new ArrayList<>();
    private static final boolean DEBUG = false;          // true — кто и почему заводит системное размытие
    private static final float[] R8 = new float[8], R4 = new float[4];
    private static BitmapShader wallShader;
    private static Bitmap wallShaderBitmap;

    /**
     * Нарисовать размытые обои в r (координаты canvas вида owner) с углами radii (tl, tr, bl, br). false — обои живые
     * или ещё не готовы: вызывающий рисует системное размытие.
     */
    static boolean wallpaper(Canvas c, View owner, RectF r, float[] radii, int blurPx) {
        if (r.isEmpty()) return false;
        Shader sh = wallpaperShader(owner, blurPx);
        if (sh == null) return false;
        WALL_PAINT.setShader(sh);
        path.reset();
        R8[0] = R8[1] = radii[0];
        R8[2] = R8[3] = radii[1];
        R8[4] = R8[5] = radii[3];
        R8[6] = R8[7] = radii[2];
        path.addRoundRect(r, R8, Path.Direction.CW);
        c.drawPath(path, WALL_PAINT);
        trackOwner(owner);
        return true;
    }

    /**
     * Шейдер размытых обоев в координатах вида owner (его холста) или null — обои живые или копия ещё считается
     * (тогда owner перерисуется, когда она будет готова). Шейдер общий: следующий вызов меняет его матрицу.
     */
    static Shader wallpaperShader(View owner, int blurPx) {
        if (wallLive) return null;
        Bitmap b = wallBitmap(owner, blurPx);
        if (b == null) {
            WAITING.add(new WeakReference<>(owner));
            return null;
        }
        WM.reset();                                           // transformMatrixToGlobal домножает, а не задаёт
        owner.transformMatrixToGlobal(WM);                    // вид → окно лаунчера (на весь экран)
        if (!WM.invert(INV)) return null;
        INV.preScale((float) wallW / b.getWidth(), (float) wallH / b.getHeight());
        if (wallShader == null || wallShaderBitmap != b) {
            wallShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            wallShaderBitmap = b;
        }
        wallShader.setLocalMatrix(INV);                       // список отрисовки берёт копию — шейдер можно переиспользовать
        return wallShader;
    }

    /** Обои живые (или картинку не получить) — тогда размытие только системное. */
    static boolean wallpaperLive() {
        return wallLive;
    }

    /** Готовая размытая копия обоев (или null, пока считается в фоне). */
    private static Bitmap wallBitmap(View v, int blurPx) {
        Bitmap ready = WALL_BLURS.get(blurPx);
        if (ready != null) return ready;
        if (wallLoading) return wallBlur;
        wallLoading = true;
        int gen = wallGen;
        android.content.Context ctx = v.getContext().getApplicationContext();
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        listenWallpaper(ctx);
        new Thread(() -> {
            Bitmap out = null;
            boolean live = false;
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
                live = wm.getWallpaperInfo() != null;
                if (!live) {
                    Drawable d = wm.getDrawable();             // у лаунчера есть READ_WALLPAPER_INTERNAL
                    if (d instanceof android.graphics.drawable.BitmapDrawable) {
                        out = blurWallpaper(((android.graphics.drawable.BitmapDrawable) d).getBitmap(), sw, sh, Math.max(1, blurPx / DOWN));
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: размытые обои — " + t);
            }
            Bitmap res = out;
            boolean isLive = live;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                wallLoading = false;
                if (gen != wallGen) {                         // обои сменились, пока считали — посчитать заново
                    invalidateOwners();
                    return;
                }
                wallLive = isLive || res == null;
                wallBlur = res;
                if (res != null) {
                    if (WALL_BLURS.size() >= 3) WALL_BLURS.clear();   // радиус двигали ползунком — старые не держим
                    WALL_BLURS.put(blurPx, res);
                }
                wallW = sw;
                wallH = sh;
                XposedBridge.log("ntweaker: размытые обои — " + (res != null ? res.getWidth() + "×" + res.getHeight()
                        + ", радиус " + blurPx + " px" : isLive ? "обои живые — системное размытие" : "картинку не получить — системное размытие"));
                invalidateOwners();
            });
        }, "ntweaker-wallblur").start();
        return wallBlur;
    }

    /** Обои сменились — пересчитать (и проверить, не стали ли живыми). */
    private static void listenWallpaper(android.content.Context ctx) {
        if (wallListening) return;
        wallListening = true;
        try {
            android.app.WallpaperManager.getInstance(ctx).addOnColorsChangedListener((colors, which) -> {
                if ((which & android.app.WallpaperManager.FLAG_SYSTEM) == 0) return;
                wallGen++;
                WALL_BLURS.clear();
                wallLive = false;
                invalidateOwners();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: слежение за обоями — " + t);
        }
    }

    /**
     * Видимая на экране часть обоев, уменьшение в DOWN раз и размытие тремя проходами коробки (≈ гаусс).
     * Как Nothing показывает обои стола (сверено подбором по снимку экрана, ошибка 3 из 255 против 6 у центрального
     * кропа): картинка заполняет 110 % экрана, сдвиг — 5 % ширины слева и половина запаса по высоте сверху (та же
     * логика, что у вырезки глубины в SystemUI, getDepthEffectWallpaperPosition).
     */
    private static Bitmap blurWallpaper(Bitmap src, int sw, int sh, int radius) {
        if (src == null) return null;
        float k = Math.max(sw * 1.1f / src.getWidth(), sh * 1.1f / src.getHeight());
        float x0 = sw * 0.05f, y0 = (src.getHeight() * k - sh) / 2f;
        int cw = Math.round(sw / k), ch = Math.round(sh / k);
        int cx = Math.max(0, Math.min(src.getWidth() - cw, Math.round(x0 / k)));
        int cy = Math.max(0, Math.min(src.getHeight() - ch, Math.round(y0 / k)));
        int w = Math.max(2, sw / DOWN), h = Math.max(2, sh / DOWN);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(src, new android.graphics.Rect(cx, cy, cx + cw, cy + ch), new android.graphics.Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[px.length];
        int r = Math.max(1, radius / 2);                       // три прохода коробки ≈ гаусс радиуса radius
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, r, true);
            box(tmp, px, w, h, r, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    /** Один проход коробки по строкам (horizontal) или столбцам, скользящая сумма. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float div = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sa += p >>> 24; sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (Math.round(sa * div) << 24) | (Math.round(sr * div) << 16) | (Math.round(sg * div) << 8) | Math.round(sb * div);
                int pOut = in[base + Math.max(0, Math.min(n - 1, i - r)) * step];
                int pIn = in[base + Math.max(0, Math.min(n - 1, i + r + 1)) * step];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    /**
     * Кусок обоев запекается в список отрисовки вида. Если вид сдвинулся на экране (листание, анимация стола) —
     * перерисовать его перед кадром, иначе размытие «поедет» вместе с элементом.
     */
    private static final float[] TMP9 = new float[9];

    private static void trackOwner(View owner) {
        WM.reset();
        owner.transformMatrixToGlobal(WM);
        float[] m = wallOwners.get(owner);
        if (m == null) {
            m = new float[9];
            wallOwners.put(owner, m);
            watch(owner.getRootView());
        }
        WM.getValues(m);
    }

    private static void checkOwners() {
        if (wallOwners.isEmpty()) return;
        for (java.util.Map.Entry<View, float[]> e : wallOwners.entrySet()) {
            View v = e.getKey();
            if (v == null || !v.isAttachedToWindow()) continue;
            WM.reset();
            v.transformMatrixToGlobal(WM);
            WM.getValues(TMP9);
            // стол отдалён (открытие приложения, жест «домой», меню): он движется целиком, как один слой — кусок
            // обоев под элементом не пересчитываем каждый кадр. Перерисовка внутри стола заставляла систему заново
            // размывать весь стол (homeBlur) на каждом кадре. Выровняем, когда масштаб вернётся к 1.
            if (Math.abs(TMP9[android.graphics.Matrix.MSCALE_X] - 1f) > 0.002f) continue;
            float[] was = e.getValue();
            for (int i = 0; i < 9; i++) {
                if (Math.abs(TMP9[i] - was[i]) > 0.01f) {
                    v.invalidate();
                    break;
                }
            }
        }
    }

    private static void invalidateOwners() {
        for (View v : wallOwners.keySet()) if (v != null) v.invalidate();
        for (WeakReference<View> w : WAITING) {
            View v = w.get();
            if (v != null) v.invalidate();
        }
        WAITING.clear();
    }
}
