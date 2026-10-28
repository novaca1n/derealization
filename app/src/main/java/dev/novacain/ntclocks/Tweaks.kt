package dev.novacain.ntclocks

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * Наследие старой кнопки «Выключить все твики» (до 2.0): она запоминала настройки всех твиков (Settings.Global
 * ntweaker_*) и выключала их. Кнопки больше нет, но если такая копия осталась — патч возвращает настройки из неё.
 */
internal object Tweaks {
    private val KEYS = listOf("ntweaker_anim", "ntweaker_glass", "ntweaker_lucent", "ntweaker_depth", "ntweaker_nav", "ntweaker_color")
    private const val BACKUP = "tweaksBackup"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun isOff(ctx: Context) = prefs(ctx).contains(BACKUP)

    fun restoreAll(ctx: Context) {
        val cr = ctx.contentResolver
        val backup = runCatching { JSONObject(prefs(ctx).getString(BACKUP, "{}")!!) }.getOrDefault(JSONObject())
        KEYS.forEach { k ->
            if (backup.has(k)) runCatching { Settings.Global.putString(cr, k, backup.getString(k)) }
        }
        prefs(ctx).edit().remove(BACKUP).commit()
    }
}
