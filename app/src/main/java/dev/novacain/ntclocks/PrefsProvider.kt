package dev.novacain.ntclocks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.novacain.ntclocks.faces.ClockPrefs
import dev.novacain.ntclocks.faces.Clocks

/**
 * Настройки часов для SystemUI: content://dev.novacain.ntclocks.prefs/clock/<id> → одна строка, один столбец (JSON).
 * Только чтение: менять настройки может лишь само приложение.
 */
class PrefsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val id = uri.lastPathSegment ?: ""
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(Store.json(context!!, id))) }
    }

    /** Лаунчер присылает список папок и виджетов рабочего стола (для «прозрачных» в приложении). Только от лаунчера. */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (method == "homeItems" && callingPackage == "com.nothing.launcher" && arg != null) {
            context!!.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("homeItems", arg).apply()
        }
        // снимок часов экрана блокировки для редактора маски глубины (DepthHooks.clockShot)
        if (method == "clockShot" && callingPackage == "com.android.systemui" && arg != null) {
            extras?.getByteArray("png")?.let { java.io.File(context!!.filesDir, "clock_shot.png").writeBytes(it) }
            context!!.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("clockShotRect", arg).commit()
        }
        // модуль загрузился в SystemUI / лаунчере — отметка для статуса на главной (Alive)
        if (method == "alive" && callingPackage in Alive.PKGS) Alive.mark(context!!, callingPackage!!)
        return null
    }

    /** Вырезка своей маски глубины — только для SystemUI (он ставит её системе, см. DepthHooks.listenCutout). */
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        if (uri.lastPathSegment != "depth_cutout" || callingPackage != "com.android.systemui") throw SecurityException()
        val f = java.io.File(context!!.filesDir, "depth_cutout.png")
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.ntclocks.prefs"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
}

/**
 * Статус твикера: SystemUI и лаунчер при загрузке модуля отмечаются у провайдера (XposedEntry.reportAlive).
 * Отметка после последнего включения телефона = модуль в этом процессе действительно работает.
 */
object Alive {
    val PKGS = setOf("com.android.systemui", "com.nothing.launcher")

    private fun store(ctx: Context) = ctx.createDeviceProtectedStorageContext().getSharedPreferences("alive", Context.MODE_PRIVATE)

    fun mark(ctx: Context, pkg: String) = store(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply()

    /** Сбросить отметки (после снятия патча процессы перезапускаются уже без derealization). */
    fun clear(ctx: Context) = store(ctx).edit().clear().commit()

    /** Отметился ли процесс после загрузки телефона. */
    fun since(ctx: Context, pkg: String): Boolean {
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        return store(ctx).getLong(pkg, 0) > boot
    }
}

/** Хранилище настроек приложения (по JSON на каждые часы) + оповещение SystemUI об изменении. */
object Store {
    private const val FILE = "clocks"
    val BASE: Uri = Uri.parse("content://dev.novacain.ntclocks.prefs/clock")

    /** Хранилище, доступное до первой разблокировки (direct boot): настройки нужны экрану блокировки сразу. */
    private fun store(ctx: Context) = ctx.createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun json(ctx: Context, id: String): String = store(ctx).getString(id, null) ?: Clocks.defaults(id).toJson()

    fun load(ctx: Context, id: String): ClockPrefs = ClockPrefs.fromJson(json(ctx, id), Clocks.defaults(id))

    fun save(ctx: Context, id: String, p: ClockPrefs) {
        store(ctx).edit().putString(id, p.toJson()).apply()
        ctx.contentResolver.notifyChange(Uri.withAppendedPath(BASE, id), null)
    }
}
