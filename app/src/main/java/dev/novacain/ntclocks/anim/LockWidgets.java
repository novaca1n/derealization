package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Виджеты экрана блокировки в стиле Nothing. Виджеты Nothing на экране блокировки — их собственные карточки, не
 * Android-виджеты (чужие туда не добавить), поэтому свой ряд встраивается в их контейнер NTWidgetContainer
 * (LinearLayout под часами): он уже привязан к часам, уведомления идут ниже него, видимость в AOD ведёт Nothing.
 * Данные: наушники и часы — провайдер cmf link (content://dev.novacain.cmflink.status, оповещает об изменениях),
 * музыка — медиасессии системы (у SystemUI есть MEDIA_CONTENT_CONTROL).
 */
public final class LockWidgets {
    private LockWidgets() {
    }

    static final String TAG = "derealization_widgets";
    static final Uri CMF = Uri.parse("content://dev.novacain.cmflink.status");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<WidgetCards.Row>> rows = new ArrayList<>();
    private static Handler bg;
    private static boolean started, cmfLogged;

    public static volatile WidgetPrefs prefs = new WidgetPrefs();
    /** Последнее состояние из cmf link (null — нет приложения или устройств). */
    static volatile JSONObject cmf;

    public static void hook(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.nothing.systemui.widget.NTWidgetContainer", cl);
        if (c == null) {
            XposedBridge.log("derealization: lock screen widgets — container not found");
            return;
        }
        Hook.all(c, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    attach((LinearLayout) p.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: lock screen widgets — " + t);
                }
            }
        });
        // AOD: ряд прячем, не убирая места (иначе часы и уведомления дёрнутся); проснулся — свежие данные
        Hook.all(c, "onDozingChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                WidgetCards.Row row = (WidgetCards.Row) ((ViewGroup) p.thisObject).findViewWithTag(TAG);
                if (row == null) return;
                boolean dozing = (Boolean) p.args[0];
                row.setDozing(dozing);
                if (!dozing && bg != null) bg.post(() -> loadCmf(row.getContext().getContentResolver()));
            }
        });
        XposedBridge.log("derealization: lock screen widgets hooked");
    }

    private static void attach(LinearLayout container) {
        if (container.findViewWithTag(TAG) != null) return;
        container.setOrientation(LinearLayout.VERTICAL);         // свой ряд — под виджетами Nothing
        Context ctx = container.getContext();
        WidgetCards.Row row = new WidgetCards.Row(ctx);
        row.setTag(TAG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * ctx.getResources().getDisplayMetrics().density);
        container.addView(row, lp);
        rows.add(new WeakReference<>(row));
        start(ctx);
        row.update();
    }

    private static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        HandlerThread th = new HandlerThread("derealization-widgets");
        th.start();
        bg = new Handler(th.getLooper());
        ContentResolver cr = ctx.getContentResolver();
        cr.registerContentObserver(CMF, false, new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange) {
                loadCmf(cr);
            }
        });
        // отладка без устройств: adb shell settings put global ntweaker_lockwidgets_demo 1 — карточки с примером
        cr.registerContentObserver(Settings.Global.getUriFor(DEMO_KEY), false, new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange) {
                loadCmf(cr);
            }
        });
        cr.registerContentObserver(Settings.Global.getUriFor(WidgetPrefs.KEY), false, new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange) {
                prefs = WidgetPrefs.read(cr);
                MAIN.post(LockWidgets::refresh);
            }
        });
        bg.post(() -> {
            prefs = WidgetPrefs.read(cr);
            loadCmf(cr);
        });
        MAIN.post(() -> Media.start(ctx));
    }

    static final String DEMO_KEY = "ntweaker_lockwidgets_demo";

    private static void loadCmf(ContentResolver cr) {
        if (Settings.Global.getInt(cr, DEMO_KEY, 0) == 1) {
            MAIN.post(LockWidgets::demo);
            return;
        }
        JSONObject o = null;
        try (Cursor c = cr.query(CMF, null, null, null, null)) {
            if (c != null && c.moveToFirst()) o = new JSONObject(c.getString(0));
        } catch (Throwable t) {
            // cmf link не установлен (или старый, без провайдера) — виджетов наушников и шагов просто нет
            if (!cmfLogged) XposedBridge.log("derealization: widgets — cmf link unavailable: " + t);
            cmfLogged = true;
        }
        if (o != null && !cmfLogged) { XposedBridge.log("derealization: widgets — cmf link: " + o); cmfLogged = true; }
        cmf = o;
        MAIN.post(LockWidgets::refresh);
    }

    /** Пример данных: предпросмотр в настройках и отладка на экране блокировки без наушников, часов и музыки. */
    public static void demo() {
        try {
            cmf = new JSONObject("{\"buds\":{\"left\":84,\"right\":76,\"case\":40,\"caseCharging\":true},"
                    + "\"watch\":{\"steps\":6214,\"battery\":62}}");
        } catch (Throwable ignored) {
        }
        Media.title = "Derealization";
        Media.artist = "NovaCain";
        Media.playing = true;
        Media.duration = 214_000;
        Media.position = 71_000;
        Media.speed = 1f;
        Media.updated = android.os.SystemClock.elapsedRealtime();
        int n = 96;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                double dx = x - n * 0.35, dy = y - n * 0.4, r = Math.sqrt(dx * dx + dy * dy) / n;
                float t = (float) Math.min(1, r * 1.6);
                px[y * n + x] = android.graphics.Color.rgb(Math.round(240 - 150 * t), Math.round(71 + 20 * t), Math.round(30 + 170 * t));
            }
        Media.art = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        Media.demo = true;
        refresh();
    }

    static void refresh() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            WidgetCards.Row r = rows.get(i).get();
            if (r == null) rows.remove(i);
            else r.update();
        }
    }

    static void post(Runnable r) {
        if (bg != null) bg.post(r);
    }

    /** Музыка: активная медиасессия (сначала играющая), название, исполнитель, обложка и позиция трека. */
    static final class Media {
        static MediaController controller;
        static String title, artist;
        static Bitmap art;                        // уменьшенная копия обложки (до 256 px), null — обложки нет
        static boolean playing;
        static long duration, position, updated; // мс; позиция — на момент updated (elapsedRealtime)
        static float speed;

        /** Доля прослушанного трека сейчас (с поправкой на время с последнего обновления состояния). */
        static float progress() {
            if (duration <= 0) return 0f;
            long pos = position + (playing ? (long) ((android.os.SystemClock.elapsedRealtime() - updated) * speed) : 0);
            return Math.max(0f, Math.min(1f, pos / (float) duration));
        }

        private static void state(PlaybackState s) {
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (s != null) {
                position = s.getPosition();
                speed = s.getPlaybackSpeed();
                updated = s.getLastPositionUpdateTime();
            }
        }
        static boolean demo;
        static boolean debugLog = false;          // true — писать в лог каждую смену состояния плеера                      // пример (LockWidgets.demo) — карточка видна без сессии
        private static MediaSessionManager msm;
        private static final MediaController.Callback CB = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                read();
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (debugLog) XposedBridge.log("derealization: music widget — state " + state);
                boolean was = playing;
                state(state);
                if (was != playing) refresh();
                if (!playing) pick(sessions());             // остановилась — может, играет другая
            }

            @Override
            public void onSessionDestroyed() {
                pick(sessions());
            }
        };

        static void start(Context ctx) {
            try {
                msm = ctx.getSystemService(MediaSessionManager.class);
                msm.addOnActiveSessionsChangedListener(Media::pick, null, MAIN);
                pick(sessions());
            } catch (Throwable t) {
                XposedBridge.log("derealization: music widget — " + t);
            }
        }

        private static List<MediaController> sessions() {
            try {
                return msm != null ? msm.getActiveSessions(null) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private static void pick(List<MediaController> list) {
            MediaController best = null;
            if (list != null) {
                for (MediaController m : list) {
                    PlaybackState s = m.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
                    if (best == null && m.getMetadata() != null) best = m;
                }
            }
            if (controller != null && (best == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
                controller.unregisterCallback(CB);
            }
            if (best != null && (controller == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
                best.registerCallback(CB, MAIN);
            }
            controller = best;
            read();
        }

        private static void read() {
            if (demo) return;
            MediaController c = controller;
            MediaMetadata md = c != null ? c.getMetadata() : null;
            PlaybackState s = c != null ? c.getPlaybackState() : null;
            state(s);
            if (md == null) {
                title = null; artist = null; art = null; duration = 0;
                refresh();
                return;
            }
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            Bitmap a = art;
            refresh();
            post(() -> {
                Bitmap small = null;
                try {
                    if (a != null && !a.isRecycled()) {
                        Bitmap sw = a.getConfig() == Bitmap.Config.HARDWARE ? a.copy(Bitmap.Config.ARGB_8888, false) : a;
                        float k = Math.min(1f, 256f / Math.max(sw.getWidth(), sw.getHeight()));
                        small = k < 1f ? Bitmap.createScaledBitmap(sw, Math.max(1, Math.round(sw.getWidth() * k)),
                                Math.max(1, Math.round(sw.getHeight() * k)), true) : sw.copy(Bitmap.Config.ARGB_8888, false);
                    }
                } catch (Throwable ignored) {
                }
                Bitmap res = small;
                MAIN.post(() -> {
                    Media.art = res;                       // поле, не локальная art из read()
                    refresh();
                });
            });
        }

        static void action(int what) {
            MediaController c = controller;
            if (c == null) return;
            MediaController.TransportControls t = c.getTransportControls();
            if (what < 0) t.skipToPrevious();
            else if (what > 0) t.skipToNext();
            else {
                // состояние — у плеера, а не запомненное: уведомление о смене могло ещё не дойти
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
        }
    }
}
