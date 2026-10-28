package dev.novacain.ntclocks.anim;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * «Гравитация» на рабочем столе (AnimPrefs.gravity), как iOS-твик Gravitation: встряхнул телефон на столе — значки,
 * папки и виджеты текущего экрана и дока становятся телами (Physics, порт Box2D-lite): падают, кувыркаются,
 * сталкиваются и складываются в кучу; сила тяжести — с датчика, наклонил телефон — куча пересыпается. Значки можно
 * хватать и бросать, касание — значок возвращается вместе со всеми и открывается. Ещё встряхивание или «домой» —
 * всё разлетается по местам пружиной; уход со стола — мгновенно на место.
 * Движение — setAnimationMatrix (раскладку и анимации лаунчера не трогает), рамки предков не обрезают на время.
 * Когда всё улеглось и телефон не шевелят — кадры не считаются.
 */
public final class Gravity {
    private Gravity() {
    }

    private static final float PPM = 480f;          // пикселей на метр: значок ~0.3 м, экран ~2.2 × 5 м
    private static final float G_SCALE = 1.7f;      // чуть сильнее земной — падение через весь экран ~0.8 с
    private static final float DT = 1f / 120f;
    private static final String ACTION = "dev.novacain.ntclocks.GRAVITY";
    private static boolean debugG;

    private static WeakReference<Activity> launcher = new WeakReference<>(null);
    private static Object normalState;
    private static SensorManager sm;
    private static Sensor accel, grav;

    private static Physics world;
    private static final List<View> views = new ArrayList<>();
    private static float[] ox, oy, lx, ly;          // исходный центр тела на экране и в координатах вида (px)
    private static final List<ViewGroup> unclipped = new ArrayList<>();
    private static final List<boolean[]> clipWas = new ArrayList<>();
    private static boolean active, returning, sleeping;
    private static long lastFrame;
    private static float accum, sgx, sgy = 9.8f;
    private static int calm;
    private static ValueAnimator back;
    private static final Matrix M = new Matrix();
    private static final int[] LOC = new int[2];

    // ───────── подключение ─────────

    public static void launcher(ClassLoader cl) {
        Class<?> l = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        Class<?> drag = XposedHelpers.findClassIfExists("com.android.launcher3.dragndrop.DragLayer", cl);
        Class<?> st = XposedHelpers.findClassIfExists("com.android.launcher3.LauncherState", cl);
        if (l == null || drag == null) {
            XposedBridge.log("ntweaker: гравитация — лаунчер не найден");
            return;
        }
        if (st != null) normalState = XposedHelpers.getStaticObjectField(st, "NORMAL");
        Hook.all(l, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                launcher = new WeakReference<>(a);
                // для проверки без встряхивания (root): am broadcast -a dev.novacain.ntclocks.GRAVITY
                // [--ef gx 4 --ef gy 8 — подменить силу тяжести вместо датчика]
                try {
                    a.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, android.content.Intent i) {
                            if (i.hasExtra("gx")) {
                                debugG = true;
                                sgx = i.getFloatExtra("gx", 0f);
                                sgy = i.getFloatExtra("gy", 9.8f);
                                wake();
                            } else {
                                onShake();
                            }
                        }
                    }, new android.content.IntentFilter(ACTION), "android.permission.STATUS_BAR", null, Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: гравитация, приёмник — " + t);
                }
            }
        });
        Hook.all(l, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                launcher = new WeakReference<>((Activity) p.thisObject);
                listenShake(on());
            }
        });
        Hook.all(l, "onPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                listenShake(false);
                if (active) finish(false);
            }
        });
        Hook.all(l, "onNewIntent", new XC_MethodHook() {      // «домой», когда стол уже открыт
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (active && !returning) finish(true);
            }
        });
        Hook.all(drag, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!active || !(p.args[0] instanceof MotionEvent)) return;
                try {
                    touch((MotionEvent) p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: гравитация, касание — " + t);
                }
                p.setResult(true);
            }
        });
        XposedBridge.log("ntweaker: гравитация подключена");
    }

    private static boolean on() {
        return AnimHooks.prefs().gravity;
    }

    // ───────── датчики: встряхивание и сила тяжести ─────────

    private static boolean shakeOn, gravOn;
    private static final long[] peaks = new long[4];
    private static int peakN;
    private static long lastShake;

    private static final SensorEventListener SHAKE = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float a = (float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
            long now = SystemClock.uptimeMillis();
            if (a < 11f || now - peaks[(peakN + 3) % 4] < 110) return;       // рывок сильнее ~2g, не чаще 9 раз/с
            peaks[peakN % 4] = now;
            peakN++;
            // три рывка за 0.9 с — встряхивание
            if (peakN >= 3 && now - peaks[(peakN - 3) % 4] < 900 && now - lastShake > 1200) {
                lastShake = now;
                peakN = 0;
                onShake();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }
    };

    private static final SensorEventListener GRAV = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            // датчик: x — вправо, y — вверх по экрану; на экране y вниз
            if (debugG) return;
            float nx = -e.values[0], ny = e.values[1];
            if (sleeping && (Math.abs(nx - sgx) > 0.35f || Math.abs(ny - sgy) > 0.35f)) wake();
            sgx = nx;
            sgy = ny;
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }
    };

    private static boolean sensors(Context c) {
        if (sm == null) {
            sm = c.getSystemService(SensorManager.class);
            if (sm == null) return false;
            accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        return accel != null;
    }

    private static void listenShake(boolean want) {
        Activity a = launcher.get();
        if (a == null || !sensors(a)) return;
        if (want && !shakeOn) {
            peakN = 0;
            shakeOn = sm.registerListener(SHAKE, accel, SensorManager.SENSOR_DELAY_GAME);
        } else if (!want && shakeOn) {
            sm.unregisterListener(SHAKE);
            shakeOn = false;
        }
    }

    private static void listenGravity(boolean want) {
        if (sm == null || grav == null) return;
        if (want && !gravOn) gravOn = sm.registerListener(GRAV, grav, SensorManager.SENSOR_DELAY_GAME);
        else if (!want && gravOn) {
            sm.unregisterListener(GRAV);
            gravOn = false;
        }
    }

    private static void onShake() {
        if (!on()) return;
        if (active) {
            if (!returning) finish(true);
            return;
        }
        Activity a = launcher.get();
        if (a == null || !onWorkspace(a)) return;
        start(a);
    }

    /** Открыт сам стол: не меню, не недавние, не папка и не всплывающее меню. */
    private static boolean onWorkspace(Activity a) {
        try {
            Object sm = XposedHelpers.callMethod(a, "getStateManager");
            if (normalState != null && XposedHelpers.callMethod(sm, "getState") != normalState) return false;
            Class<?> fv = XposedHelpers.findClassIfExists("com.android.launcher3.AbstractFloatingView", a.getClassLoader());
            if (fv != null && XposedHelpers.callStaticMethod(fv, "getTopOpenView", a) != null) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ───────── запуск: тела из значков ─────────

    private static void start(Activity a) {
        views.clear();
        try {
            ViewGroup ws = (ViewGroup) XposedHelpers.callMethod(a, "getWorkspace");
            int page = (Integer) XposedHelpers.callMethod(ws, "getCurrentPage");
            View cell = ws.getChildAt(page);
            if (cell != null) add((ViewGroup) XposedHelpers.callMethod(cell, "getShortcutsAndWidgets"));
            Object hs = XposedHelpers.callMethod(a, "getHotseat");
            if (hs != null) add((ViewGroup) XposedHelpers.callMethod(hs, "getShortcutsAndWidgets"));
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: гравитация, значки — " + t);
            return;
        }
        int n = views.size();
        if (n == 0) return;
        View root = a.getWindow().getDecorView();
        float sw = root.getWidth(), sh = root.getHeight();
        float bottomInset = 0f;
        android.view.WindowInsets wi = root.getRootWindowInsets();
        if (wi != null) bottomInset = wi.getInsets(android.view.WindowInsets.Type.systemGestures() | android.view.WindowInsets.Type.navigationBars()).bottom;

        world = new Physics(n + 4);
        ox = new float[n];
        oy = new float[n];
        lx = new float[n];
        ly = new float[n];
        Random rnd = new Random();
        Rect r = new Rect();
        for (int i = 0; i < n; i++) {
            View v = views.get(i);
            bounds(v, r);
            v.getLocationOnScreen(LOC);
            lx[i] = r.exactCenterX();
            ly[i] = r.exactCenterY();
            ox[i] = LOC[0] + lx[i];
            oy[i] = LOC[1] + ly[i];
            float w = r.width() / PPM, h = r.height() / PPM;
            Physics.Body b = world.bodies[i];
            b.set(w, h, w * h * 10f);
            b.x = ox[i] / PPM;
            b.y = oy[i] / PPM;
            // толчок «отрыва»: подброс вверх, разлёт от центра экрана и закрутка — чтобы кувыркались, а не падали
            // стройными рядами; лёгкие (значки) крутятся сильнее тяжёлых (виджеты)
            float side = (ox[i] - sw / 2f) / sw;
            float spin = 1f / (float) Math.sqrt(Math.max(0.3f, w * h * 10f));
            b.vx = side * 1.6f + (rnd.nextFloat() - 0.5f) * 1.4f;
            b.vy = -0.8f - rnd.nextFloat() * 1.6f;
            b.w = (rnd.nextBoolean() ? 1 : -1) * (2f + rnd.nextFloat() * 4f) * spin;
        }
        // стены — за краями экрана, толщиной в метр
        float W = sw / PPM, H = (sh - bottomInset) / PPM, T = 1f;
        wall(world.bodies[n], W / 2f, H + T / 2f, W + 2 * T, T);
        wall(world.bodies[n + 1], W / 2f, -T / 2f - 0.6f, W + 2 * T, T);
        wall(world.bodies[n + 2], -T / 2f, H / 2f, T, H + 2 * T);
        wall(world.bodies[n + 3], W + T / 2f, H / 2f, T, H + 2 * T);

        unclip();
        sgx = 0f;
        sgy = 9.8f;
        listenGravity(true);
        active = true;
        returning = false;
        sleeping = false;
        calm = 0;
        accum = 0f;
        lastFrame = 0L;
        Choreographer.getInstance().postFrameCallback(FRAME);
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    private static void wall(Physics.Body b, float x, float y, float w, float h) {
        b.set(w, h, 0f);
        b.x = x;
        b.y = y;
        b.friction = 0.6f;
    }

    private static void add(ViewGroup g) {
        if (g == null) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.isAttachedToWindow()) views.add(v);
        }
    }

    /** Видимая часть элемента: значок — без подписи и полей ячейки, папка — превью, виджет — без отступов. */
    private static void bounds(View v, Rect r) {
        try {
            if (v.getClass().getName().contains("FolderIcon")) XposedHelpers.callMethod(v, "getPreviewBounds", r);
            else XposedHelpers.callMethod(v, "getIconBounds", (Integer) XposedHelpers.callMethod(v, "getIconSize"), r);
            if (!r.isEmpty()) return;
        } catch (Throwable ignored) {                           // виджет — методов значка нет
        }
        r.set(v.getPaddingLeft(), v.getPaddingTop(), v.getWidth() - v.getPaddingRight(), v.getHeight() - v.getPaddingBottom());
        if (r.width() <= 0 || r.height() <= 0) r.set(0, 0, v.getWidth(), v.getHeight());
    }

    /** Значки летают по всему экрану — рамки предков на время не обрезают (потом — как было). */
    private static void unclip() {
        unclipped.clear();
        clipWas.clear();
        for (View v : views) {
            for (ViewParent p = v.getParent(); p instanceof ViewGroup; p = p.getParent()) {
                ViewGroup g = (ViewGroup) p;
                if (unclipped.contains(g)) continue;
                unclipped.add(g);
                clipWas.add(new boolean[]{g.getClipChildren(), g.getClipToPadding()});
                g.setClipChildren(false);
                g.setClipToPadding(false);
            }
        }
    }

    private static void reclip() {
        for (int i = 0; i < unclipped.size(); i++) {
            unclipped.get(i).setClipChildren(clipWas.get(i)[0]);
            unclipped.get(i).setClipToPadding(clipWas.get(i)[1]);
        }
        unclipped.clear();
        clipWas.clear();
    }

    // ───────── кадры ─────────

    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long t) {
            if (!active || returning || world == null) return;
            float dt = lastFrame == 0L ? DT : Math.min((t - lastFrame) / 1e9f, 0.05f);
            lastFrame = t;
            accum += dt;
            // телефон лежит — в плоскости экрана тяжести почти нет, значки зависали бы в невесомости (и кадры не
            // кончались бы): слабая тяга вниз по экрану, пока наклон меньше ~18°
            float gxp = sgx, gyp = sgy, gm = (float) Math.hypot(gxp, gyp);
            if (gm < 3f) gyp += 3f - gm;
            world.gx = gxp * G_SCALE;
            world.gy = gyp * G_SCALE;
            int steps = 0;
            while (accum >= DT * 0.999f && steps < 4) {
                if (grab >= 0) steer();
                world.step(DT);
                accum -= DT;
                steps++;
            }
            if (steps == 4) accum = 0f;
            apply();
            // всё улеглось и никто не держит — кадры больше не нужны, пока не наклонят или не коснутся
            float maxV = 0f;
            for (int i = 0; i < views.size(); i++) {
                Physics.Body b = world.bodies[i];
                maxV = Math.max(maxV, Math.max(Math.abs(b.vx) + Math.abs(b.vy), Math.abs(b.w) * 0.2f));
            }
            calm = maxV < 0.03f && grab < 0 ? calm + 1 : 0;
            if (calm > 45) {
                sleeping = true;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static void wake() {
        if (!active || returning || !sleeping) return;
        sleeping = false;
        calm = 0;
        lastFrame = 0L;
        Choreographer.getInstance().postFrameCallback(FRAME);
    }

    private static void apply() {
        for (int i = 0; i < views.size(); i++) {
            Physics.Body b = world.bodies[i];
            set(views.get(i), i, b.x * PPM - ox[i], b.y * PPM - oy[i], b.rot);
        }
    }

    /** Вид сдвинут на (dx, dy) и повёрнут на rot вокруг центра своего тела. */
    private static void set(View v, int i, float dx, float dy, float rot) {
        if (Math.abs(dx) < 0.3f && Math.abs(dy) < 0.3f && Math.abs(rot) < 0.0005f) {
            v.setAnimationMatrix(null);
            return;
        }
        M.reset();
        M.postRotate((float) Math.toDegrees(rot), lx[i], ly[i]);
        M.postTranslate(dx, dy);
        v.setAnimationMatrix(M);
    }

    // ───────── касания: схватить, бросить, открыть ─────────

    private static int grab = -1;
    private static float fx, fy, downX, downY, grabLx, grabLy;
    private static long downT;
    private static android.view.VelocityTracker vt;

    private static void touch(MotionEvent e) {
        if (returning) return;
        float x = e.getRawX(), y = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                downT = e.getEventTime();
                fx = x;
                fy = y;
                grab = hit(x, y);
                if (vt != null) vt.recycle();
                vt = android.view.VelocityTracker.obtain();
                vt.addMovement(e);
                if (grab >= 0) {
                    Physics.Body b = world.bodies[grab];
                    // точка захвата в координатах тела — тянем за неё, тело поворачивается вокруг пальца
                    float c = (float) Math.cos(-b.rot), s = (float) Math.sin(-b.rot);
                    float px = x / PPM - b.x, py = y / PPM - b.y;
                    grabLx = c * px - s * py;
                    grabLy = s * px + c * py;
                }
                wake();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                fx = x;
                fy = y;
                if (vt != null) vt.addMovement(e);
                wake();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int slop = ViewConfiguration.get(views.isEmpty() ? null : views.get(0).getContext()).getScaledTouchSlop();
                boolean tap = e.getActionMasked() == MotionEvent.ACTION_UP && Math.hypot(x - downX, y - downY) < slop
                        && e.getEventTime() - downT < 350;
                int g = grab;
                if (vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000, 8000f);
                    if (g >= 0 && !tap) {                       // бросок — скорость пальца
                        Physics.Body b = world.bodies[g];
                        b.vx = vt.getXVelocity() / PPM;
                        b.vy = vt.getYVelocity() / PPM;
                    }
                    vt.recycle();
                    vt = null;
                }
                grab = -1;
                if (tap && g >= 0) {
                    View v = views.get(g);
                    finish(true);
                    v.postDelayed(() -> {
                        try {
                            v.performClick();
                        } catch (Throwable ignored) {
                        }
                    }, 260);
                }
                break;
            }
            default:
                break;
        }
    }

    /** Тело под пальцем — сверху (последнее нарисованное) вниз; точка переводится в систему тела. */
    private static int hit(float x, float y) {
        float px = x / PPM, py = y / PPM;
        for (int i = views.size() - 1; i >= 0; i--) {
            Physics.Body b = world.bodies[i];
            float c = (float) Math.cos(-b.rot), s = (float) Math.sin(-b.rot);
            float dx = px - b.x, dy = py - b.y;
            float lx2 = c * dx - s * dy, ly2 = s * dx + c * dy;
            if (Math.abs(lx2) <= b.hx + 0.02f && Math.abs(ly2) <= b.hy + 0.02f) return i;
        }
        return -1;
    }

    /** Схваченное тело тянется к пальцу: скорость точки захвата — к пальцу, вращение гасится. */
    private static void steer() {
        Physics.Body b = world.bodies[grab];
        float c = (float) Math.cos(b.rot), s = (float) Math.sin(b.rot);
        float gx = b.x + c * grabLx - s * grabLy, gy = b.y + s * grabLx + c * grabLy;
        float k = 18f;
        b.vx = (fx / PPM - gx) * k;
        b.vy = (fy / PPM - gy) * k;
        b.w *= 0.9f;
    }

    // ───────── возврат на места ─────────

    /** Всё на место: animate — разлёт пружиной (каждый по своей траектории), иначе мгновенно. */
    private static void finish(boolean animate) {
        if (!active) return;
        listenGravity(false);
        grab = -1;
        if (back != null) {
            ValueAnimator b0 = back;
            back = null;
            b0.cancel();
        }
        int n = views.size();
        if (!animate || world == null) {
            for (View v : views) v.setAnimationMatrix(null);
            done();
            return;
        }
        returning = true;
        float[] dx = new float[n], dy = new float[n], rot = new float[n];
        for (int i = 0; i < n; i++) {
            Physics.Body b = world.bodies[i];
            dx[i] = b.x * PPM - ox[i];
            dy[i] = b.y * PPM - oy[i];
            float r = (float) Math.IEEEremainder(b.rot, 2 * Math.PI);    // кратчайший поворот обратно
            rot[i] = r;
        }
        ShellAnims.Spring sp = new ShellAnims.Spring(0.5, 0.8);
        long dur = sp.durationMs();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(dur + 14L * n);
        va.addUpdateListener(an -> {
            long t = an.getCurrentPlayTime();
            for (int i = 0; i < n; i++) {
                float local = Math.max(0f, Math.min(1f, (t - 14f * i) / dur));
                float k = 1f - sp.getInterpolation(local);
                set(views.get(i), i, dx[i] * k, dy[i] * k, rot[i] * k);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (back != va) return;
                back = null;
                for (View v : views) v.setAnimationMatrix(null);
                done();
            }
        });
        back = va;
        va.start();
    }

    private static void done() {
        debugG = false;
        active = false;
        returning = false;
        sleeping = false;
        reclip();
        views.clear();
        world = null;
    }
}
