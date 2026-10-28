package dev.novacain.ntclocks

import android.graphics.Matrix
import android.provider.Settings
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.PathInterpolator
import android.view.animation.Transformation
import android.view.animation.TranslateAnimation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.novacain.ntclocks.anim.AnimPrefs
import dev.novacain.ntclocks.anim.ShellAnims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val STYLES = listOf(AnimPrefs.NOTHING, AnimPrefs.IOS, AnimPrefs.COLOROS, AnimPrefs.COLOROS_FAST, AnimPrefs.COLOROS17, AnimPrefs.ORIGIN, AnimPrefs.CUSTOM)
private val IN_APP = listOf(AnimPrefs.IN_NATIVE, AnimPrefs.IOS, AnimPrefs.COLOROS, AnimPrefs.ORIGIN)
private val SYS_SCALES = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")

/** Примерное время, за которое пружина успокаивается (до 1 % от пути), — понятнее, чем «жёсткость 342». */
private fun settleMs(stiffness: Float, damping: Float): Int {
    val w = sqrt(stiffness)
    val z = damping.coerceIn(0.05f, 1.2f)
    val t = if (z < 1f) -ln(0.01f) / (z * w) else 6.6f / w      // у критического затухания хвост длиннее
    return (t * 1000).roundToInt()
}

@Composable
internal fun AnimTab() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf(AnimPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    var sys by remember { mutableFloatStateOf(runCatching { Settings.Global.getFloat(cr, SYS_SCALES[0]) }.getOrDefault(1f)) }
    var sysVersion by remember { mutableIntStateOf(0) }
    var restarted by remember { mutableStateOf(false) }

    /** Ручная правка пружин/стола превращает пресет в «Свои»; переходы внутри приложений — отдельная настройка. */
    fun update(launcher: Boolean, change: AnimPrefs.() -> Unit) {
        a = a.copy().apply(change).also { if (launcher) it.style = AnimPrefs.CUSTOM }
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = a
        Save.later("anim", 250) { runCatching { AnimPrefs.write(cr, saved) } }
    }
    LaunchedEffect(sysVersion) {
        if (sysVersion == 0) return@LaunchedEffect
        val v = sys
        Save.later("sys", 250) { runCatching { SYS_SCALES.forEach { Settings.Global.putFloat(cr, it, v) } } }
    }

    var sub by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SubTabs(listOf(s(R.string.anim_tab_style), s(R.string.anim_tab_fine)), sub) { sub = it }
        if (sub == 0) {
        Spacer(Modifier.height(10.dp))
        Demo(a, Modifier.align(Alignment.CenterHorizontally))
        Text(
            s(R.string.anim_preview_hint),
            style = t(12, c = Dim), modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)
        )

        Spacer(Modifier.height(16.dp))
        PresetGrid(a.style) { s ->
            a = if (s == AnimPrefs.CUSTOM) a.copy().apply { style = s } else AnimPrefs.preset(s).apply { inAppSpeed = a.inAppSpeed }
            version++
        }
        Text(
            s(R.string.anim_preset_hint),
            style = t(12, c = Dim), modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)
        )
        } else {

        Group(s(R.string.anim_open)) {
            SliderRow(s(R.string.anim_speed), a.openStiffness, 120f..1200f, { s(R.string.anim_settle_ms, settleMs(it, a.openDamping)) }) { v -> update(true) { openStiffness = v } }
            SliderRow(s(R.string.anim_bounce), 1f - a.openDamping, -0.2f..0.45f, { if (it < -0.005f) s(R.string.anim_no_bounce) else "${(it * 100).roundToInt()}%" }) { v -> update(true) { openDamping = 1f - v } }
        }
        Group(s(R.string.anim_stretch)) {
            SwitchRow(s(R.string.anim_stretch_edges), a.stretch) { v -> update(false) { stretch = v } }
            if (a.stretch) SwitchRow(s(R.string.anim_far_edge_first), a.stretchBottom) { v -> update(false) { stretchBottom = v } }
            Text(if (a.stretchBottom) s(R.string.anim_far_edge_hint)
                else s(R.string.anim_stretch_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            if (a.stretch) SwitchRow(s(R.string.anim_warp), a.warp) { v -> update(false) { warp = v } }
            SwitchRow(s(R.string.anim_icon_press), a.iconPress) { v -> update(false) { iconPress = v } }
        }
        Group(s(R.string.anim_close)) {
            SliderRow(s(R.string.anim_speed), a.closeStiffness, 120f..1200f, { s(R.string.anim_settle_ms, settleMs(it, a.closeDamping)) }) { v -> update(true) { closeStiffness = v } }
            SliderRow(s(R.string.anim_bounce), 1f - a.closeDamping, -0.2f..0.45f, { if (it < -0.005f) s(R.string.anim_no_bounce) else "${(it * 100).roundToInt()}%" }) { v -> update(true) { closeDamping = 1f - v } }
        }
        Group(s(R.string.anim_home)) {
            SliderRow(s(R.string.anim_home_scale), 1f - a.homeScale, 0f..0.2f, { "${(it * 100).roundToInt()}%" }) { v -> update(true) { homeScale = 1f - v } }
            SwitchRow(s(R.string.anim_depth_wallpaper), a.depth) { v -> update(true) { depth = v } }
            Text(
                s(R.string.anim_home_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            Button(if (restarted) s(R.string.anim_launcher_restarted) else s(R.string.anim_restart_launcher), primary = false) {
                scope.launch {
                    restarted = withContext(Dispatchers.IO) {
                        runCatching {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.nothing.launcher")).waitFor() == 0
                        }.getOrDefault(false)
                    }
                }
            }
        }
        Group(s(R.string.anim_home_drawer_shade)) {
            SliderRow(s(R.string.anim_home_blur), a.homeBlur, 0f..48f, { if (it < 1f) s(R.string.off) else "${it.roundToInt()} dp" }) { v -> update(false) { homeBlur = if (v < 1f) 0f else v } }
            SwitchRow(s(R.string.anim_icon_resize), a.iconResize) { v -> update(false) { iconResize = v } }
            Text(s(R.string.anim_icon_resize_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Label(s(R.string.anim_drawer_open))
            val drawers = listOf(AnimPrefs.NOTHING, AnimPrefs.ORIGIN, AnimPrefs.IOS)
            Segmented(listOf("Nothing", "OriginOS", "iOS"), drawers.indexOf(a.drawer).coerceAtLeast(0)) { i -> update(false) { drawer = drawers[i] } }
            SwitchRow(s(R.string.anim_split_shade), a.splitShade) { v -> update(false) { splitShade = v } }
            Text(s(R.string.anim_split_shade_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Label(s(R.string.anim_shade_open))
            val shades = listOf(AnimPrefs.NOTHING, AnimPrefs.ORIGIN, AnimPrefs.COLOROS, AnimPrefs.IOS)
            Segmented(listOf("Nothing", "OriginOS", "ColorOS", "iOS"), shades.indexOf(a.shade).coerceAtLeast(0)) { i -> update(false) { shade = shades[i] } }
            Text(s(R.string.anim_shade_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Label(s(R.string.anim_living))
            val motions = listOf(AnimPrefs.NOTHING, AnimPrefs.COLOROS17)
            Segmented(listOf("Nothing", "ColorOS 17"), motions.indexOf(a.sysMotion).coerceAtLeast(0)) { i -> update(false) { sysMotion = motions[i] } }
            Text(
                s(R.string.anim_living_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
            SwitchRow(s(R.string.anim_gravity), a.gravity) { v -> update(false) { gravity = v } }
            Text(
                s(R.string.anim_gravity_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
            Label(s(R.string.anim_volume))
            val volumes = listOf(AnimPrefs.NOTHING, AnimPrefs.COLOROS, AnimPrefs.IOS)
            Segmented(listOf("Nothing", "ColorOS", "iPhone"), volumes.indexOf(a.volume).coerceAtLeast(0)) { i -> update(false) { volume = volumes[i] } }
            Text(
                when (a.volume) {
                    AnimPrefs.IOS -> s(R.string.anim_volume_ios)
                    AnimPrefs.COLOROS -> s(R.string.anim_volume_coloros)
                    else -> s(R.string.anim_volume_nothing)
                },
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Group(s(R.string.anim_in_app)) {
            Text(s(R.string.anim_in_app_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Segmented(listOf(s(R.string.anim_system), "iOS 26", "ColorOS", "Origin"), IN_APP.indexOf(a.inApp).coerceAtLeast(0)) { i ->
                update(false) { inApp = IN_APP[i] }
            }
            if (a.inApp != AnimPrefs.IN_NATIVE) {
                SliderRow(s(R.string.anim_duration), a.inAppSpeed, 0.5f..2f, { "×%.2f".format(it) }) { v -> update(false) { inAppSpeed = v } }
            }
        }
        Group(s(R.string.anim_whole_system)) {
            SliderRow(s(R.string.anim_scale), sys, 0f..2f, { if (it < 0.05f) s(R.string.off) else "×%.2f".format(it) }) { v ->
                sys = (v * 20).roundToInt() / 20f
                sysVersion++
            }
            Text(s(R.string.anim_scale_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp))
        }
        if (!canApply(ctx)) {
            Text(
                s(R.string.anim_grant_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 12.dp)
            )
        }
        }
        Spacer(Modifier.height(110.dp))
    }
}

// ───────── живой макет: рабочий стол, окно из иконки, переходы внутри ─────────
// Макет считает движение так же, как лаунчер Nothing с модулем: полёт окна (X/Y) и размер окна (жёсткость ×1.11) —
// пружины с выбранной пружинистостью, размер может перелететь до +25 %. Переходы внутри приложения —
// те же самые объекты Animation, что модуль отдаёт SystemUI (ShellAnims), только применённые к макету.

private val ICONS = listOf(0xFFF0471E, 0xFF2F8CFF, 0xFF34C759, 0xFFFFC233, 0xFF8E5BFF, 0xFFFF4F9A, 0xFFE8E8E8, 0xFF3A3A3A)
    .map { Color(it) }

/** «Как в системе» для макета — примерно как стандартный переход Android 16 (сдвиг на 10 % и проявление). */
private fun nativeAnim(attr: Int): Animation {
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    fun slide(from: Float, to: Float) = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, from, Animation.RELATIVE_TO_SELF, to, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f
    ).apply { interpolator = ease }
    fun fade(from: Float, to: Float) = AlphaAnimation(from, to).apply { interpolator = ease }
    return AnimationSet(false).apply {
        when (attr) {
            4 -> { addAnimation(slide(0.1f, 0f)); addAnimation(fade(0f, 1f)) }
            5 -> addAnimation(slide(0f, -0.05f))
            6 -> addAnimation(slide(-0.05f, 0f))
            else -> { addAnimation(slide(0f, 0.1f)); addAnimation(fade(1f, 0f)) }
        }
        duration = 450
        fillAfter = true
        fillBefore = true
    }
}

/** Кадр перехода: масштаб/сдвиг/прозрачность слоя в момент t (0…1 по времени). */
private class Frame(val sx: Float = 1f, val sy: Float = 1f, val tx: Float = 0f, val ty: Float = 0f, val alpha: Float = 1f)

private fun frame(anim: Animation, w: Int, h: Int, t: Float): Frame {
    if (!anim.isInitialized) anim.initialize(w, h, w, h)
    anim.startTime = 0
    val tr = Transformation()
    anim.getTransformation((t * anim.computeDurationHint()).toLong().coerceAtLeast(0), tr)
    val m = FloatArray(9).also { tr.matrix.getValues(it) }
    return Frame(m[Matrix.MSCALE_X], m[Matrix.MSCALE_Y], m[Matrix.MTRANS_X], m[Matrix.MTRANS_Y], tr.alpha)
}

private fun Modifier.frame(f: Frame) = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = f.sx; scaleY = f.sy; translationX = f.tx; translationY = f.ty; alpha = f.alpha
}

@Composable
private fun Demo(a: AnimPrefs, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val prefs by rememberUpdatedState(a)
    val pos = remember { Animatable(0f) }           // полёт окна: 0 — у иконки, 1 — на месте
    val size = remember { Animatable(0f) }          // размер окна: 0 — с иконку, 1 — весь экран
    val nav = remember { Animatable(1f) }           // время текущего перехода внутри приложения, 0…1
    var pushed by remember { mutableStateOf(false) }
    var from by remember { mutableIntStateOf(5) }
    var opened by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    fun fly(to: Float, stiffness: Float, damping: Float) {
        scope.launch { pos.animateTo(to, spring(damping, stiffness, 0.0005f)) }
        scope.launch { size.animateTo(to, spring(damping, stiffness * 1.11f, 0.0005f)) }
    }
    fun launch(i: Int) {
        if (opened) return
        from = i
        opened = true
        pushed = false
        scope.launch { nav.snapTo(1f) }
        fly(1f, prefs.openStiffness, prefs.openDamping)
    }
    fun home() {
        if (!opened) return
        opened = false
        fly(0f, prefs.closeStiffness, prefs.closeDamping)
    }
    fun navigate() {
        pushed = !pushed
        val p = prefs
        val ms = ShellAnims.spring(p.inApp, false)?.let { (it.durationMs() * p.inAppSpeed).toInt() } ?: 450
        scope.launch {
            nav.snapTo(0f)
            nav.animateTo(1f, tween(ms, easing = LinearEasing))
        }
    }

    BoxWithConstraints(
        modifier.width(184.dp).height(398.dp).clip(RoundedCornerShape(32.dp)).background(Color.Black)
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pad = w * 0.08f
        val cell = (w - 2 * pad) / 4
        val icon = cell * 0.68f
        fun iconRect(i: Int): Rect {
            val c = i % 4
            val r = i / 4
            val cx = pad + cell * (c + 0.5f)
            val cy = if (r < 5) h * 0.13f + cell * 1.08f * (r + 0.5f) else h - pad - cell * 0.5f
            return Rect(Offset(cx - icon / 2, cy - icon / 2), Size(icon, icon))
        }
        val count = 24                                   // 5 рядов + док
        val pv = pos.value
        val sv = size.value.coerceAtMost(1.25f)          // перелёт размера — как в лаунчере с модулем (до +25 %)
        val svc = sv.coerceIn(0f, 1f)
        val visible = opened || size.value > 0.002f
        // обои: с «глубиной» отъезжают назад вместе со столом (в покое они чуть крупнее — как у Android)
        Canvas(Modifier.fillMaxSize().graphicsLayer { val z = if (prefs.depth && prefs.style != AnimPrefs.NOTHING) 1.08f - 0.07f * svc else 1f; scaleX = z; scaleY = z }) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2B2B33), Color(0xFF15151A), Color(0xFF0B0B0D))))
            drawCircle(Brush.radialGradient(listOf(Accent.copy(alpha = 0.55f), Color.Transparent), Offset(w * 0.8f, h * 0.18f), w * 0.7f), w * 0.7f, Offset(w * 0.8f, h * 0.18f))
        }
        // иконки: отдаляются и размываются
        Canvas(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val s = lerp(1f, if (prefs.style == AnimPrefs.NOTHING) 0.95f else prefs.homeScale, svc)
                    scaleX = s; scaleY = s; alpha = 1f - 0.5f * svc        // как у Nothing: стол темнеет до 50 %
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        (0 until count).firstOrNull { iconRect(it).inflate(cell * 0.15f).contains(p) }?.let { launch(it) }
                    }
                }
        ) {
            for (i in 0 until count) {
                if (i == from && visible) continue          // иконка «превратилась» в окно
                drawRoundRect(ICONS[i % ICONS.size], iconRect(i).topLeft, iconRect(i).size, CornerRadius(icon * 0.3f))
            }
        }
        // окно приложения: центр летит по своей пружине, размер — по своей
        if (visible) {
            val start = iconRect(from)
            val cx = lerp(start.center.x, w / 2, pv)
            val cy = lerp(start.center.y, h / 2, pv)
            val rw = lerp(start.width, w, sv)
            val rh = lerp(start.height, h, sv)
            val rect = Rect(Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh))
            val corner = with(density) { lerp(icon * 0.3f, 32.dp.toPx(), svc).toDp() }
            val tint = ICONS[from % ICONS.size]
            Box(
                Modifier.layout { m, _ ->
                    val pl = m.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                    layout(w.roundToInt(), h.roundToInt()) { pl.place(rect.left.roundToInt(), rect.top.roundToInt()) }
                }.clip(RoundedCornerShape(corner)).background(lerp(tint, Color(0xFF161616), (svc * 1.6f).coerceAtMost(1f)))
            ) {
                AppContent(tint, prefs, nav.value, pushed, Modifier.graphicsLayer { alpha = ((svc - 0.25f) / 0.45f).coerceIn(0f, 1f) }, ::navigate)
                // полоска «домой»
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp).clickable(onClick = ::home),
                    contentAlignment = Alignment.Center
                ) { Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.7f))) }
            }
        }
    }
}

/**
 * Два экрана выдуманного приложения. Переход — настоящие анимации модуля: при открытии нижний слой — старый экран
 * (тип 5), верхний — новый (4); при возврате нижний — предыдущий (6), верхний — закрывающийся (7).
 */
@Composable
private fun AppContent(tint: Color, a: AnimPrefs, t: Float, pushed: Boolean, modifier: Modifier, onRow: () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val anims = remember(a.inApp, a.inAppSpeed, w, h) {
            (4..7).associateWith { ShellAnims.create(a.inApp, it, a.inAppSpeed) ?: nativeAnim(it) }
        }
        val running = t < 1f
        if (pushed) {
            Screen(tint, s(R.string.anim_demo_main), if (running) Modifier.frame(frame(anims.getValue(5), w, h, t)) else Modifier.graphicsLayer { alpha = 0f }, onRow)
            Screen(tint, s(R.string.anim_demo_detail), Modifier.frame(frame(anims.getValue(4), w, h, t)), onRow)
        } else {
            Screen(tint, s(R.string.anim_demo_main), if (running) Modifier.frame(frame(anims.getValue(6), w, h, t)) else Modifier, onRow)
            if (running) Screen(tint, s(R.string.anim_demo_detail), Modifier.frame(frame(anims.getValue(7), w, h, t)), onRow)
        }
    }
}

@Composable
private fun Screen(tint: Color, title: String, modifier: Modifier, onRow: () -> Unit) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF161616)).padding(horizontal = 12.dp).padding(top = 30.dp)) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(tint))
        Text(title, style = t(17, FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        repeat(5) { i ->
            Box(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).height(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF242424)).clickable(onClick = onRow).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) { Box(Modifier.width((50 + i * 13 % 40).dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF4A4A4A))) }
        }
    }
}


/** Пресеты анимаций: название и характер в одну строку; выбранный — с акцентной рамкой. */
private val PRESET_INFO get() = listOf(
    Triple(AnimPrefs.NOTHING, "Nothing", s(R.string.preset_nothing)),
    Triple(AnimPrefs.IOS, "iOS 26", s(R.string.preset_ios)),
    Triple(AnimPrefs.COLOROS, "ColorOS", s(R.string.preset_coloros)),
    Triple(AnimPrefs.COLOROS_FAST, s(R.string.preset_coloros_fast_name), s(R.string.preset_coloros_fast)),
    Triple(AnimPrefs.COLOROS17, "ColorOS 17", s(R.string.preset_coloros17)),
    Triple(AnimPrefs.ORIGIN, "OriginOS 17", s(R.string.preset_origin)),
    Triple(AnimPrefs.CUSTOM, s(R.string.preset_custom_name), s(R.string.preset_custom)),
)

@Composable
private fun PresetGrid(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        PRESET_INFO.chunked(2).forEach { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, title, hint) ->
                    val on = id == selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) Surface2 else Surface)
                            .then(if (on) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier)
                            .clickable { onPick(id) }.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(title, style = t(15, FontWeight.Medium, if (on) Color.White else Color(0xFFD8D8D8)))
                        Text(hint, style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
