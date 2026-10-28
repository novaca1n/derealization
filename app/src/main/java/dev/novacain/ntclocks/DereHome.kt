package dev.novacain.ntclocks

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.drawscope.scale
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Патчер derealization — по устройству Dopamine (opa334/Dopamine 3.x, UI tomt000): слева заголовок (имя, система и
 * устройство, авторы), карточка-меню (строки с иконкой, разделителями и стрелкой), под ней кнопка той же карточкой.
 * Нажатие: меню уезжает вверх и сжимается, заголовок приподнимается, кнопка пружиной разворачивается в лист до низа
 * экрана; наверху листа «Патчу» с двойной спиралью из точек, внизу — журнал «как текст песни» (новая строка снизу,
 * текущая крупнее с крутилкой, пройденные мельче и тусклее с галочкой, верх растворяется). В конце — «Готово»,
 * открытые замки, затемнение в чёрное и обратно. Фон — живые пятна акцентных цветов системы (как карточка
 * «О телефоне»), меню и лист — полупрозрачная тёмная карточка, как actionMenuColor у Dopamine.
 */

// как actionMenuColor Dopamine — полупрозрачная карточка поверх живого фона, но цвета — роли Material 3 системы
private val MenuColor get() = Ui.surface.copy(alpha = 0.72f)
private val Sep get() = Ui.dim.copy(alpha = 0.22f)

private enum class Job { PATCH, UNPATCH }

@Composable
internal fun DereHome() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var off by remember { mutableStateOf(!Patcher.isPatched(ctx)) }
    var safe by remember { mutableStateOf(emptyList<String>()) }
    var notes by remember { mutableStateOf(emptyList<String>()) }
    var sys by remember { mutableStateOf(Alive.since(ctx, "com.android.systemui")) }
    var launcher by remember { mutableStateOf(Alive.since(ctx, "com.nothing.launcher")) }
    var job by remember { mutableStateOf<Job?>(null) }
    var credits by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var done by remember { mutableStateOf(false) }
    val black = remember { Animatable(0f) }
    val expand = remember { Animatable(0f) }                  // 0 — кнопка, 1 — лист
    var buttonRect by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(job) {
        if (job != null) return@LaunchedEffect
        Patcher.vectorEnabled()?.let { on -> if (on == off) { Patcher.remember(ctx, on); off = !on } }
        safe = Patcher.safeMode()
        val f = Patcher.failures()
        notes = listOfNotNull(Patcher.compatibility(ctx), if (f.isNotEmpty()) s(R.string.home_failed_count, f.size, f.first()) else null)
        while (true) {
            sys = Alive.since(ctx, "com.android.systemui")
            launcher = Alive.since(ctx, "com.nothing.launcher")
            delay(2000)
        }
    }

    val repatch = off || safe.isNotEmpty()

    fun fadeToBlack(then: suspend () -> Unit) = scope.launch {
        black.animateTo(1f, tween(450))
        then()
        delay(300)
        black.animateTo(0f, tween(700))
    }

    fun run(j: Job) {
        if (job != null) return
        job = j
        done = false
        log.clear()
        scope.launch {
            expand.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 260f))
        }
        scope.launch {
            delay(500)
            var last = 0L
            val step: suspend (String) -> Unit = { s ->
                val wait = 650L - (System.currentTimeMillis() - last)
                if (wait > 0) delay(wait)
                withContext(Dispatchers.Main) { log += s }
                last = System.currentTimeMillis()
            }
            val ok = withContext(Dispatchers.IO) {
                if (j == Job.PATCH) Patcher.patch(ctx, step) else Patcher.unpatch(ctx, step)
            }
            delay(500)
            if (ok) {
                log += s(R.string.done)
                done = true
                off = j == Job.UNPATCH
                if (j == Job.PATCH) safe = emptyList()
            }
            delay(if (ok) 1300 else 3200)
            black.animateTo(1f, tween(450))
            if (ok) withContext(Dispatchers.IO) { Patcher.restartSystemUi() }   // в темноте, как respring у Dopamine
            expand.snapTo(0f)
            job = null
            log.clear()
            delay(600)
            black.animateTo(0f, tween(700))
        }
    }

    val versionName = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val system = remember { systemLine(ctx) }
    val e = expand.value
    val menuShift by animateFloatAsState(if (job != null) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 420f), label = "menu")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        AccentBackground(energy = if (job != null) 1f else 0f)

        // ───── главный экран ─────
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 30.dp)
        ) {
            Spacer(Modifier.weight(0.55f))
            // заголовок: приподнимается на 25 dp, когда идёт патч
            Column(Modifier.graphicsLayer { translationY = -25.dp.toPx() * menuShift }) {
                Text("derealization", style = t(40, FontWeight.SemiBold, Ui.text))
                Text(system, style = t(14, FontWeight.Medium, Ui.text), modifier = Modifier.padding(top = 2.dp))
                Text("v$versionName · by novaca1n", style = t(14, c = Ui.dim))
                val statusLine = when {
                    safe.isNotEmpty() -> s(R.string.home_safe_mode, safe.joinToString())
                    !off && !(sys && launcher) -> s(R.string.home_patched_waiting)
                    else -> null
                }
                (listOfNotNull(statusLine) + if (!off) notes else emptyList()).forEach {
                    Text(it, style = t(13, c = Ui.warn), modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.weight(0.25f))
            // меню: при патче уезжает вверх на 75 dp, сжимается до 60 % и гаснет
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = 1f - menuShift
                    translationY = -75.dp.toPx() * menuShift
                    val s = 1f - 0.4f * menuShift; scaleX = s; scaleY = s
                }.clip(RoundedCornerShape(28.dp)).background(MenuColor).padding(horizontal = 20.dp, vertical = 5.dp)
            ) {
                val rows = buildList {
                    add(Triple(Glyph.GEAR, s(R.string.menu_settings), true) to { openWallpaperStyle(ctx) })
                    add(Triple(Glyph.STORE, ctx.getString(R.string.menu_store), true) to {
                        scope.launch(Dispatchers.IO) { Patcher.openStore(ctx) }
                    })
                    add(Triple(Glyph.RESTART, s(R.string.menu_restart_systemui), false) to {
                        fadeToBlack { withContext(Dispatchers.IO) { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill \$(pidof com.android.systemui)")).waitFor() } } }
                    })
                    add(Triple(Glyph.POWER, s(R.string.menu_reboot), false) to {
                        fadeToBlack { withContext(Dispatchers.IO) { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power reboot")).waitFor() } } }
                    })
                    if (!repatch) add(Triple(Glyph.LOCK, s(R.string.menu_unpatch), false) to { run(Job.UNPATCH) })
                    add(Triple(Glyph.INFO, s(R.string.menu_credits), true) to { credits = true })
                }
                rows.forEachIndexed { i, (r, action) ->
                    MenuRow(r.first, r.second, r.third, i != rows.lastIndex, enabled = job == null) { action() }
                }
            }
            Spacer(Modifier.height(30.dp))
            // кнопка: есть что делать — залитая акцентом «таблетка» Material 3, иначе — стекло, как карточка; при патче
            // разворачивается в лист (рисуется ниже поверх всего), её цвет перетекает в цвет листа
            val pressSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by pressSrc.collectIsPressedAsState()
            val btnCorner by androidx.compose.animation.core.animateDpAsState(if (pressed) 18.dp else 30.dp, label = "btn")
            Box(
                Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned { buttonRect = it.boundsInRoot() }
                    .graphicsLayer { alpha = if (job != null) 0f else 1f }
                    .clip(RoundedCornerShape(btnCorner)).background(if (repatch) Ui.accent else MenuColor)
                    .clickable(interactionSource = pressSrc, indication = androidx.compose.material3.ripple(), enabled = job == null && repatch) { run(Job.PATCH) },
                contentAlignment = Alignment.Center
            ) {
                val fg = if (repatch) Ui.onAccent else Ui.dim
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.LOCK, Modifier.size(18.dp), open = true, color = fg)
                    Spacer(Modifier.width(10.dp))
                    Text(if (safe.isNotEmpty()) s(R.string.home_repatch) else if (off) s(R.string.home_patch) else s(R.string.home_patched),
                        style = t(17, FontWeight.Medium, fg))
                }
            }
            Spacer(Modifier.weight(0.4f))
        }

        // ───── лист патча: из прямоугольника кнопки — во всю ширину от 26 % высоты до низа (и чуть за край) ─────
        if (job != null && buttonRect != Rect.Zero) {
            val top = lerp(buttonRect.top, hPx * 0.26f + with(density) { 35.dp.toPx() }, e)
            val left = lerp(buttonRect.left, 0f, e)
            val right = lerp(buttonRect.right, wPx, e)
            val bottom = lerp(buttonRect.bottom, hPx + with(density) { 100.dp.toPx() }, e)
            val corner = 30.dp + 2.dp * e
            Box(
                Modifier.offset { IntOffset(left.toInt(), top.toInt()) }
                    .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                    .clip(RoundedCornerShape(corner)).background(androidx.compose.ui.graphics.lerp(Ui.accent, MenuColor, e))
            ) {
                // заголовок листа с двойной спиралью
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 22.dp).graphicsLayer { alpha = ((e - 0.4f) / 0.6f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Helix(Modifier.width(30.dp).height(12.dp), done)
                    Spacer(Modifier.width(10.dp))
                    Text(when { done -> if (job == Job.PATCH) s(R.string.home_patched) else s(R.string.home_unpatched); job == Job.PATCH -> s(R.string.home_patching); else -> s(R.string.home_unpatching) },
                        style = t(18, c = Ui.text))
                }
            }
            // журнал «как текст песни» — внизу экрана
            Lyrics(log, done, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(maxHeight * 0.55f).padding(bottom = 80.dp)
                .graphicsLayer { alpha = ((e - 0.5f) / 0.5f).coerceIn(0f, 1f) })
        }

        if (credits) Credits(versionName) { credits = false }

        // затемнение в чёрное (перезапуск и конец патча)
        if (black.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = black.value)))
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** «Nothing OS 4.1 · CMF Phone 2 Pro» — из сборки (B4.1-…) и имени устройства. */
private fun systemLine(ctx: Context): String {
    val ver = Regex("""\d+(\.\d+)?""").find(android.os.Build.DISPLAY)?.value ?: android.os.Build.VERSION.RELEASE
    val name = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    return "Nothing OS $ver · $name"
}

// ───────── фон: живые пятна акцентных цветов системы ─────────

@Composable
private fun AccentBackground(energy: Float) {
    val ctx = LocalContext.current
    val cols = remember {
        listOf(android.R.color.system_accent1_400, android.R.color.system_accent3_400, android.R.color.system_accent2_300,
            android.R.color.system_accent1_600, android.R.color.system_accent3_200).map { Color(ctx.getColor(it)) }
    }
    val base = remember { Color(ctx.getColor(android.R.color.system_accent1_900)) }
    // кисти пятен — один раз, единичного радиуса в (0, 0): в кадре только сдвиг и масштаб, без новых градиентов
    val brushes = remember(cols) { cols.map { c -> Brush.radialGradient(listOf(c.copy(alpha = 0.75f), c.copy(alpha = 0f)), Offset.Zero, 1f) } }
    val speed by animateFloatAsState(1f + 2.2f * energy, tween(900, easing = FastOutSlowInEasing), label = "speed")
    var t by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { n -> if (last != 0L) t += (n - last) / 1e9 * speed; last = n }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val w = size.width; val h = size.height; val m = maxOf(w, h)
        cols.forEachIndexed { i, c ->
            val sp = 0.09 + 0.03 * i; val ph = i * 1.7
            val cx = (w * (0.5 + 0.45 * sin(t * sp + ph))).toFloat()
            val cy = (h * (0.5 + 0.42 * cos(t * sp * 1.3 + ph * 0.7))).toFloat()
            val r = (m * (0.48 + 0.08 * sin(t * 0.2 + i))).toFloat()
            withTransform({ translate(cx, cy); scale(r, r, Offset.Zero) }) { drawCircle(brushes[i], 1f, Offset.Zero) }
        }
        drawRect(Color.Black.copy(alpha = 0.28f))                  // читаемость белого текста
    }
}

// ───────── меню ─────────

/** Material Symbols «extension» (Apache-2.0). */
private val StorePath by lazy {
    androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M20.5,11H19V7c0,-1.1 -0.9,-2 -2,-2h-4V3.5C13,2.12 11.88,1 10.5,1S8,2.12 8,3.5V5H4c-1.1,0 -1.99,0.9 -1.99,2v3.8H3.5" +
            "c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 -2.7,2.7H2V20c0,1.1 0.9,2 2,2h3.8v-1.5c0,-1.49 1.21,-2.7 2.7,-2.7 1.49,0 2.7,1.21 2.7,2.7" +
            "V22H17c1.1,0 2,-0.9 2,-2v-4h1.5c1.38,0 2.5,-1.12 2.5,-2.5S21.88,11 20.5,11z"
    ).toPath()
}

private enum class Glyph { GEAR, STORE, RESTART, POWER, LOCK, INFO }

@Composable
private fun MenuRow(g: Glyph, title: String, chevron: Boolean, separator: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(g, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = t(16, FontWeight.Medium, Ui.text), modifier = Modifier.weight(1f))
            if (chevron) Canvas(Modifier.size(14.dp)) {
                val s = size.width; val st = 1.6.dp.toPx()
                drawLine(Ui.dim, Offset(s * 0.35f, s * 0.15f), Offset(s * 0.7f, s * 0.5f), st, StrokeCap.Round)
                drawLine(Ui.dim, Offset(s * 0.7f, s * 0.5f), Offset(s * 0.35f, s * 0.85f), st, StrokeCap.Round)
            }
        }
        if (separator) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Sep))
    }
}

@Composable
private fun GlyphIcon(g: Glyph, modifier: Modifier, open: Boolean = false, color: Color = Ui.text) {
    Canvas(modifier) { drawGlyph(g, color, open) }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color, open: Boolean) {
    val s = size.width; val st = Stroke(s * 0.1f, cap = StrokeCap.Round)
    when (g) {
        Glyph.GEAR -> {
            drawCircle(c, s * 0.18f, Offset(s / 2, s / 2), style = st)
            for (i in 0 until 8) {
                val a = Math.PI * i / 4
                drawLine(c, Offset((s / 2 + s * 0.28f * cos(a)).toFloat(), (s / 2 + s * 0.28f * sin(a)).toFloat()),
                    Offset((s / 2 + s * 0.44f * cos(a)).toFloat(), (s / 2 + s * 0.44f * sin(a)).toFloat()), s * 0.12f, StrokeCap.Round)
            }
        }
        Glyph.STORE -> {
            // кусочек пазла (твик) — контур того же символа, что на значке TweakStore; путь в сетке 24×24
            val k = s / 24f
            scale(k, k, Offset.Zero) {
                drawPath(StorePath, c, style = Stroke(2.1f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        Glyph.RESTART -> {
            // ↻: дуга по часовой от 1 часа до ~11:30 (разрыв сверху), на конце — залитый треугольник по касательной
            val r = s * 0.34f; val cx = s / 2; val cy = s / 2 + s * 0.03f
            drawArc(c, -60f, 305f, false, Offset(cx - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = st)
            val a = Math.toRadians(245.0)
            val ex = cx + r * cos(a).toFloat(); val ey = cy + r * sin(a).toFloat()
            val tx = -sin(a).toFloat(); val ty = cos(a).toFloat()          // касательная по часовой
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()           // нормаль наружу
            val l = s * 0.26f
            val head = androidx.compose.ui.graphics.Path().apply {
                moveTo(ex + tx * l * 0.55f, ey + ty * l * 0.55f)
                lineTo(ex - tx * l * 0.15f + nx * l * 0.42f, ey - ty * l * 0.15f + ny * l * 0.42f)
                lineTo(ex - tx * l * 0.15f - nx * l * 0.42f, ey - ty * l * 0.15f - ny * l * 0.42f)
                close()
            }
            drawPath(head, c)
        }
        Glyph.POWER -> {
            drawArc(c, -50f, 280f, false, Offset(s * 0.12f, s * 0.16f), androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.76f), style = st)
            drawLine(c, Offset(s / 2, s * 0.06f), Offset(s / 2, s * 0.46f), s * 0.1f, StrokeCap.Round)
        }
        Glyph.LOCK -> {
            drawRoundRect(c, Offset(s * 0.16f, s * 0.44f), androidx.compose.ui.geometry.Size(s * 0.68f, s * 0.5f),
                androidx.compose.ui.geometry.CornerRadius(s * 0.1f), style = st)
            val left = s * 0.3f; val right = s * 0.7f
            drawArc(c, 180f, 180f, false, Offset(left, s * 0.08f), androidx.compose.ui.geometry.Size(right - left, s * 0.4f), style = st)
            drawLine(c, Offset(left, s * 0.28f), Offset(left, s * 0.44f), s * 0.1f, StrokeCap.Round)
            if (!open) drawLine(c, Offset(right, s * 0.28f), Offset(right, s * 0.44f), s * 0.1f, StrokeCap.Round)
        }
        Glyph.INFO -> {
            drawCircle(c, s * 0.42f, Offset(s / 2, s / 2), style = st)
            drawLine(c, Offset(s / 2, s * 0.44f), Offset(s / 2, s * 0.72f), s * 0.1f, StrokeCap.Round)
            drawCircle(c, s * 0.06f, Offset(s / 2, s * 0.3f))
        }
    }
}

// ───────── двойная спираль (DODoubleHelixIndicator): 2 × 10 точек, волна с задержкой 0.1 с, период 2.3 с ─────────

@Composable
private fun Helix(modifier: Modifier, stop: Boolean) {
    var t by remember { mutableStateOf(0.0) }
    LaunchedEffect(stop) {
        var start = 0L
        while (!stop) withFrameNanos { n -> if (start == 0L) start = n; t = (n - start) / 1e9 }
    }
    Canvas(modifier) {
        val dot = 2.dp.toPx(); val gap = 3.dp.toPx(); val amp = 8.dp.toPx() / 2
        for (row in 0..1) for (i in 0 until 10) {
            val ph = (((t - 0.1 * i) % 2.3 + 2.3) % 2.3) / 2.3
            val k = (1 - cos(ph * 2 * Math.PI)) / 2                       // 0 → 1 → 0, ease in-out
            val dy = (if (row == 0) -1 else 1) * amp * k.toFloat()
            val y = size.height / 2 + (if (row == 0) amp else -amp) + dy
            drawCircle(Ui.accent.copy(alpha = if (row == 0) 0.7f else 1f), dot / 2, Offset(i * gap + dot / 2, y))
        }
    }
}

// ───────── журнал «как текст песни» (DOLyricsLogView) ─────────

@Composable
private fun Lyrics(log: List<String>, done: Boolean, modifier: Modifier) {
    Box(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            // верх растворяется (у Dopamine — прогрессивное размытие с маской)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
        }
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 25.dp), verticalArrangement = Arrangement.Bottom) {
            log.forEachIndexed { i, s ->
                val last = i == log.lastIndex
                LyricLine(s, current = last && !done, success = done, key = i)
            }
        }
    }
}

@Composable
private fun LyricLine(text: String, current: Boolean, success: Boolean, key: Int) {
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { appear.animateTo(1f, tween(300)) }
    val dim by animateFloatAsState(if (current) 0.9f else 0.5f, tween(200), label = "dim")
    val size by animateFloatAsState(if (current) 20f else 18f, tween(200), label = "size")
    Row(
        Modifier.height(40.dp).graphicsLayer {
            alpha = appear.value * (if (success) 0.9f else dim)
            translationY = 8.dp.toPx() * (1f - appear.value)
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            when {
                current -> Spinner(Modifier.size(20.dp))
                success -> GlyphIcon(Glyph.LOCK, Modifier.size(19.dp), open = true)
                else -> Canvas(Modifier.size(17.dp)) {
                    val s = this.size.width
                    drawLine(Ui.accent, Offset(s * 0.12f, s * 0.55f), Offset(s * 0.4f, s * 0.82f), s * 0.11f, StrokeCap.Round)
                    drawLine(Ui.accent, Offset(s * 0.4f, s * 0.82f), Offset(s * 0.9f, s * 0.2f), s * 0.11f, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(15.dp))
        Text(text, style = androidx.compose.ui.text.TextStyle(fontFamily = Ui.font, fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp), color = Ui.text), maxLines = 1)
    }
}

/** Крутилка (DOLoadingIndicator): дуга, один оборот в секунду. */
@Composable
private fun Spinner(modifier: Modifier) {
    var a by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { var s = 0L; while (true) withFrameNanos { n -> if (s == 0L) s = n; a = ((n - s) / 1e9f * 360f) % 360f } }
    Canvas(modifier) {
        drawArc(Ui.accent.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(size.width * 0.12f))
        drawArc(Ui.accent, a, 90f, false, style = Stroke(size.width * 0.12f, cap = StrokeCap.Round))
    }
}

// ───────── авторы ─────────

@Composable
private fun Credits(version: String, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onBack)) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 30.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Ui.raised).clickable(enabled = false) {}.padding(24.dp)
        ) {
            Text(s(R.string.credits_title, version), style = t(18, FontWeight.SemiBold, Ui.text))
            Spacer(Modifier.height(12.dp))
            listOf(
                "novaca1n" to s(R.string.credits_author),
                "Vector (LSPosed)" to s(R.string.credits_vector),
                "Dopamine — opa334, tomt000" to s(R.string.credits_dopamine),
                "crDroid Android" to s(R.string.credits_crdroid),
                "Box2D-lite — Erin Catto" to s(R.string.credits_box2d),
                "Inter · Outfit · Ndot — Nothing" to s(R.string.credits_fonts),
            ).forEachIndexed { i, (a, b) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Sep))
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(a, style = t(15, FontWeight.Medium, Ui.text))
                    Text(b, style = t(13, c = Ui.dim))
                }
            }
        }
    }
}
