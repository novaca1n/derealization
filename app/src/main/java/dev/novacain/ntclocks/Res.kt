package dev.novacain.ntclocks

import android.content.Context

/**
 * Interface text from resources — values/ is English, values-ru/ Russian. Works both inside and outside composables.
 * The context is set by PrefsProvider, which Android creates when the app process starts, before any activity.
 */
internal object Res {
    lateinit var ctx: Context
}

internal fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) Res.ctx.getString(id) else Res.ctx.getString(id, *args)
