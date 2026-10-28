package dev.novacain.ntclocks

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * «Выключить все твики»: запоминает текущие настройки всех твиков (Settings.Global ntweaker_*) и выключает их;
 * «Вернуть» записывает запомненное обратно. Часы экрана блокировки и плитки в шторке не трогаются.
 * Оба действия возвращают шаги с реальными данными — их показывает анимация на главной (как джейлбрейк в Dopamine).
 */
internal object Tweaks {
    private val KEYS = listOf("ntweaker_anim", "ntweaker_glass", "ntweaker_lucent", "ntweaker_depth", "ntweaker_nav", "ntweaker_color")
    private val NAMES = mapOf(
        "ntweaker_anim" to "Анимации", "ntweaker_glass" to "Стекло", "ntweaker_lucent" to "Прозрачные виджеты",
        "ntweaker_depth" to "Эффект глубины", "ntweaker_nav" to "Полоска жестов", "ntweaker_color" to "Перекраска",
    )
    private const val BACKUP = "tweaksBackup"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun isOff(ctx: Context) = prefs(ctx).contains(BACKUP)

    /** Был ли набор настроек «включён» (по его JSON). */
    private fun active(k: String, json: String?): Boolean {
        val o = runCatching { JSONObject(json ?: return false) }.getOrNull() ?: return false
        return if (k == "ntweaker_anim") o.optString("style", "nothing") != "nothing" || o.optString("inApp", "native") != "native" ||
            o.optDouble("homeBlur", 0.0) > 0 || o.optString("drawer", "nothing") != "nothing" || o.optString("shade", "nothing") != "nothing" ||
            o.optBoolean("splitShade", false)
        else o.optBoolean("enabled", false) || (k == "ntweaker_glass" && o.optBoolean("notifRound", false))
    }

    private fun receivers(ctx: Context): String {
        val who = listOfNotNull("SystemUI".takeIf { Alive.since(ctx, "com.android.systemui") },
            "лаунчер".takeIf { Alive.since(ctx, "com.nothing.launcher") })
        return if (who.isEmpty()) "Применится после перезагрузки" else "Сразу подхватили: ${who.joinToString(" и ")}"
    }

    fun disableAll(ctx: Context): List<String> {
        Save.flush()
        val cr = ctx.contentResolver
        val backup = JSONObject()
        val was = KEYS.associateWith { Settings.Global.getString(cr, it) }
        was.forEach { (k, v) -> v?.let { backup.put(k, it) } }
        val text = backup.toString()
        prefs(ctx).edit().putString(BACKUP, text).commit()
        val steps = mutableListOf("Сохраняю настройки · ${backup.length()} наборов, ${text.length} байт")
        KEYS.forEach { k ->
            val o = runCatching { JSONObject(was[k] ?: "{}") }.getOrDefault(JSONObject())
            when (k) {
                "ntweaker_anim" -> o.put("style", "nothing").put("inApp", "native").put("depth", false)
                    .put("homeBlur", 0).put("drawer", "nothing").put("shade", "nothing").put("stretch", false).put("stretchBottom", false).put("iconPress", false).put("warp", false).put("splitShade", false).put("iconResize", false)
                "ntweaker_glass" -> o.put("enabled", false).put("notifRound", false)
                else -> o.put("enabled", false)
            }
            runCatching { Settings.Global.putString(cr, k, o.toString()) }
            if (active(k, was[k])) steps += "${NAMES[k]} → как у Nothing"
        }
        steps += receivers(ctx)
        return steps
    }

    fun restoreAll(ctx: Context): List<String> {
        val cr = ctx.contentResolver
        val backup = runCatching { JSONObject(prefs(ctx).getString(BACKUP, "{}")!!) }.getOrDefault(JSONObject())
        val steps = mutableListOf("Читаю сохранённое · ${backup.length()} наборов")
        KEYS.forEach { k ->
            if (!backup.has(k)) return@forEach
            runCatching { Settings.Global.putString(cr, k, backup.getString(k)) }
            if (active(k, backup.optString(k))) steps += "${NAMES[k]} — включено"
        }
        prefs(ctx).edit().remove(BACKUP).commit()
        steps += receivers(ctx)
        return steps
    }
}
