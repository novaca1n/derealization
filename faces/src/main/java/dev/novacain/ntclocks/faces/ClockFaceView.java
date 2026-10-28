package dev.novacain.ntclocks.faces;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Общая основа трёх циферблатов. Повторяет правила часов Nothing (NTClockView), чтобы встать на их место:
 * ширина — от родителя, высота — от «родной» высоты часов Nothing (166 dp × поправка на масштаб экрана × 0,875),
 * «снимок» для превью уменьшен до 0,41. «Маленькие» часы у Nothing — это и есть основные часы полного размера
 * (lockscreen_clock_view), поэтому FACE_SMALL рисуется как FACE_LARGE.
 *
 * Рисование никогда не роняет SystemUI: любое исключение в отрисовке ловится и пишется в лог.
 */
public abstract class ClockFaceView extends View implements ClockFace {
    static final String TAG = "NtClocks";

    /** Высота часов Nothing (R.dimen.clock_height) и их поправочный коэффициент FIX_SCALE_RATIO. */
    private static final float NOTHING_CLOCK_HEIGHT_DP = 166f;
    private static final float NOTHING_FIX_SCALE = 0.875f;
    private static final float SNAPSHOT_RATIO = 0.41f;
    private static final float NOTHING_PADDING_DP = 30f;

    protected final int face;
    protected ClockPrefs prefs = new ClockPrefs();
    protected final Calendar calendar = Calendar.getInstance();
    private Boolean systemIs24;
    private boolean regionDark = true;
    private Integer seedColor;
    private boolean systemCentered = true;
    private boolean normalSize = true;
    protected float doze;                       // 0 — экран включён, 1 — AOD
    private boolean ticking;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshTime();
            scheduleTick();
        }
    };

    @Override
    public View asView() {
        return this;
    }

    protected ClockFaceView(Context context, int face) {
        super(context);
        this.face = face;
        setWillNotDraw(false);
    }

    // ───────── то, что дёргает SystemUI (или превью в приложении) ─────────

    public void setPrefs(ClockPrefs p) {
        prefs = p == null ? new ClockPrefs() : p;
        onPrefsChanged();
        requestLayout();
        invalidate();
        scheduleTick();
    }

    public ClockPrefs getPrefs() {
        return prefs;
    }

    public void refreshTime() {
        calendar.setTimeInMillis(System.currentTimeMillis());
        invalidate();
    }

    public void setTimeZone(TimeZone tz) {
        calendar.setTimeZone(tz);
        refreshTime();
    }

    public void setSystem24(boolean is24) {
        systemIs24 = is24;
        requestLayout();
        invalidate();
    }

    public void setRegionDark(boolean dark) {
        if (regionDark != dark) {
            regionDark = dark;
            invalidate();
        }
    }

    public void setSeedColor(Integer color) {
        seedColor = color;
        invalidate();
    }

    public void setDoze(float amount) {
        float d = Math.max(0f, Math.min(1f, amount));
        if (d != doze) {
            doze = d;
            invalidate();
            scheduleTick();
        }
    }

    /** onClockFaceChanged / updateClockface: выравнивание по центру и «нормальный» размер (у Nothing иначе 0,7). */
    public void setSystemLayout(boolean centered, boolean normal) {
        systemCentered = centered;
        normalSize = normal;
        requestLayout();
        invalidate();
    }

    // ───────── общие вычисления ─────────

    /** Пикселей на «dp часов Nothing»: не зависит от «размера отображения», как у них. */
    protected float unit() {
        return DisplayMetrics.DENSITY_DEVICE_STABLE / 160f * NOTHING_FIX_SCALE;
    }

    /** Итоговый масштаб: выбор пользователя × маленькие/снимок/«ненормальный» размер. */
    protected float scale() {
        float s = prefs.size * (normalSize ? 1f : 0.7f);
        if (face == FACE_SNAPSHOT) s *= SNAPSHOT_RATIO;
        return s;
    }

    protected float baseHeight() {
        return NOTHING_CLOCK_HEIGHT_DP * unit() * scale();
    }

    protected float paddingH() {
        return NOTHING_PADDING_DP * unit() * (face == FACE_SNAPSHOT ? SNAPSHOT_RATIO : 1f);
    }

    protected float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    protected boolean is24() {
        if (prefs.hourFormat == ClockPrefs.FORMAT_24) return true;
        if (prefs.hourFormat == ClockPrefs.FORMAT_12) return false;
        return systemIs24 != null ? systemIs24 : DateFormat.is24HourFormat(getContext());
    }

    protected int hour() {
        int h = calendar.get(Calendar.HOUR_OF_DAY);
        if (!is24()) {
            h %= 12;
            if (h == 0) h = 12;
        }
        return h;
    }

    protected String two(int v, boolean zero) {
        return v < 10 ? (zero ? "0" + v : String.valueOf(v)) : String.valueOf(v);
    }

    /** Секунды показываются только на включённом экране: в AOD экран обновляется раз в минуту. */
    protected boolean showSeconds() {
        return prefs.seconds && doze < 0.5f;
    }

    /** Итоговое выравнивание: «как в системе» берёт выбор Nothing (по центру / слева). */
    protected int alignment() {
        if (prefs.align != ClockPrefs.ALIGN_SYSTEM) return prefs.align;
        return systemCentered ? ClockPrefs.ALIGN_CENTER : ClockPrefs.ALIGN_LEFT;
    }

    /** Основной цвет с учётом AOD: к AOD цвет приглушается до выбранной яркости. */
    protected int mainColor() {
        int c;
        switch (prefs.colorMode) {
            case ClockPrefs.COLOR_WALLPAPER:
                c = seedColor != null ? lighten(seedColor) : prefs.customColor;
                break;
            case ClockPrefs.COLOR_CUSTOM:
                c = prefs.customColor;
                break;
            default:
                c = regionDark ? Color.WHITE : 0xFF101010;
        }
        if (doze > 0f) {
            // в AOD фон чёрный: тёмный текст стал бы невидим — уходим к белому и приглушаем
            int aod = blend(c, Color.WHITE, prefs.colorMode == ClockPrefs.COLOR_THEME ? 1f : 0.25f);
            float k = 1f - doze * (1f - prefs.aodBrightness);
            aod = Color.argb(255, (int) (Color.red(aod) * k), (int) (Color.green(aod) * k), (int) (Color.blue(aod) * k));
            c = blend(c, aod, doze);
        }
        return c;
    }

    protected int accentColor() {
        if (prefs.colorMode == ClockPrefs.COLOR_WALLPAPER && seedColor != null) return lighten(seedColor);
        return prefs.customColor;
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** seed color из обоев бывает тёмным — для часов на тёмном фоне поднимаем яркость. */
    static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1], 0.45f));
        hsv[2] = Math.max(hsv[2], 0.92f);
        return Color.HSVToColor(hsv);
    }

    protected static int withAlpha(int c, float a) {
        return (c & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, a)) * Color.alpha(c)) << 24);
    }

    /**
     * Обводка текста без внутренних стыков: в шрифтах части глифа часто нарисованы перекрывающимися контурами
     * («4», «8»), и простая обводка показывает их пересечения. Контуры сливаются в один (объединение путей).
     */
    protected static void drawTextOutline(Canvas c, String s, float x, float y, Paint p) {
        // слияние контуров (Path.op) дорогое, а в переходе в AOD часы рисуются каждый кадр одной и той же строкой —
        // кэш последнего результата по строке, позиции, кеглю, шрифту и разрядке
        String key = s + '|' + x + '|' + y + '|' + p.getTextSize() + '|' + System.identityHashCode(p.getTypeface()) + '|'
                + p.getLetterSpacing() + '|' + p.getTextScaleX() + '|' + p.getFontVariationSettings();
        Path merged = OUTLINES.get(key);
        if (merged == null) {
            Path raw = new Path();
            p.getTextPath(s, 0, s.length(), x, y, raw);
            merged = new Path();
            if (!merged.op(raw, new Path(), Path.Op.UNION)) merged = raw;
            if (OUTLINES.size() > 16) OUTLINES.clear();
            OUTLINES.put(key, merged);
        }
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        c.drawPath(merged, p);
        p.setStyle(st);
    }

    // ───────── размеры и рисование ─────────

    /** Высота содержимого при данной ширине — у каждого циферблата своя. */
    protected abstract float contentHeight(int width);

    private static final java.util.HashMap<String, Path> OUTLINES = new java.util.HashMap<>();

    protected abstract void drawClock(Canvas c, int width, int height);

    protected void onPrefsChanged() {
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = getDefaultSize(getSuggestedMinimumWidth(), widthSpec);
        int h = Math.round(contentHeight(w) + Math.abs(dp(prefs.offsetY)));
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            calendar.setTimeInMillis(System.currentTimeMillis());
            int save = canvas.save();
            canvas.translate(dp(prefs.offsetX), Math.max(0f, dp(prefs.offsetY)));
            drawClock(canvas, getWidth(), getHeight());
            canvas.restoreToCount(save);
        } catch (Throwable t) {
            Log.e(TAG, "ошибка отрисовки " + getClass().getSimpleName(), t);
        }
    }

    // ───────── секундный тик (только когда видно и нужны секунды) ─────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        ticking = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        scheduleTick();
    }

    private void scheduleTick() {
        handler.removeCallbacks(tick);
        ticking = showSeconds() && isAttachedToWindow() && isShown();
        if (ticking) {
            long now = System.currentTimeMillis();
            handler.postAtTime(tick, SystemClock.uptimeMillis() + (1000 - now % 1000) + 5);
        }
    }
}
