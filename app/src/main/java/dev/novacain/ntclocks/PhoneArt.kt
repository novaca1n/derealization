package dev.novacain.ntclocks

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.sin

/** Какой телефон рисовать: по ro.product.device. */
enum class Phone { CmfPhone2Pro, NothingPhone3, Other }

fun currentPhone(): Phone = when (Build.DEVICE.lowercase()) {
    "galaga" -> Phone.CmfPhone2Pro
    "metroid" -> Phone.NothingPhone3
    else -> Phone.Other
}

/**
 * Силуэт телефона владельца над названием — белый, как значки устройств на виджете батареи Nothing: плоский корпус
 * и чёрные вырезы (камеры, винт CMF, Glyph Matrix у Phone (3)). При входе выплывает пружиной, потом парит.
 */
@Composable
fun PhoneSilhouette(modifier: Modifier = Modifier) {
    val phone = remember { currentPhone() }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 120f)) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) time += (now - last) / 1e9f; last = now }
    }
    Canvas(
        modifier.graphicsLayer {
            val e = enter.value
            translationY = (1f - e) * 60f * density + sin(time * 0.9f) * 4f * density
            rotationZ = -6f * (1f - e) + sin(time * 0.6f) * 2f
            alpha = e.coerceIn(0f, 1f)
            scaleX = 0.8f + 0.2f * e; scaleY = scaleX
        }
    ) { Kit(this).draw(phone) }
}

/** Рисунок в квадрате 0..1; корпус — прямоугольник (0.26, 0.04, 0.48 × 0.92), детали — в долях корпуса (как в cmf link). */
private class Kit(val d: DrawScope) {
    val s = d.size.minDimension
    val ox = (d.size.width - s) / 2
    val oy = (d.size.height - s) / 2
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    private fun bx(u: Float) = 0.26f + 0.48f * u
    private fun by(v: Float) = 0.04f + 0.92f * v
    private fun bs(r: Float) = 0.48f * r
    fun dotB(c: Color, u: Float, v: Float, r: Float) = d.drawCircle(c, bs(r) * s, p(bx(u), by(v)))

    val cut = Color(0xFF141414)

    fun draw(phone: Phone) {
        // всё белое, как значки устройств на виджете батареи Nothing: белый корпус, чёрные вырезы
        val body = Color(0xFFF4F4F4)
        d.drawRoundRect(body, p(0.26f, 0.04f), Size(0.48f * s, 0.92f * s), CornerRadius(0.08f * s))
        when (phone) {
            Phone.CmfPhone2Pro -> {
                dotB(cut, 0.18f, 0.07f, 0.13f)
                dotB(cut, 0.18f, 0.2f, 0.13f)
                dotB(cut, 0.43f, 0.105f, 0.08f)
                dotB(cut, 0.82f, 0.915f, 0.11f)                   // винт CMF — тёмное кольцо
                dotB(body, 0.82f, 0.915f, 0.065f)
            }
            Phone.NothingPhone3 -> {
                dotB(cut, 0.15f, 0.07f, 0.11f)
                dotB(cut, 0.18f, 0.24f, 0.13f)
                dotB(cut, 0.48f, 0.24f, 0.13f)
                dotB(cut, 0.8f, 0.08f, 0.13f)                     // Glyph Matrix — тёмный круг с точками
                for ((u, v) in listOf(0.76f to 0.065f, 0.84f to 0.065f, 0.8f to 0.095f)) dotB(body, u, v, 0.022f)
            }
            Phone.Other -> dotB(cut, 0.2f, 0.08f, 0.12f)
        }
    }
}
