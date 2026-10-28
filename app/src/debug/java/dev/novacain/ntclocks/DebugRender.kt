package dev.novacain.ntclocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import dev.novacain.ntclocks.faces.ClockFace
import dev.novacain.ntclocks.faces.Clocks
import java.io.File

/**
 * Отладка из adb (закрыто DUMP): нарисовать все часы вне экрана в листы-картинки, не трогая экран телефона.
 * am broadcast -n dev.novacain.ntclocks/.DebugRender → files/render/sheet-N.png
 */
/** Вне окна TextView не сдвигает центрированную однострочную строку в видимую область (это делает onPreDraw) — делаем сами. */
private fun preDraw(v: View) {
    if (v is android.widget.TextView) v.onPreDraw()
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) preDraw(v.getChildAt(i))
}

class DebugRender : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            intent.getStringExtra("dump")?.let { id ->
                // дерево видов одного стиля: что внутри, видно ли, какого размера
                val face = Clocks.create(ctx, id, ClockFace.FACE_LARGE)
                face.setPrefs(Store.load(ctx, id)); face.refreshTime()
                val v = face.asView()
                v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                v.layout(0, 0, 1080, v.measuredHeight)
                preDraw(v)
                fun walk(x: View, d: Int) {
                    val tv = x as? android.widget.TextView
                    val txt = tv?.let { "${it.text.toString().take(30)}» color=${Integer.toHexString(it.currentTextColor)} size=${it.textSize} lines=${it.layout?.lineCount} lw=${it.layout?.width} grav=${Integer.toHexString(it.gravity)} align=${it.textAlignment} tf=${it.typeface}" }
                    Log.i("NtClocks", "  ".repeat(d) + "${x.javaClass.simpleName} vis=${x.visibility} ${x.left},${x.top} ${x.width}x${x.height} a=${x.alpha} s=${x.scaleX}" + (txt?.let { " «$it»" } ?: ""))
                    if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i), d + 1)
                }
                walk(v, 0)
                val bmp = Bitmap.createBitmap(1080, v.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { drawColor(0xFF1B1D24.toInt()); v.draw(this) }
                File(ctx.getExternalFilesDir(null), "dump.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                pending.finish()
                return@post
            }
            try {
                val dir = File(ctx.getExternalFilesDir(null), "render").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val w = 1080
                val ids = Clocks.IDS
                val perSheet = 12
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 34f }
                ids.toList().chunked(perSheet).forEachIndexed { sheet, chunk ->
                    val cellH = 620
                    val bmp = Bitmap.createBitmap(w * 3, cellH * ((chunk.size + 2) / 3), Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    c.drawColor(0xFF1B1D24.toInt())
                    chunk.forEachIndexed { i, id ->
                        val face = Clocks.create(ctx, id, ClockFace.FACE_LARGE)
                        face.setPrefs(Store.load(ctx, id))
                        face.setRegionDark(true)
                        face.refreshTime()                       // TextClock вне окна сам текст не заполняет
                        val v = face.asView()
                        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(cellH - 60, View.MeasureSpec.AT_MOST))
                        v.layout(0, 0, w, v.measuredHeight)
                        preDraw(v)
                        val x = (i % 3) * w.toFloat(); val y = (i / 3) * cellH.toFloat()
                        c.save(); c.translate(x, y + 50); c.clipRect(0, 0, w, cellH - 60); v.draw(c); c.restore()
                        c.drawText("$id  ${Clocks.title(id)}  h=${v.measuredHeight}", x + 20, y + 40, label)
                    }
                    File(dir, "sheet-$sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                Log.i("NtClocks", "отрисовано: ${ids.size} часов → $dir")
            } catch (t: Throwable) {
                Log.e("NtClocks", "отрисовка не удалась", t)
            } finally {
                pending.finish()
            }
        }
    }
}
