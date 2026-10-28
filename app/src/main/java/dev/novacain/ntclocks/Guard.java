package dev.novacain.ntclocks;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import de.robv.android.xposed.XposedBridge;

/**
 * Безопасный режим derealization. Процесс-цель (SystemUI, лаунчер, «Обои», «Настройки») при падении записывает его
 * в свою папку данных (/data/user_de/0/пакет/files/derealization_guard) — перехватчик необработанных исключений
 * ставится при старте. Три падения подряд (без 45 с нормальной работы между ними) → на следующем запуске
 * derealization в нём не включается, процесс поднимается заводским. Обычное закрытие процесса системой падением не
 * считается (иначе часто закрываемые «Настройки» уходили бы в безопасный режим зря). Root не нужен: решает сам
 * процесс. Патчер видит это (читает файл через root) и сбрасывает при «Патч».
 */
public final class Guard {
    private Guard() {
    }

    static final int LIMIT = 3;
    static final long STABLE_MS = 45_000;

    static File file(String pkg) {
        return new File("/data/user_de/0/" + pkg + "/files/derealization_guard");
    }

    /** true — можно внедряться; false — безопасный режим. */
    public static boolean enter(String pkg) {
        File f = file(pkg);
        int crashes = 0;
        boolean safe = false;
        try {
            if (f.exists()) {
                String[] s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
                crashes = Integer.parseInt(s[0]);
                safe = s.length > 1 && "safe".equals(s[1]);
            }
        } catch (Throwable ignored) {
        }
        if (safe || crashes >= LIMIT) {
            if (!safe) write(f, crashes + " safe");
            XposedBridge.log("derealization: safe mode — " + pkg + " kept crashing, patch disabled there");
            return false;
        }
        final int before = crashes;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            write(f, String.valueOf(before + 1));
            if (prev != null) prev.uncaughtException(th, ex);
        });
        try {
            if (before > 0) new Handler(Looper.getMainLooper()).postDelayed(() -> write(f, "0"), STABLE_MS);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void write(File f, String s) {
        try {
            File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            XposedBridge.log("derealization: launch counter — " + t);
        }
    }
}
