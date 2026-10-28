package dev.novacain.ntclocks.anim;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;

/**
 * Заблокирован ли экран — с кэшем. KeyguardManager.isKeyguardLocked() — межпроцессный вызов в system_server, а его
 * звали на каждом кадре шторки (и не один раз). За время жеста состояние блокировки не меняется.
 */
final class Lock {
    private Lock() {
    }

    private static long at;
    private static boolean locked;

    static boolean locked(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - at > 300) {
            KeyguardManager km = c.getSystemService(KeyguardManager.class);
            locked = km != null && km.isKeyguardLocked();
            at = now;
        }
        return locked;
    }
}
