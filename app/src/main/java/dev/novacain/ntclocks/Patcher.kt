package dev.novacain.ntclocks

import android.content.Context
import kotlinx.coroutines.delay

/**
 * derealization — патчер. «Патч» по-настоящему внедряет код в системные процессы через Vector (форк LSPosed):
 * включает модуль, назначает ему цели (SystemUI, лаунчер, «Обои и стиль») и перезапускает их — новые процессы
 * поднимаются уже с derealization. «Снять патч» выключает модуль и перезапускает их же — всё как у Nothing.
 * Файлы системы не меняются (SystemUI подписан ключом платформы — переподписанный не запустится; лаунчер Nothing
 * обновляет через свой магазин), поэтому патч обратим одной кнопкой. Каждый шаг — реальная команда через root.
 */
internal object Patcher {
    private const val CLI = "/data/adb/modules/zygisk_vector/cli"
    private const val PKG = "dev.novacain.ntclocks"
    const val STORE = "dev.novacain.tweakstore"
    private val TARGETS = listOf("com.android.systemui", "com.nothing.launcher", "com.nothing.wallpaper", "com.android.settings")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Пропатчено ли (по нашему последнему действию; до первого — считаем, что да, если модуль уже работал). */
    fun isPatched(ctx: Context): Boolean = prefs(ctx).getBoolean("patched", true)

    private val NAMES = mapOf("com.android.systemui" to "SystemUI", "com.nothing.launcher" to "лаунчер",
        "com.nothing.wallpaper" to "«Обои и стиль»", "com.android.settings" to "«Настройки»")

    /** Цели, ушедшие в безопасный режим (Guard: падали три раза подряд — derealization в них не включается). */
    suspend fun safeMode(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        TARGETS.filter { su("cat /data/user_de/0/$it/files/derealization_guard 2>/dev/null").second.contains("safe") }
            .map { NAMES[it] ?: it }
    }

    private fun clearGuards() {
        su(TARGETS.joinToString("; ") { "rm -f /data/user_de/0/$it/files/derealization_guard" })
    }

    /**
     * Совместимость: сборка Nothing OS и версии лаунчера и «Обоев», на которых derealization проверен. Другая — Nothing
     * обновила систему, часть твиков может не встать. null — всё как было.
     */
    private const val TESTED_BUILD = "B4.1-260812"
    private val TESTED = mapOf("com.nothing.launcher" to 40020116L, "com.nothing.wallpaper" to 1082400L)

    fun compatibility(ctx: Context): String? {
        val changed = mutableListOf<String>()
        if (!android.os.Build.DISPLAY.startsWith(TESTED_BUILD)) changed += "Nothing OS ${android.os.Build.DISPLAY}"
        for ((pkg, code) in TESTED) {
            val v = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode }.getOrNull() ?: continue
            if (v != code) changed += NAMES[pkg] ?: pkg
        }
        return if (changed.isEmpty()) null else "Обновилось: " + changed.joinToString(", ") + " — часть твиков может не встать"
    }

    /** Что не встало с последней загрузки: строки «не найдено» и ошибки из журнала Vector. */
    suspend fun failures(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
        su("cat /data/adb/lspd/log/modules_*.log 2>/dev/null | grep -E 'ntweaker|derealization'").second.lineSequence()
            .filter { it.contains("не найден") || it.contains("Exception") || it.contains("Error") }
            .filter { l -> runCatching { fmt.parse(l.substringAfter("[ ").take(19))!!.time >= boot }.getOrDefault(false) }
            .map { it.substringAfter("] ").substringAfter(": ").take(90) }
            .distinct().toList()
    }

    fun remember(ctx: Context, patched: Boolean) = prefs(ctx).edit().putBoolean("patched", patched).apply()

    /** Включён ли модуль по самому Vector (null — не удалось спросить). Вызывать не из главного потока. */
    suspend fun vectorEnabled(): Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val (rc, out) = su("$CLI modules ls")
        if (rc != 0) null
        else out.lineSequence().firstOrNull { it.trim().startsWith(PKG) }?.let { it.contains("enabled") && !it.contains("disabled") }
    }

    private fun su(cmd: String): Pair<Int, String> = try {
        // --mount-master: в общем пространстве монтирования — из пространства приложения Android прячет папки данных
        // других приложений (а там счётчики безопасного режима)
        val p = ProcessBuilder("su", "--mount-master", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() to out
    } catch (t: Throwable) {
        -1 to (t.message ?: t.toString())
    }

    /** Общее начало: root и Vector. null — можно продолжать, иначе текст ошибки. */
    private suspend fun preflight(step: suspend (String) -> Unit): String? {
        val (rc, id) = su("id -u")
        if (rc != 0 || id.trim() != "0") return "Нет root — без него патч невозможен"
        step("Root получен")
        if (su("test -x $CLI").first != 0) return "Vector не найден — нужен Vector (LSPosed)"
        step("Vector на месте")
        return null
    }

    private suspend fun restart(step: suspend (String) -> Unit) {
        su("am force-stop com.nothing.wallpaper")
        step("«Обои и стиль» перезапущены")
        su("am force-stop com.android.settings")
        step("«Настройки» перезапущены")
        su("am force-stop com.nothing.launcher")
        step("Лаунчер перезапущен")
    }

    /**
     * SystemUI — последним, уже после «Готово», в затемнении (как respring в конце у Dopamine): после его перезапуска
     * телефон блокируется, и посреди журнала это выглядело бы обрывом. Отдельный kill по pid (pkill -f совпал бы со
     * строкой собственной команды).
     */
    fun restartSystemUi() {
        su("kill \$(pidof com.android.systemui)")
    }

    suspend fun patch(ctx: Context, step: suspend (String) -> Unit): Boolean {
        preflight(step)?.let { step(it); return false }
        val (rc, out) = su("$CLI modules enable $PKG")
        if (rc != 0) { step("Vector не включил модуль: $out".take(120)); return false }
        step("derealization включён в Vector")
        val scope = TARGETS.joinToString(" ") { "$it/0" }
        val (rc2, out2) = su("$CLI scope set $PKG $scope")
        if (rc2 != 0) { step("Vector не принял цели: $out2".take(120)); return false }
        step("Цели: SystemUI, лаунчер, «Обои и стиль», «Настройки»")
        if (Tweaks.isOff(ctx)) { Tweaks.restoreAll(ctx); step("Настройки возвращены") }
        installStore(ctx, step)
        clearGuards()
        step("Счётчики безопасного режима сброшены")
        Alive.clear(ctx)
        restart(step)
        prefs(ctx).edit().putBoolean("patched", true).commit()
        // лаунчер отмечается через несколько секунд после старта (XposedEntry.reportAlive); SystemUI — после «Готово»
        var launcher = false
        for (i in 0 until 20) {
            delay(500)
            if (Alive.since(ctx, "com.nothing.launcher")) { launcher = true; step("Лаунчер: код внедрён"); break }
        }
        if (!launcher) step("Лаунчер ещё не отозвался — проверю позже")
        step("SystemUI перезапустится после затемнения")
        return true
    }

    /**
     * TweakStore ships inside derealization (assets/tweakstore.apk, see tools/bundle-tweakstore.sh) and is installed —
     * or updated, if the bundled one is newer — while patching. Extra tweaks live there, not in derealization itself.
     * Not fatal: patching goes on if it fails (e.g. a differently signed store is already installed).
     */
    private suspend fun installStore(ctx: Context, step: suspend (String) -> Unit) {
        val f = java.io.File(ctx.cacheDir, "tweakstore.apk")
        try {
            ctx.assets.open("tweakstore.apk").use { i -> f.outputStream().use { i.copyTo(it) } }
        } catch (_: Throwable) {
            return                                              // a build without the store bundled
        }
        val bundled = ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.longVersionCode
        val have = runCatching { ctx.packageManager.getPackageInfo(STORE, 0).longVersionCode }.getOrNull()
        if (bundled == null || (have != null && have >= bundled)) {
            f.delete()
            if (have != null) step(ctx.getString(R.string.patch_store_ok))
            return
        }
        val (rc, out) = su("cat '${f.path}' | pm install -r -S ${f.length()}")
        f.delete()
        step(ctx.getString(when {
            rc != 0 || !out.contains("Success") -> R.string.patch_store_failed
            have == null -> R.string.patch_store_installed
            else -> R.string.patch_store_updated
        }))
    }

    /** Opens TweakStore; installs the bundled one first if it's missing. Call off the main thread. */
    suspend fun openStore(ctx: Context): Boolean {
        if (ctx.packageManager.getLaunchIntentForPackage(STORE) == null) installStore(ctx) {}
        val i = ctx.packageManager.getLaunchIntentForPackage(STORE) ?: return false
        ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    suspend fun unpatch(ctx: Context, step: suspend (String) -> Unit): Boolean {
        preflight(step)?.let { step(it); return false }
        val (rc, out) = su("$CLI modules disable $PKG")
        if (rc != 0) { step("Vector не выключил модуль: $out".take(120)); return false }
        step("derealization выключен в Vector")
        Alive.clear(ctx)
        restart(step)
        step("SystemUI перезапустится после затемнения")
        prefs(ctx).edit().putBoolean("patched", false).commit()
        return true
    }
}
