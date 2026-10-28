package dev.novacain.ntclocks.anim;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Вертикальные ползунки яркости и громкости в быстрых настройках (как в ColorOS / iOS). Встаём рядом с родным
 * ползунком яркости (QSPanel.setBrightnessView) и прячем его. Яркость — как в BrightnessController: DisplayManager
 * setTemporaryBrightness во время жеста, setBrightness по отпусканию, гамма-шкала BrightnessUtils (0…65535).
 * Громкость — медиа-поток. Цвета — ресурсы SystemUI brightness_color_* (их же перекрашивает адаптивный движок).
 * Включатель: Settings.Global ntweaker_qs_vsliders = 1.
 */
public final class QsSliders {
    private static final String PKG = "dev.novacain.ntclocks";
    public static final String SPEC_BRIGHT = "custom(dev.novacain.ntclocks/.qs.BrightnessTile)";
    public static final String SPEC_VOLUME = "custom(dev.novacain.ntclocks/.qs.VolumeTile)";
    private static final int GAMMA_MAX = 65535;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<View> brightness = new WeakReference<>(null);
    private static volatile boolean hideNative;
    private static ClassLoader sysui;
    private static boolean observing;

    private QsSliders() {
    }

    private static boolean ours(String spec) {
        return spec != null && spec.startsWith("custom(" + PKG + "/");
    }

    public static void hook(ClassLoader cl) {
        sysui = cl;
        // вид плитки: для наших — вертикальный ползунок вместо обычного содержимого
        Class<?> ex = XposedHelpers.findClassIfExists("com.nothing.systemui.qs.QSPanelControllerBaseEx", cl);
        if (ex != null) {
            Hook.all(ex, "createTileView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    String spec = null;
                    for (Object a : p.args) if (a instanceof String) spec = (String) a;
                    if (!ours(spec) || !(p.getResult() instanceof ViewGroup)) return;
                    try {
                        ViewGroup tile = (ViewGroup) p.getResult();
                        boolean bright = spec.contains("BrightnessTile");
                        tile.removeAllViews();
                        tile.setBackground(null);
                        tile.setPadding(0, 0, 0, 0);
                        Slider sl = new Slider(tile.getContext(), bright);
                        tile.addView(sl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: плитка-ползунок — " + t);
                    }
                }
            });
        }
        // размер при добавлении из списка: 1 колонка × 2 ряда
        Class<?> sheet = XposedHelpers.findClassIfExists("com.nothing.systemui.qs.customize.QSTilesBottomSheetController", cl);
        if (sheet != null) {
            Hook.all(sheet, "generateInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 4 && ours((String) p.args[3])) {
                        p.args[1] = 1;
                        p.args[2] = 2;
                    }
                }
            });
        }
        // родной ползунок яркости прячем, пока наша плитка «Яркость» стоит в сетке
        Class<?> qs = XposedHelpers.findClassIfExists("com.android.systemui.qs.QSPanel", cl);
        if (qs != null) {
            Hook.all(qs, "setBrightnessView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    View bv = (View) p.args[0];
                    brightness = new WeakReference<>(bv);
                    // Nothing сама возвращает ползунок при раскрытии шторки — прячем его перед каждой отрисовкой
                    bv.getViewTreeObserver().addOnPreDrawListener(() -> {
                        if (hideNative && bv.getVisibility() != View.GONE) bv.setVisibility(View.GONE);
                        return true;
                    });
                    MAIN.post(QsSliders::apply);
                }
            });
            Hook.all(qs, "onTuningChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    MAIN.post(QsSliders::apply);
                }
            });
        }
        XposedBridge.log("ntweaker: плитки-ползунки подключены" + (ex == null ? " (без createTileView)" : "") + (sheet == null ? " (без списка плиток)" : ""));
    }

    private static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static void apply() {
        View b = brightness.get();
        if (b == null) return;
        Context c = b.getContext();
        if (!observing) {
            observing = true;
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("sysui_qs_tiles"), false, new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    apply();
                }
            });
        }
        String tiles = Settings.Secure.getString(c.getContentResolver(), "sysui_qs_tiles");
        hideNative = tiles != null && tiles.contains(SPEC_BRIGHT);
        b.setVisibility(hideNative ? View.GONE : View.VISIBLE);
    }

    /** Высокая «таблетка»: заливка снизу вверх, иконка внизу. */
    @SuppressLint("ViewConstructor")
    static final class Slider extends View {
        private final boolean bright;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private float value;          // 0…1 (для яркости — гамма-шкала, как у системного ползунка)
        private float downY, downValue;
        private int lastStep = -1;
        private boolean handedOff;
        private final Runnable longPress = () -> {
            handedOff = true;
            setPressed(false);
            animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            getParent().requestDisallowInterceptTouchEvent(false);
            ((View) getParent()).performLongClick();
        };
        private BroadcastReceiver volReceiver;
        private ContentObserver brObserver;

        Slider(Context c, boolean bright) {
            super(c);
            this.bright = bright;
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            setContentDescription(bright ? "Яркость" : "Громкость");
        }

        private int color(String name, int fallback) {
            int id = getResources().getIdentifier(name, "color", getContext().getPackageName());
            return id != 0 ? getContext().getColor(id) : fallback;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            read();
            if (bright) {
                brObserver = new ContentObserver(MAIN) {
                    @Override
                    public void onChange(boolean selfChange) {
                        if (!isPressed()) read();
                    }
                };
                getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_brightness_float"), false, brObserver);
                getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_brightness"), false, brObserver);
            } else {
                volReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!isPressed()) read();
                    }
                };
                getContext().registerReceiver(volReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            try {
                if (brObserver != null) getContext().getContentResolver().unregisterContentObserver(brObserver);
                if (volReceiver != null) getContext().unregisterReceiver(volReceiver);
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility);
            if (visibility == VISIBLE && !isPressed()) read();
        }

        // ───────── данные ─────────

        private float[] brightnessRange() {
            try {
                Object info = XposedHelpers.callMethod(getContext().getDisplay(), "getBrightnessInfo");
                return new float[]{XposedHelpers.getFloatField(info, "brightness"),
                        XposedHelpers.getFloatField(info, "brightnessMinimum"), XposedHelpers.getFloatField(info, "brightnessMaximum")};
            } catch (Throwable t) {
                return null;
            }
        }

        private Class<?> utils() {
            return XposedHelpers.findClassIfExists("com.android.settingslib.display.BrightnessUtils", sysui);
        }

        private void read() {
            try {
                if (bright) {
                    float[] r = brightnessRange();
                    Class<?> u = utils();
                    if (r != null && u != null) {
                        int g = (Integer) XposedHelpers.callStaticMethod(u, "convertLinearToGammaFloat", r[0], r[1], r[2]);
                        value = g / (float) GAMMA_MAX;
                    }
                } else {
                    AudioManager am = getContext().getSystemService(AudioManager.class);
                    value = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                }
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: ползунок, чтение — " + t);
            }
            invalidate();
        }

        private void write(boolean done) {
            try {
                if (bright) {
                    float[] r = brightnessRange();
                    Class<?> u = utils();
                    if (r == null || u == null) return;
                    float lin = (Float) XposedHelpers.callStaticMethod(u, "convertGammaToLinearFloat", Math.round(value * GAMMA_MAX), r[1], r[2]);
                    DisplayManager dm = getContext().getSystemService(DisplayManager.class);
                    int id = getContext().getDisplay().getDisplayId();
                    XposedHelpers.callMethod(dm, done ? "setBrightness" : "setTemporaryBrightness", id, lin);
                    int step = Math.round(value * 20);
                    if (step != lastStep) { lastStep = step; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                } else {
                    AudioManager am = getContext().getSystemService(AudioManager.class);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int v = Math.round(value * max);
                    if (v != lastStep) {
                        lastStep = v;
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: ползунок, запись — " + t);
            }
        }

        // ───────── жест: тянем вверх-вниз, относительно места касания (как в iOS) ─────────

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);   // не листать шторку
                    handedOff = false;
                    postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                    setPressed(true);
                    downY = e.getY();
                    downValue = value;
                    lastStep = -1;
                    animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (handedOff) return false;
                    if (Math.abs(e.getY() - downY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) removeCallbacks(longPress);
                    value = Math.max(0f, Math.min(1f, downValue + (downY - e.getY()) / Math.max(1, getHeight())));
                    write(false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (handedOff) return false;
                    setPressed(false);
                    write(true);
                    animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        // ───────── отрисовка ─────────

        /** Плитка Nothing сама возвращает свой фон и отступы — держим их выключенными, ползунок занимает её целиком. */
        private void claimTile() {
            if (!(getParent() instanceof View)) return;
            View t = (View) getParent();
            if (t.getBackground() != null) t.setBackground(null);
            if (t.getPaddingLeft() != 0 || t.getPaddingTop() != 0 || t.getPaddingRight() != 0 || t.getPaddingBottom() != 0) t.setPadding(0, 0, 0, 0);
        }

        @Override
        protected void onDraw(Canvas cv) {
            claimTile();
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;      // полная «таблетка», как у плиток Nothing
            track.setColor(color("brightness_color_background", 0xFF2A2A2E));
            fill.setColor(color("brightness_color_foreground", 0xFFE6E6EA));
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            cv.drawRect(rect, track);
            float top = h * (1f - value);
            cv.drawRect(0, top, w, h, fill);
            cv.restore();
            // иконка внизу: на заливке — цвет трека, иначе — цвет заливки
            float cx = w / 2f, cy = h - Math.min(w, h) * 0.30f, s = dp(getContext(), 11);
            icon.setColor(cy > top ? track.getColor() : fill.getColor());
            icon.setStrokeWidth(dp(getContext(), 2.2f));
            if (bright) drawSun(cv, cx, cy, s); else drawSpeaker(cv, cx, cy, s);
        }

        private void drawSun(Canvas cv, float cx, float cy, float s) {
            cv.drawCircle(cx, cy, s * 0.42f, icon);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float c = (float) Math.cos(a), sn = (float) Math.sin(a);
                cv.drawLine(cx + c * s * 0.72f, cy + sn * s * 0.72f, cx + c * s, cy + sn * s, icon);
            }
        }

        private void drawSpeaker(Canvas cv, float cx, float cy, float s) {
            Path p = new Path();
            float x0 = cx - s * 0.85f;
            p.moveTo(x0, cy - s * 0.3f);
            p.lineTo(x0 + s * 0.4f, cy - s * 0.3f);
            p.lineTo(x0 + s * 0.85f, cy - s * 0.7f);
            p.lineTo(x0 + s * 0.85f, cy + s * 0.7f);
            p.lineTo(x0 + s * 0.4f, cy + s * 0.3f);
            p.lineTo(x0, cy + s * 0.3f);
            p.close();
            cv.drawPath(p, icon);
            RectF a = new RectF();
            for (float k : new float[]{0.45f, 0.85f}) {
                a.set(cx + s * 0.1f - s * k, cy - s * k, cx + s * 0.1f + s * k, cy + s * k);
                if (value > (k < 0.5f ? 0.01f : 0.5f)) cv.drawArc(a, -45, 90, false, icon);
            }
        }
    }
}
