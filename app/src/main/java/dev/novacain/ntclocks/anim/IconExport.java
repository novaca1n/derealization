package dev.novacain.ntclocks.anim;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Значки для Hive (свой лаунчер-соты, ~/Проекты/hive): лаунчер Nothing отдаёт итоговые значки приложений — те же, что
 * на его столе (однотонные, цветами ColorEngine, с генератором Nothing для приложений без монохромного слоя).
 * Работает и когда Nothing не лаунчер по умолчанию (его процесс всё равно живёт ради жестов и недавних): без активности,
 * через Application — IconCache из LauncherAppState: new AppInfo(ctx, LauncherActivityInfo, user) →
 * getTitleAndIcon(…, DEFAULT_LOOKUP_FLAG) → newIcon(ctx, FLAG_THEMED) → content://dev.novacain.hive.icons,
 * call("put", ключ, png), в конце call("done", список ключей).
 * Когда: по запросу Hive (broadcast EXPORT_ICONS — при её старте и смене пакетов) и сам через 8 с после старта
 * процесса (после смены цветов ColorEngine лаунчер перезапускается). Рисуется в фоновом потоке.
 */
public final class IconExport {
    private IconExport() {
    }

    private static final String ACTION = "dev.novacain.ntclocks.EXPORT_ICONS";
    private static final String HIVE = "dev.novacain.hive";
    private static final Uri INBOX = Uri.parse("content://dev.novacain.hive.icons");
    private static Handler bg;
    private static ClassLoader loader;

    public static void launcher(ClassLoader cl) {
        loader = cl;
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Application app = (Application) p.thisObject;
                if (!"com.nothing.launcher".equals(Application.getProcessName())) return;
                try {
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            export(app, i.getIntExtra("size", 0));
                        }
                    }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: Hive icons — " + t);
                }
                worker().postDelayed(() -> {
                    boolean has = installed(app);
                    XposedBridge.log("derealization: Hive icons — Hive " + (has ? "present" : "not visible"));
                    if (has) export(app, 0);
                }, 8000);
            }
        });
    }

    private static Handler worker() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("nt-hive-icons");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    private static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(HIVE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void export(Context ctx, int size) {
        int px = size > 0 ? size : Math.round(96 * ctx.getResources().getDisplayMetrics().density);
        Handler h = worker();
        h.removeCallbacksAndMessages(null);
        h.post(() -> run(ctx, px));
    }

    private static void run(Context ctx, int px) {
        XposedBridge.log("derealization: Hive icons — export, " + px + " px");
        Object cache, flag;
        Class<?> appInfo;
        Handler model;
        try {
            Class<?> las = XposedHelpers.findClass("com.android.launcher3.LauncherAppState", loader);
            Object state = XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(las, "INSTANCE"), "get", ctx);
            cache = XposedHelpers.callMethod(state, "getIconCache");
            flag = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.android.launcher3.icons.cache.CacheLookupFlag", loader), "DEFAULT_LOOKUP_FLAG");
            appInfo = XposedHelpers.findClass("com.android.launcher3.model.data.AppInfo", loader);
            // кэш значков разрешает доступ только из своего потока (BaseIconCache.bgLooper)
            model = new Handler((android.os.Looper) XposedHelpers.getObjectField(cache, "bgLooper"));
        } catch (Throwable t) {
            XposedBridge.log("derealization: Hive icons, icon cache — " + t);
            return;
        }
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        ArrayList<String> keys = new ArrayList<>();
        int sent = 0;
        for (UserHandle u : la.getProfiles()) {
            for (LauncherActivityInfo lai : la.getActivityList(null, u)) {
                try {
                    // значок строится в потоке кэша (быстро), сжатие и отправка — здесь, чтобы его не задерживать
                    Bitmap[] got = new Bitmap[1];
                    Throwable[] err = new Throwable[1];
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    Object c0 = cache, f0 = flag;
                    Class<?> a0 = appInfo;
                    model.post(() -> {
                        try {
                            Object info = XposedHelpers.newInstance(a0, ctx, lai, u);
                            XposedHelpers.callMethod(c0, "getTitleAndIcon", info, lai, f0);
                            Drawable d = (Drawable) XposedHelpers.callMethod(info, "newIcon", ctx, 1);   // 1 — FLAG_THEMED
                            // значки лаунчера — аппаратные битмапы: программный Canvas их не рисует, поэтому
                            // через Picture и отрисовку видеочипом, затем копия в обычный битмап для PNG
                            android.graphics.Picture pic = new android.graphics.Picture();
                            Canvas c = pic.beginRecording(px, px);
                            d.setBounds(0, 0, px, px);
                            d.draw(c);
                            pic.endRecording();
                            Bitmap hw = Bitmap.createBitmap(pic, px, px, Bitmap.Config.HARDWARE);
                            got[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        } catch (Throwable t) {
                            err[0] = t;
                        }
                        done.countDown();
                    });
                    if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) continue;
                    if (err[0] != null) throw err[0];
                    Bitmap b = got[0];
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                    b.recycle();
                    String key = lai.getComponentName().flattenToString() + "#" + u.hashCode();
                    Bundle e = new Bundle();
                    e.putByteArray("png", out.toByteArray());
                    try {
                        ctx.getContentResolver().call(INBOX, "put", key, e);
                    } catch (IllegalArgumentException t) {
                        XposedBridge.log("derealization: Hive icons — Hive isn't answering: " + t);
                        return;                                        // Hive не установлена
                    }
                    keys.add(key);
                    sent++;
                } catch (Throwable t) {
                    XposedBridge.log("derealization: Hive icons, " + lai.getComponentName() + " — " + t);
                }
            }
        }
        try {
            Bundle e = new Bundle();
            e.putStringArrayList("keys", keys);
            ctx.getContentResolver().call(INBOX, "done", null, e);
        } catch (Throwable ignored) {
        }
        XposedBridge.log("derealization: Hive icons sent — " + sent);
    }
}
