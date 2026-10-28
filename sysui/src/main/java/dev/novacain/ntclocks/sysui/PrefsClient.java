package dev.novacain.ntclocks.sysui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.novacain.ntclocks.faces.ClockFace;
import dev.novacain.ntclocks.faces.ClockPrefs;
import dev.novacain.ntclocks.faces.Clocks;

/**
 * Настройки часов внутри процесса SystemUI. Читаются из ContentProvider приложения в фоновом потоке (вызов идёт
 * через binder — на главном потоке SystemUI его быть не должно), раздаются живым видам часов на главном потоке.
 */
final class PrefsClient {
    static final Uri BASE = Uri.parse("content://dev.novacain.ntclocks.prefs/clock");
    private static final String TAG = "NtClocks";
    private static PrefsClient instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler bg;
    // фоновый поток читает ключи, главный — пишет: только потокобезопасные словари
    private final Map<String, ClockPrefs> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<WeakReference<ClockFace>>> views = new java.util.concurrent.ConcurrentHashMap<>();

    /** Уже созданный клиент (или null, если часы модуля ещё не подключались). */
    static synchronized PrefsClient peek() {
        return instance;
    }

    static synchronized PrefsClient get(Context context) {
        if (instance == null) instance = new PrefsClient(context.getApplicationContext() != null ? context.getApplicationContext() : context);
        return instance;
    }

    private PrefsClient(Context context) {
        this.context = context;
        HandlerThread t = new HandlerThread("NtClocksPrefs");
        t.start();
        bg = new Handler(t.getLooper());
        try {
            context.getContentResolver().registerContentObserver(BASE, true, new ContentObserver(bg) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    String id = uri != null ? uri.getLastPathSegment() : null;
                    if (id != null && Clocks.isOurs(id)) reload(id);
                    else reloadAll();
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "не удалось подписаться на настройки (приложение не установлено?)", e);
        }
        try {
            // страховка: если в самом раннем старте настройки не прочитались — перечитать после разблокировки
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    bg.post(PrefsClient.this::reloadAll);
                }
            }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, bg, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable e) {
            Log.w(TAG, "нет подписки на разблокировку", e);
        }
        bg.post(this::reloadAll);
    }

    /** Текущие настройки (пока не прочитаны — значения по умолчанию). Только главный поток. */
    ClockPrefs prefs(String id) {
        ClockPrefs p = cache.get(id);
        return p != null ? p : Clocks.defaults(id);
    }

    /** Вид будет получать новые настройки при каждом изменении в приложении. Только главный поток. */
    void attach(String id, ClockFace v) {
        List<WeakReference<ClockFace>> list = views.get(id);
        if (list == null) views.put(id, list = new ArrayList<>());
        list.add(new WeakReference<>(v));
        v.setPrefs(prefs(id));
        if (!cache.containsKey(id)) bg.post(() -> reload(id));       // первый раз — подтянуть настройки этих часов
    }

    /** Перечитать только часы, которые уже видели (их ~70 — не дёргать провайдер лишний раз). */
    private void reloadAll() {
        java.util.Set<String> ids = new java.util.HashSet<>(cache.keySet());
        ids.addAll(views.keySet());
        for (String id : ids) reload(id);
    }

    private void reload(String id) {
        final String json = query(id);
        main.post(() -> apply(id, ClockPrefs.fromJson(json, Clocks.defaults(id))));
    }

    private void apply(String id, ClockPrefs p) {
        ClockPrefs old = cache.put(id, p);
        if (old == null || old.hideSystemDate != p.hideSystemDate) Injector.refreshSlice();
        List<WeakReference<ClockFace>> list = views.get(id);
        if (list == null) return;
        for (Iterator<WeakReference<ClockFace>> it = list.iterator(); it.hasNext(); ) {
            ClockFace v = it.next().get();
            if (v == null) it.remove();
            else v.setPrefs(p.copy());
        }
    }

    private String query(String id) {
        try (Cursor c = context.getContentResolver().query(Uri.withAppendedPath(BASE, id), null, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable e) {
            Log.w(TAG, "настройки " + id + " не прочитаны: " + e);
        }
        return null;
    }
}
