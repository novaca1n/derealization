package dev.novacain.ntclocks

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Своя маска эффекта глубины: любое фото → обои экрана блокировки, объект с которого заходит на часы.
 * Фото подгоняется под экран (щипок, сдвиг), объект выделяет ML Kit (тот же, что у «Обоев и стиля»), маска
 * поправляется пальцем. «Поставить» делает то же, что сами «Обои и стиль»: обои экрана блокировки, вырезку объекта
 * (WallpaperManager.setDepthEffectWallpaperWithWallpaperType — добавка Nothing; ставит её SystemUI, см. systemUi)
 * и depth_effect_state = 1.
 * Всё в размер экрана: SystemUI кладёт вырезку в угол (0, 0) без сдвигов — совпадение с обоями точное.
 */
class DepthMaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.picker(this)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val b = windowManager.currentWindowMetrics.bounds
        // фото можно передать сразу (data) — тогда без выбора
        setContent { MaskEditor(b.width(), b.height(), intent.data) { finish() } }
    }
}

private enum class Step { PICK, PLACE, MASK, DONE }

/**
 * Установка обоев идёт вне экрана: новые обои перекрашивают систему (Material You), Android от этого пересоздаёт
 * экран — установка не должна прерваться, а новый экран должен показать её итог.
 */
internal object DepthApply {
    var running by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun start(ctx: android.content.Context, wall: Bitmap, mask: Bitmap) {
        if (running) return
        running = true; done = false; error = null
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            val err = apply(app, wall, mask)
            withContext(Dispatchers.Main) { error = err; done = err == null; running = false }
        }
    }

    /** Убрать свою маску: вырезки нет, экран блокировки снова с обоями рабочего стола. null — успех. */
    fun remove(ctx: android.content.Context): String? = try {
        systemUi(ctx, "clear") ?: run { WallpaperManager.getInstance(ctx).clear(WallpaperManager.FLAG_LOCK); null }
    } catch (t: Throwable) {
        android.util.Log.e("derealization", "remove mask", t)
        s(R.string.mask_failed, (t.cause ?: t).javaClass.simpleName)
    }
}

/**
 * Заявка SystemUI (DepthHooks.listenCutout): вырезку системе ставит он — файл вырезки Nothing закрыт SELinux для
 * обычных приложений. Ждём ответ до 15 с. null — успех, иначе текст ошибки.
 */
private fun systemUi(ctx: android.content.Context, what: String): String? {
    val cr = ctx.contentResolver
    val tag = System.nanoTime().toString()
    Settings.Global.putString(cr, dev.novacain.ntclocks.anim.DepthHooks.CUT_DONE, null)
    Settings.Global.putString(cr, dev.novacain.ntclocks.anim.DepthHooks.CUT_REQUEST, "$what:$tag")
    val until = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < until) {
        Thread.sleep(150)
        val done = Settings.Global.getString(cr, dev.novacain.ntclocks.anim.DepthHooks.CUT_DONE) ?: continue
        if (!done.startsWith("$tag:")) continue
        val res = done.substringAfter(':')
        return if (res == "ok") null else "SystemUI: $res"
    }
    Settings.Global.putString(cr, dev.novacain.ntclocks.anim.DepthHooks.CUT_REQUEST, null)
    return s(R.string.mask_no_systemui)
}

@Composable
private fun MaskEditor(sw: Int, sh: Int, start: Uri?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.PICK) }
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var wall by remember { mutableStateOf<Bitmap?>(null) }      // видимая часть — в размер экрана
    var big by remember { mutableStateOf<Bitmap?>(null) }       // обои целиком, с полями
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<ClockShot?>(null) }
    LaunchedEffect(Unit) { shot = withContext(Dispatchers.IO) { loadShot(ctx) } }
    var note by remember { mutableStateOf<String?>(null) }

    var pickAgain by remember { mutableStateOf(false) }
    fun open(uri: Uri) {
        scope.launch {
            busy = s(R.string.mask_opening)
            src = withContext(Dispatchers.IO) { runCatching { decode(ctx, uri, max(sw, sh) * 2) }.getOrNull() }
            busy = null
            if (src != null) step = Step.PLACE else { note = s(R.string.mask_open_failed); pickAgain = true }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) open(uri) else if (src == null) onClose()
    }
    LaunchedEffect(Unit) {
        if (DepthApply.running || DepthApply.done) return@LaunchedEffect      // экран пересоздан во время установки
        if (start != null) open(start) else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    // переданное фото не открылось — обычный выбор
    LaunchedEffect(pickAgain) {
        if (pickAgain && src == null) { pickAgain = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    androidx.activity.compose.BackHandler {
        when (step) {
            Step.MASK -> step = Step.PLACE
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (step) {
            Step.PICK -> {}
            Step.PLACE -> Place(src!!, sw, sh, shot, onBack = onClose) { m ->
                scope.launch {
                    busy = s(R.string.mask_detecting)
                    val b = withContext(Dispatchers.Default) { render(src!!, m, sw, sh) }
                    big = b
                    val w = Bitmap.createBitmap(b, margin(sw), margin(sh), sw, sh)
                    wall = w
                    val auto = segment(ctx, w)
                    mask = auto ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
                    note = if (auto == null) s(R.string.mask_auto_unavailable_draw) else null
                    busy = null
                    step = Step.MASK
                }
            }
            Step.MASK -> MaskPaint(wall!!, mask!!, shot, onBack = { step = Step.PLACE },
                onAuto = {
                    scope.launch {
                        busy = s(R.string.mask_detecting)
                        val auto = segment(ctx, wall!!)
                        if (auto != null) mask = auto else note = s(R.string.mask_auto_unavailable)
                        busy = null
                    }
                },
                onApply = { DepthApply.start(ctx, big!!, mask!!) })
            Step.DONE -> Done(onClose)
        }
        // итог установки (в том числе в пересозданном экране)
        LaunchedEffect(DepthApply.done, DepthApply.error) {
            if (DepthApply.done) { DepthApply.done = false; step = Step.DONE }
            DepthApply.error?.let { note = it; DepthApply.error = null; if (wall == null) onClose() }
        }
        if (DepthApply.running) Busy(s(R.string.mask_applying))
        busy?.let { Busy(it) }
        note?.let { n ->
            LaunchedEffect(n) { kotlinx.coroutines.delay(3500); note = null }
            Text(n, style = t(14, c = Ui.text), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
                .clip(RoundedCornerShape(50)).background(Ui.surface).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

/** Шаг 1: фото под экран. Щипок — масштаб, палец — сдвиг; фото всегда закрывает экран целиком. */
@Composable
private fun Place(src: Bitmap, sw: Int, sh: Int, shot: ClockShot?, onBack: () -> Unit, onNext: (Matrix) -> Unit) {
    val img = remember(src) { src.asImageBitmap() }
    var view by remember { mutableStateOf(IntSize(sw, sh)) }
    // масштаб и сдвиг — в пикселях экрана; минимум — фото закрывает экран с полями (см. margin)
    val mx = margin(sw); val my = margin(sh)
    val minScale = max((sw + 2 * mx) / src.width.toFloat(), (sh + 2 * my) / src.height.toFloat())
    var scale by remember { mutableFloatStateOf(minScale) }
    var off by remember { mutableStateOf(Offset((sw - src.width * minScale) / 2, (sh - src.height * minScale) / 2)) }
    fun clampOff(o: Offset, s: Float) = Offset(
        o.x.coerceIn(sw + mx - src.width * s, -mx.toFloat()), o.y.coerceIn(sh + my - src.height * s, -my.toFloat())
    )
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            view = size
            awaitEachGesture {
                awaitFirstDown()
                do {
                    val ev = awaitPointerEvent()
                    val k = sw / size.width.toFloat()
                    val z = ev.calculateZoom()
                    val c = ev.calculateCentroid() * k
                    val ns = (scale * z).coerceIn(minScale, minScale * 6f)
                    val f = ns / scale
                    off = clampOff(Offset(c.x - (c.x - off.x) * f, c.y - (c.y - off.y) * f) + ev.calculatePan() * k, ns)
                    scale = ns
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
            }
        }) {
            val k = size.width / sw
            drawImage(img, dstOffset = IntOffset((off.x * k).toInt(), (off.y * k).toInt()),
                dstSize = IntSize((src.width * scale * k).toInt(), (src.height * scale * k).toInt()))
            clock(shot, k, sh * k, 0.6f)
        }
        TopBar(s(R.string.mask_place), if (shot != null) s(R.string.mask_clock_real) else s(R.string.mask_clock_approx), onBack)
        BottomBar {
            Pill(s(R.string.action_next), primary = true, Modifier.fillMaxWidth()) {
                onNext(Matrix().apply { setScale(scale, scale); postTranslate(off.x, off.y) })
            }
        }
    }
}

/**
 * Шаг 2: маска. Один палец — кисть (добавить / стереть), два — рассмотреть ближе. Вне объекта картинка затемнена,
 * объект подсвечен акцентом. «Предпросмотр» — как будет на экране блокировки: обои, часы, объект поверх.
 */
@Composable
private fun MaskPaint(wall: Bitmap, mask: Bitmap, shot: ClockShot?, onBack: () -> Unit, onAuto: () -> Unit, onApply: () -> Unit) {
    val cr = LocalContext.current.contentResolver
    // «подрезать по часам» — та же настройка, что в разделе «Глубина»: здесь её видно вживую
    var depth by remember { mutableStateOf(dev.novacain.ntclocks.anim.DepthPrefs.read(cr)) }
    fun updateDepth(change: dev.novacain.ntclocks.anim.DepthPrefs.() -> Unit) {
        depth = depth.copy().apply(change)
        val saved = depth
        Save.later("depth", 200) { runCatching { dev.novacain.ntclocks.anim.DepthPrefs.write(cr, saved) } }
    }
    val clip = if (shot != null && depth.enabled) shot.rect to depth.cover else null
    val sw = wall.width; val sh = wall.height
    val wallImg = remember(wall) { wall.asImageBitmap() }
    val maskImg = remember(mask) { mask.asImageBitmap() }
    var gen by remember { mutableIntStateOf(0) }          // маска изменилась — перерисовать
    var erase by remember { mutableStateOf(false) }
    var brush by remember { mutableFloatStateOf(0.06f) }   // доля ширины экрана
    var preview by remember { mutableStateOf(false) }
    var vs by remember { mutableFloatStateOf(1f) }
    var vo by remember { mutableStateOf(Offset.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val undo = remember(mask) { ArrayDeque<Bitmap>() }
    val canvas = remember(mask) { android.graphics.Canvas(mask) }
    val accent = Ui.accent

    fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = brush * sw / vs
        maskFilter = BlurMaskFilter(strokeWidth * 0.12f + 1f, BlurMaskFilter.Blur.NORMAL)
        if (erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) else color = android.graphics.Color.BLACK
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().pointerInput(mask) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val k = sw / size.width.toFloat()
                fun toMask(p: Offset) = (p * k - vo) / vs
                var mode = 0                                  // 0 — ещё не ясно, 1 — кисть, 2 — лупа
                var last = toMask(down.position)
                val p = paint()
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        if (mode == 1 && undo.isNotEmpty()) { canvas.drawBitmap(undo.removeLast(), 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }); gen++ }
                        mode = 2; cursor = null
                        val z = ev.calculateZoom(); val c = ev.calculateCentroid() * k
                        val ns = (vs * z).coerceIn(1f, 6f); val f = ns / vs
                        vo = Offset(c.x - (c.x - vo.x) * f, c.y - (c.y - vo.y) * f) + ev.calculatePan() * k
                        vo = Offset(vo.x.coerceIn(sw - sw * ns, 0f), vo.y.coerceIn(sh - sh * ns, 0f))
                        vs = ns
                    } else if (mode != 2 && !preview) {
                        val ch = ev.changes.first()
                        if (mode == 0) {
                            mode = 1
                            undo.addLast(mask.copy(Bitmap.Config.ALPHA_8, false)); if (undo.size > 12) undo.removeFirst()
                        }
                        val now = toMask(ch.position)
                        canvas.drawLine(last.x, last.y, now.x, now.y, p)
                        last = now; cursor = ch.position; gen++
                    }
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
                if (mode == 0 && !preview) {                       // касание без движения — точка
                    undo.addLast(mask.copy(Bitmap.Config.ALPHA_8, false))
                    canvas.drawPoint(last.x, last.y, p); gen++
                }
                cursor = null
            }
        }) {
            gen.let { }                                           // читаем — перерисовка при каждом мазке
            val k = size.width / sw
            withTransform({ translate(vo.x * k, vo.y * k); scale(vs, vs, Offset.Zero) }) {
                val full = IntSize(size.width.toInt(), (sh * k).toInt())
                if (preview) {
                    drawImage(wallImg, dstSize = full)
                    clock(shot, k, sh * k, 1f)
                    cutout(wallImg, maskImg, full, clip, k)
                } else {
                    drawImage(wallImg, dstSize = full)
                    // затемнение вне объекта: тёмный слой с вырезанной маской
                    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(full.width.toFloat(), full.height.toFloat())), androidx.compose.ui.graphics.Paint())
                    drawRect(Color.Black.copy(alpha = 0.62f), size = Size(full.width.toFloat(), full.height.toFloat()))
                    drawImage(maskImg, dstSize = full, blendMode = BlendMode.DstOut)
                    drawContext.canvas.restore()
                    drawImage(maskImg, dstSize = full, colorFilter = ColorFilter.tint(accent.copy(alpha = 0.28f), BlendMode.SrcIn))
                    clock(shot, k, sh * k, 0.55f)
                    clip?.let { (r, cover) ->
                        // линия обрезки: выше неё объект на часы не заходит
                        val y = (r.top + r.height() * (1f - cover)) * k
                        drawLine(accent, Offset(r.left * k, y), Offset(r.right * k, y), 2.dp.toPx() / vs,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx() / vs, 8.dp.toPx() / vs)))
                    }
                }
            }
            cursor?.let { drawCircle(Color.White, brush * size.width / 2, it, style = Stroke(1.5.dp.toPx())) }
        }
        TopBar(if (preview) s(R.string.mask_preview) else s(R.string.mask_trace), if (preview) s(R.string.mask_preview_hint) else s(R.string.mask_zoom_hint), onBack)
        BottomBar {
            if (!preview) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(s(R.string.mask_add), primary = !erase, Modifier.weight(1f)) { erase = false }
                    Pill(s(R.string.mask_erase), primary = erase, Modifier.weight(1f)) { erase = true }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(s(R.string.mask_brush), style = t(13, c = Dim), modifier = Modifier.padding(end = 12.dp))
                    Box(Modifier.weight(1f)) { ThinSlider(brush, 0.015f..0.2f) { brush = it } }
                }
            }
            if (preview && shot != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(s(R.string.mask_cut_clock), style = t(14), modifier = Modifier.weight(1f))
                    Toggle(depth.enabled) { v -> updateDepth { enabled = v } }
                }
                if (depth.enabled) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(s(R.string.mask_overlap), style = t(13, c = Dim), modifier = Modifier.padding(end = 12.dp))
                    Box(Modifier.weight(1f)) { ThinSlider(depth.cover, 0f..0.8f) { v -> updateDepth { cover = v } } }
                    Text("${(depth.cover * 100).toInt()}%", style = t(13, c = Dim), modifier = Modifier.padding(start = 10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!preview) {
                    Pill(s(R.string.action_undo), primary = false, Modifier.weight(1f)) {
                        if (undo.isNotEmpty()) { canvas.drawBitmap(undo.removeLast(), 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }); gen++ }
                    }
                    Pill(s(R.string.mask_auto), primary = false, Modifier.weight(1f)) { onAuto() }
                }
                Pill(if (preview) s(R.string.action_edit) else s(R.string.action_preview), primary = false, Modifier.weight(1f)) { preview = !preview; vs = 1f; vo = Offset.Zero }
                if (preview) Pill(s(R.string.action_apply), primary = true, Modifier.weight(1f)) { onApply() }
            }
        }
    }
}

/**
 * Объект поверх: обои, оставленные только там, где маска. clip — обрезка по часам, как в SystemUI
 * (DepthHooks.clip): над линией «верх + (1 − cover) высоты» объект стёрт, край мягкий.
 */
private fun DrawScope.cutout(wall: androidx.compose.ui.graphics.ImageBitmap, mask: androidx.compose.ui.graphics.ImageBitmap, full: IntSize,
                             clip: Pair<android.graphics.Rect, Float>?, k: Float) {
    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(full.width.toFloat(), full.height.toFloat())), androidx.compose.ui.graphics.Paint())
    drawImage(wall, dstSize = full)
    drawImage(mask, dstSize = full, blendMode = BlendMode.DstIn)
    clip?.let { (r, cover) ->
        val top = r.top * k; val h = r.height() * k
        val line = top + h * (1f - cover)
        val feather = max(8f * k, h * 0.08f)
        val from = kotlin.math.min(top, line - feather)
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = line - feather, endY = line),
            topLeft = Offset(r.left * k, from), size = Size(r.width() * k, line - from), blendMode = BlendMode.DstOut)
    }
    drawContext.canvas.restore()
}

/** Снимок настоящих часов экрана блокировки (DepthHooks.clockShot): картинка и место на экране в пикселях. */
private class ClockShot(val img: androidx.compose.ui.graphics.ImageBitmap, val rect: android.graphics.Rect)

/** Попросить SystemUI снять часы; null — патч не работает или часов нет (тогда ориентир «примерно»). */
private fun loadShot(ctx: android.content.Context): ClockShot? = runCatching {
    if (systemUi(ctx, "shot") != null) return null
    val r = ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getString("clockShotRect", null)
        ?.split(',')?.map { it.toInt() } ?: return null
    val b = android.graphics.BitmapFactory.decodeFile(java.io.File(ctx.filesDir, "clock_shot.png").path) ?: return null
    ClockShot(b.asImageBitmap(), android.graphics.Rect(r[0], r[1], r[2], r[3]))
}.getOrNull()

/** Часы на своём месте (k — пикселей холста на пиксель экрана); без снимка — примерный ориентир. */
private fun DrawScope.clock(shot: ClockShot?, k: Float, h: Float, alpha: Float) {
    if (shot == null) { clockGhost(h, Color.White.copy(alpha = alpha), outline = alpha < 1f); return }
    val r = shot.rect
    drawImage(shot.img, dstOffset = IntOffset((r.left * k).toInt(), (r.top * k).toInt()),
        dstSize = IntSize((r.width() * k).toInt(), (r.height() * k).toInt()), alpha = alpha)
}

/** Ориентир «часы примерно здесь»: крупное время в верхней части экрана, как большие часы экрана блокировки. */
private fun DrawScope.clockGhost(h: Float, color: Color, outline: Boolean) {
    val text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false)
        textSize = h * 0.115f
        if (outline) { style = Paint.Style.STROKE; strokeWidth = h * 0.0015f + 1f }
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2, h * 0.245f, p)
}

@Composable
private fun TopBar(title: String, sub: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // стрелка назад — как в шапке настроек
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Ui.surface.copy(alpha = 0.85f)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val w = size.width; val s = w * 0.09f
                drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), s, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.2f), s, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.8f), s, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Column(Modifier.padding(start = 10.dp).clip(RoundedCornerShape(22.dp)).background(Ui.surface.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = t(15, FontWeight.Medium))
            Text(sub, style = t(12, c = Dim))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomBar(content: @Composable () -> Unit) {
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
            .clip(RoundedCornerShape(30.dp)).background(Ui.surface.copy(alpha = 0.92f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (primary) Ui.selected else Ui.surface2).clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = t(14, FontWeight.Medium, if (primary) Ui.onSelected else Ui.text)) }
}

@Composable
private fun Busy(text: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown().consume() }
    }, contentAlignment = Alignment.Center) {
        Text(text, style = t(15, FontWeight.Medium), modifier = Modifier.clip(RoundedCornerShape(50)).background(Ui.surface)
            .padding(horizontal = 22.dp, vertical = 12.dp))
    }
}

@Composable
private fun Done(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ui.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(s(R.string.done), style = t(26))
            Text(s(R.string.mask_done_text),
                style = t(14, c = Dim).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), modifier = Modifier.padding(top = 10.dp, bottom = 28.dp))
            Pill(s(R.string.action_close), primary = true, Modifier.fillMaxWidth()) { onClose() }
        }
    }
}

/** Фото с уменьшением до ~limit по большей стороне (выбор обоев — не повод держать 50 Мп в памяти). */
private fun decode(ctx: android.content.Context, uri: Uri, limit: Int): Bitmap {
    val source = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { d, info, _ ->
        val big = max(info.size.width, info.size.height)
        if (big > limit) d.setTargetSampleSize(big / limit)
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/**
 * Поля обоев: Nothing держит обои экрана блокировки на 10 % больше экрана (по 5 % с каждой стороны) — ImageWallpaper
 * растягивает картинку до такого размера, а SystemUI сдвигает вырезку на эти 5 %
 * (NTDepthEffectWallpaperController.getDepthEffectWallpaperPosition). Обои ровно в экран вышли бы крупнее вырезки.
 */
private fun margin(side: Int) = (side * 0.05).toInt()

/** Обои с полями: видимая часть (экран) — в середине. */
private fun render(src: Bitmap, m: Matrix, sw: Int, sh: Int): Bitmap =
    Bitmap.createBitmap(sw + 2 * margin(sw), sh + 2 * margin(sh), Bitmap.Config.ARGB_8888).also {
        val mm = Matrix(m).apply { postTranslate(margin(sw).toFloat(), margin(sh).toFloat()) }
        android.graphics.Canvas(it).drawBitmap(src, mm, Paint(Paint.FILTER_BITMAP_FLAG))
    }

/** Маска объекта от ML Kit (уверенность 0…1 → прозрачность), null — модуль недоступен. */
private suspend fun segment(ctx: android.content.Context, wall: Bitmap): Bitmap? {
    val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    return try {
        val res = suspendCancellableCoroutine<com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult?> { c ->
            seg.process(InputImage.fromBitmap(wall, 0))
                .addOnSuccessListener { c.resume(it) }
                .addOnFailureListener { c.resume(null) }
        } ?: return null
        val conf = res.foregroundConfidenceMask ?: return null
        withContext(Dispatchers.Default) {
            val n = wall.width * wall.height
            val px = IntArray(n)
            conf.rewind()
            for (i in 0 until n) {
                // мягкий порог: 0,35…0,65 → плавный край, ниже — фон, выше — объект
                val a = ((conf.get() - 0.35f) / 0.3f).coerceIn(0f, 1f)
                px[i] = (a * 255).toInt() shl 24
            }
            val raw = Bitmap.createBitmap(px, wall.width, wall.height, Bitmap.Config.ARGB_8888)
            // маска ML Kit грубее экрана — край «лесенкой»; лёгкое размытие сглаживает его
            Bitmap.createBitmap(wall.width, wall.height, Bitmap.Config.ALPHA_8).also { out ->
                val r = wall.width / 220f
                android.graphics.Canvas(out).drawBitmap(raw.extractAlpha(), 0f, 0f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) })
                raw.recycle()
            }
        }
    } catch (t: Throwable) {
        null
    } finally {
        seg.close()
    }
}

/**
 * Как «Обои и стиль»: обои экрана блокировки (с полями), затем вырезка того же размера под их id, затем
 * depth_effect_state. Маска нарисована по видимой части — в полях вырезка прозрачная. null — успех.
 */
private fun apply(ctx: android.content.Context, wall: Bitmap, mask: Bitmap): String? = try {
    // вырезка = пиксели обоев с прозрачностью из маски (по пикселям: DST_IN с ALPHA_8 на Canvas давал сплошную заливку)
    val w = wall.width; val h = wall.height
    val mw = mask.width; val mh = mask.height
    val ox = (w - mw) / 2; val oy = (h - mh) / 2
    val px = IntArray(w * h).also { wall.getPixels(it, 0, w, 0, 0, w, h) }
    val alpha = java.nio.ByteBuffer.allocate(mask.rowBytes * mh).also { mask.copyPixelsToBuffer(it) }.array()
    for (y in 0 until h) {
        val my = y - oy
        for (x in 0 until w) {
            val mx = x - ox
            val a = if (my in 0 until mh && mx in 0 until mw) alpha[my * mask.rowBytes + mx].toInt() and 0xFF else 0
            val i = y * w + x
            px[i] = (px[i] and 0xFFFFFF) or (a shl 24)
        }
    }
    val cut = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    java.io.File(ctx.filesDir, "depth_cutout.png").outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
    cut.recycle()
    val id = WallpaperManager.getInstance(ctx).setBitmap(wall, null, true, WallpaperManager.FLAG_LOCK)
    if (id == 0) s(R.string.mask_wallpaper_rejected) else systemUi(ctx, id.toString())
} catch (t: Throwable) {
    android.util.Log.e("derealization", "custom mask", t)
    s(R.string.mask_failed, (t.cause ?: t).javaClass.simpleName)
}
