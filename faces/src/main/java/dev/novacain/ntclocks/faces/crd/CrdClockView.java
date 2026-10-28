package dev.novacain.ntclocks.faces.crd;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import dev.novacain.ntclocks.faces.ClockFace;
import dev.novacain.ntclocks.faces.ClockPrefs;
import dev.novacain.ntclocks.faces.Fonts;
import dev.novacain.ntclocks.faces.R;

/**
 * Стиль часов crDroid: их разметка (перенесена tools/port_crdroid.py) внутри рамки модуля.
 * Поведение повторяет ClockStyle crDroid: белые тексты красятся в выбранный цвет, цветные — как задумано стилем,
 * в AOD всё белое и приглушено; размер — масштабом от верха. Сверху — настройки модуля: шрифт вместо системного
 * шрифта часов (тег clockfont), сдвиг, яркость в AOD.
 */
public final class CrdClockView extends FrameLayout implements ClockFace {
    private static final String PKG = "dev.novacain.ntclocks";
    private static final String TAG = "NtClocks";
    /** Стили, которые crDroid не перекрашивает (у них свои цвета). */
    private static final int[] NO_COLOR = {1, 2, 25, 26, 45};
    private static final float SNAPSHOT_RATIO = 0.41f;

    private final int number;
    private final int face;
    private final View content;
    private final List<TextView> texts = new ArrayList<>();
    private final List<TextClock> clocks = new ArrayList<>();
    private final Map<TextView, Integer> originalColor = new HashMap<>();
    private final Map<TextView, Typeface> originalFont = new HashMap<>();
    private final List<TextView> clockFontViews = new ArrayList<>();
    private ClockPrefs prefs = new ClockPrefs();
    private float doze;
    private boolean regionDark = true;
    private Integer seed;
    private boolean normal = true;

    public CrdClockView(Context context, int number, int face) {
        super(context);
        this.number = number;
        this.face = face;
        setClipChildren(false);
        setClipToPadding(false);
        View v;
        try {
            int idx = indexOf(number);
            v = LayoutInflater.from(moduleContext(context)).inflate(CrdStyles.LAYOUTS[idx], this, false);
        } catch (Throwable t) {
            Log.e(TAG, "стиль crDroid " + number + " не раскрылся", t);
            v = new View(context);
        }
        content = v;
        addView(content);
        collect(content);
        fillProfile(content);
        apply();
    }

    static int indexOf(int number) {
        for (int i = 0; i < CrdStyles.NUMBERS.length; i++) if (CrdStyles.NUMBERS[i] == number) return i;
        return 0;
    }

    /**
     * Контекст для разметки: ресурсы и классы модуля. В SystemUI это контекст пакета модуля, а классы
     * (WordClockView и др.) — из того же загрузчика, что и этот класс (родитель — SystemUI).
     */
    private static Context moduleContext(Context ctx) throws Exception {
        Context pc = Fonts.moduleContext(ctx);
        if (pc == ctx) return ctx;
        final ClassLoader own = CrdClockView.class.getClassLoader();
        return new ContextThemeWrapper(pc, android.R.style.Theme_DeviceDefault) {
            @Override
            public ClassLoader getClassLoader() {
                return own;
            }
        };
    }

    private void collect(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            texts.add(tv);
            originalColor.put(tv, tv.getCurrentTextColor());
            originalFont.put(tv, tv.getTypeface());
            if (tagHas(tv, "clockfont")) clockFontViews.add(tv);
            if (tv instanceof TextClock) clocks.add((TextClock) tv);
        }
        if (v instanceof LottieAnimationView) startLottie((LottieAnimationView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i));
        }
    }

    private static boolean tagHas(View v, String part) {
        Object t = v.getTag();
        if (!(t instanceof String)) return false;
        for (String s : ((String) t).split("\\|")) if (s.equals(part)) return true;
        return false;
    }

    /** Атрибуты Lottie внутри SystemUI не читаются (там своя копия библиотеки) — анимацию ставим кодом. */
    private void startLottie(LottieAnimationView v) {
        Object t = v.getTag();
        if (!(t instanceof String)) return;
        for (String s : ((String) t).split("\\|")) {
            if (!s.startsWith("lottie:")) continue;
            try {
                Resources r = Fonts.moduleContext(getContext()).getResources();
                int id = r.getIdentifier(s.substring(7), "raw", PKG);
                if (id != 0) {
                    v.setAnimation(id);
                    v.setRepeatCount(LottieDrawable.INFINITE);
                    v.playAnimation();
                }
            } catch (Throwable e) {
                Log.w(TAG, "анимация " + s + ": " + e);
            }
        }
    }

    /** Имя пользователя, устройство, аватар — то, что crDroid брал скрытыми API, здесь — открытыми. */
    private void fillProfile(View root) {
        TextView name = root.findViewById(R.id.user_name);
        if (name != null) {
            String n = null;
            try {
                n = ((UserManager) getContext().getSystemService(Context.USER_SERVICE)).getUserName();
            } catch (Throwable ignored) {
            }
            name.setText(TextUtils.isEmpty(n) ? "Я" : n);
        }
        TextView device = root.findViewById(R.id.device_name);
        if (device != null) {
            String d = Settings.Global.getString(getContext().getContentResolver(), Settings.Global.DEVICE_NAME);
            device.setText(TextUtils.isEmpty(d) ? android.os.Build.MODEL : d);
        }
        ImageView icon = root.findViewById(R.id.user_profile_icon);
        if (icon != null && icon.getDrawable() == null) icon.setVisibility(View.GONE);
    }

    // ───────── ClockFace ─────────

    @Override public View asView() { return this; }
    @Override public ClockPrefs getPrefs() { return prefs; }

    @Override
    public void setPrefs(ClockPrefs p) {
        prefs = p == null ? new ClockPrefs() : p;
        apply();
        requestLayout();
    }

    @Override
    public void refreshTime() {
        for (TextClock c : clocks) {
            try {
                c.refreshTime();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void setTimeZone(TimeZone tz) { for (TextClock c : clocks) c.setTimeZone(tz.getID()); }
    @Override public void setSystem24(boolean is24) { refreshTime(); }
    @Override public void setRegionDark(boolean dark) { regionDark = dark; apply(); }
    @Override public void setSeedColor(Integer color) { seed = color; apply(); }

    @Override
    public void setDoze(float amount) {
        doze = Math.max(0f, Math.min(1f, amount));
        apply();
    }

    @Override
    public void setSystemLayout(boolean centered, boolean normalSize) {
        normal = normalSize;
        requestLayout();
    }

    // ───────── внешний вид ─────────

    private boolean recolor() {
        for (int n : NO_COLOR) if (n == number) return false;
        return true;
    }

    private int resolveColor() {
        switch (prefs.colorMode) {
            case ClockPrefs.COLOR_WALLPAPER:
                if (seed != null) return lighten(seed);
                try {
                    return getContext().getColor(Resources.getSystem().getIdentifier("system_accent1_100", "color", "android"));
                } catch (Throwable e) {
                    return prefs.customColor;
                }
            case ClockPrefs.COLOR_CUSTOM:
                return prefs.customColor;
            default:
                return regionDark ? Color.WHITE : 0xFF101010;
        }
    }

    private static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1], 0.45f));
        hsv[2] = Math.max(hsv[2], 0.92f);
        return Color.HSVToColor(hsv);
    }

    private void apply() {
        Typeface chosen = prefs.font.isEmpty() ? Fonts.systemClock() : Fonts.get(getContext(), prefs.font);
        for (TextView tv : clockFontViews) tv.setTypeface(chosen);
        boolean aod = doze > 0.5f;
        int color = resolveColor();
        for (TextView tv : texts) {
            Integer orig = originalColor.get(tv);
            if (orig == null) continue;
            boolean white = (orig & 0x00FFFFFF) == 0x00FFFFFF;
            if (aod) tv.setTextColor(Color.WHITE);                       // в AOD всё белое, как у crDroid
            else if (!white || !recolor()) tv.setTextColor(orig);
            else tv.setTextColor(color);
        }
        content.setAlpha(1f - doze * (1f - prefs.aodBrightness));
        float d = getResources().getDisplayMetrics().density;
        content.setTranslationX(prefs.offsetX * d);
        content.setTranslationY(prefs.offsetY * d);
        invalidate();
    }

    private float scale() {
        float s = prefs.size * (normal ? 1f : 0.7f);
        if (face == FACE_SNAPSHOT) s *= SNAPSHOT_RATIO;
        return s;
    }

    /** Высота = высота разметки × масштаб: NcLayout даёт часам расти, и виджеты Nothing сдвигаются следом. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        float s = scale();
        int h = Math.round(content.getMeasuredHeight() * s + Math.max(0f, prefs.offsetY) * getResources().getDisplayMetrics().density);
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        content.layout(0, 0, r - l, content.getMeasuredHeight());
        float s = scale();
        content.setPivotX((r - l) / 2f);
        content.setPivotY(0f);
        content.setScaleX(s);
        content.setScaleY(s);
    }
}
