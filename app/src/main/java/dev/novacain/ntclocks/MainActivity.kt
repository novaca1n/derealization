package dev.novacain.ntclocks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.PaddingValues
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.novacain.ntclocks.faces.ClockFace
import dev.novacain.ntclocks.faces.ClockPrefs
import dev.novacain.ntclocks.faces.Clocks
import dev.novacain.ntclocks.faces.Fonts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// Стекло в духе Dopamine: поверхности — полупрозрачный белый поверх живого фона, кромка — светлая линия
// (цвета — из Ui: у патчера стекло Dopamine, у настроек в «Обоях и стиле» — палитра самих «Обоев»)
internal val Bg: Color get() = Ui.bg                 // «вдавленное» внутри карточки
internal val Surface: Color get() = Ui.surface       // карточка
internal val Surface2: Color get() = Ui.surface2     // выбранное / приподнятое
internal val Line: Color get() = Ui.line             // кромка
internal val Dim: Color get() = Ui.dim
internal val Accent: Color get() = Ui.accent
internal val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

internal fun t(size: Int, w: FontWeight = FontWeight.Normal, c: Color = Ui.text) =
    TextStyle(fontFamily = Ui.font ?: Geist, fontWeight = w, fontSize = size.sp, color = c)

/** Шрифты Nothing прямо из системы телефона (Ndot — точечный для заголовка, Lettera Mono — подписи); нет — Geist. */
private fun systemFont(name: String): FontFamily? = runCatching {
    val f = java.io.File("/system/fonts/$name")
    if (f.exists()) FontFamily(android.graphics.Typeface.createFromFile(f)) else null
}.getOrNull()

internal val Dot: FontFamily by lazy { systemFont("Ndot-57.otf") ?: Geist }
internal val Mono: FontFamily by lazy { systemFont("LetteraMonoLL-Regular.otf") ?: Geist }

/** Подпись в духе Nothing: моноширинный шрифт, строчные буквы в верхнем регистре, разрядка. */
internal fun mono(size: Int = 12, c: Color = Dim, @Suppress("UNUSED_PARAMETER") spacing: Float = 0f) =
    TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = size.sp, color = c)

private const val KEY_FACE = "lock_screen_custom_clock_face"
private val PALETTE = listOf(0xFFF0471E, 0xFFFFFFFF, 0xFFFF3B30, 0xFFFFC233, 0xFF34C759, 0xFF2F8CFF, 0xFF8E5BFF, 0xFFFF4F9A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DereTheme(dark = true) { App() } }
    }

    override fun onPause() {
        Save.flush()
        super.onPause()
    }
}

/** Какие часы сейчас стоят на экране блокировки (id из Settings.Secure). */
private fun currentClock(ctx: Context): String? = try {
    Settings.Secure.getString(ctx.contentResolver, KEY_FACE)?.let { JSONObject(it).optString("clockId") }
} catch (_: Exception) { null }

internal fun canApply(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

/** Поставить часы: формат как у самой Nothing ({"clockId":…,"metadata":{"appliedTimestamp":…},"axes":[]}). */
private fun applyClock(ctx: Context, id: String) {
    val prev = currentClock(ctx)
    if (prev != null && !Clocks.isOurs(prev)) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("nothingClock", prev).apply()
    }
    val json = JSONObject().put("clockId", id)
        .put("metadata", JSONObject().put("appliedTimestamp", System.currentTimeMillis()))
        .put("axes", org.json.JSONArray())
    Settings.Secure.putString(ctx.contentResolver, KEY_FACE, json.toString())
}

private fun restoreNothing(ctx: Context) {
    val prev = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("nothingClock", null) ?: "NTYPE_16B"
    applyClock(ctx, prev)
}

/**
 * derealization: главная в духе Dopamine — статус и одна большая кнопка «Патч». Своих настроек в приложении нет:
 * они внутри «Обоев и стиля» Nothing (PickerHooks + SettingsActivity), приложение — только патчер.
 */
@Composable
private fun App() {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        DereHome()
        // «Добро пожаловать» / «Обновлено» — один раз после установки или обновления
        var news by remember { mutableStateOf(WhatsNewState.pending(ctx)) }
        androidx.compose.animation.AnimatedVisibility(news != null,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(320)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(320), targetScale = 1.04f)) {
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            WhatsNewScreen(news ?: false, version) { WhatsNewState.markSeen(ctx); news = null }
        }
    }
}

/** Открыть «Обои и стиль» Nothing — там живут все настройки derealization. */
internal fun openWallpaperStyle(ctx: Context) {
    val i = android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER).setPackage("com.nothing.wallpaper")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure {
        ctx.packageManager.getLaunchIntentForPackage("com.nothing.wallpaper")?.let { l -> runCatching { ctx.startActivity(l) } }
    }
}

/** Подвкладки: соединённая группа кнопок-переключателей Material 3 Expressive. */
@Composable
internal fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.padding(top = 4.dp, bottom = 6.dp)) { Segmented(labels, selected, onSelect) }
}

/** Группа настроек: заголовок акцентом и карточка с крупными скруглениями. */
@Composable
internal fun Group(title: String, content: @Composable () -> Unit) = Appear { Column { GroupBody(title, content) } }

@Composable
private fun GroupBody(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CompositionLocalProvider(LocalOnCard provides true) { content() }
        }
    }
}

/** Внутри карточки переключатели лежат на чёрной подложке, вне её — на сером. */
internal val LocalOnCard = staticCompositionLocalOf { false }

@Composable
internal fun ClocksTab() {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(currentClock(ctx)?.takeIf { Clocks.isOurs(it) } ?: Clocks.IOS) }
    var prefs by remember(id) { mutableStateOf(Store.load(ctx, id)) }
    var version by remember { mutableIntStateOf(0) }
    var aod by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(currentClock(ctx)) }
    var sub by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }

    fun update(change: ClockPrefs.() -> Unit) {
        prefs = prefs.copy().apply(change)
        version++
    }
    // сохранение — когда палец замер на ~0,2 с: SystemUI не перечитывает настройки на каждый кадр жеста
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val savedId = id
        val saved = prefs                               // снимок на момент жеста — переключение часов его не потеряет
        Save.later("clock:$savedId", 200) { Store.save(ctx, savedId, saved) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Preview(id, prefs, version, aod) { change -> update(change) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.clock_as_aod), style = t(15), modifier = Modifier.weight(1f))
            Toggle(aod) { aod = it }
        }
        Spacer(Modifier.height(10.dp))
        SubTabs(listOf(s(R.string.clock_tab_choose), s(R.string.clock_tab_adjust)), sub) { sub = it }
        if (sub == 0) {
        Spacer(Modifier.height(8.dp))
        Clocks.OWN.toList().chunked(3).forEachIndexed { row, ids ->
            if (row > 0) Spacer(Modifier.height(6.dp))
            Segmented(ids.map { Clocks.title(it) }, ids.indexOf(id)) { id = ids[it] }
        }
        Spacer(Modifier.height(14.dp))
        Text("crDroid · ${Clocks.IDS.size - Clocks.OWN.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        CrdStrip(id) { id = it }

        if (id != Clocks.DOTS && id != Clocks.ANALOG) {
            Spacer(Modifier.height(14.dp))
            Text(s(R.string.clock_font_label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FontStrip(prefs.font) { v -> update { font = v } }
        }

        Spacer(Modifier.height(12.dp))
        val isApplied = applied == id
        Button(if (isApplied) s(R.string.clock_applied) else s(R.string.clock_apply), primary = !isApplied) {
            if (canApply(ctx)) { applyClock(ctx, id); applied = currentClock(ctx) }
        }
        if (!canApply(ctx)) {
            Text(
                s(R.string.clock_grant_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (applied != null && Clocks.isOurs(applied!!)) {
            Spacer(Modifier.height(8.dp))
            Button(s(R.string.clock_restore), primary = false) {
                if (canApply(ctx)) { restoreNothing(ctx); applied = currentClock(ctx) }
            }
        }

        } else {
        Group(s(R.string.clock_general)) {
            SliderRow(s(R.string.clock_size), prefs.size, 0.5f..2.5f, { "${(it * 100).toInt()}%" }) { v -> update { size = v } }
            SliderRow(s(R.string.clock_offset_x), prefs.offsetX, -120f..120f, { "${it.toInt()} dp" }) { v -> update { offsetX = v } }
            SliderRow(s(R.string.clock_offset_y), prefs.offsetY, 0f..200f, { "${it.toInt()} dp" }) { v -> update { offsetY = v } }
            val crd = Clocks.isCrd(id)
            if (!crd) {
                Label(s(R.string.clock_align))
                Segmented(listOf(s(R.string.as_system), s(R.string.clock_align_left), s(R.string.clock_align_center), s(R.string.clock_align_right)), prefs.align) { v -> update { align = v } }
            }
            Label(s(R.string.clock_color))
            Segmented(listOf(s(R.string.clock_color_wallpaper), s(R.string.clock_color_accent), s(R.string.clock_color_custom)), prefs.colorMode) { v -> update { colorMode = v } }
            if (prefs.colorMode == ClockPrefs.COLOR_CUSTOM || id == Clocks.ANALOG) {
                Palette(prefs.customColor) { v -> update { customColor = v } }
            }
            if (!crd) {
                SwitchRow(s(R.string.clock_seconds), prefs.seconds) { v -> update { seconds = v } }
                Label(s(R.string.clock_format))
                Segmented(listOf(s(R.string.as_system), s(R.string.clock_12h), s(R.string.clock_24h)), prefs.hourFormat) { v -> update { hourFormat = v } }
                SwitchRow(s(R.string.clock_leading_zero), prefs.leadingZero) { v -> update { leadingZero = v } }
            }
            SwitchRow(s(R.string.clock_hide_date), prefs.hideSystemDate) { v -> update { hideSystemDate = v } }
            SliderRow(s(R.string.clock_aod_brightness), prefs.aodBrightness, 0.2f..1f, { "${(it * 100).toInt()}%" }) { v -> update { aodBrightness = v } }
            if (!crd) SwitchRow(s(R.string.clock_aod_outline), prefs.aodOutline) { v -> update { aodOutline = v } }
        }

        when (id) {
            Clocks.DOTS -> {
                Group(s(R.string.clock_dots)) {
                    SliderRow(s(R.string.clock_dot_size), prefs.dotSize, 0.3f..1f, { "${(it * 100).toInt()}%" }) { v -> update { dotSize = v } }
                    SliderRow(s(R.string.clock_dot_pitch), prefs.dotSpacing, 0.6f..2f, { "×%.2f".format(it) }) { v -> update { dotSpacing = v } }
                    SliderRow(s(R.string.clock_glow), prefs.dotGlow, 0f..1f, { "${(it * 100).toInt()}%" }) { v -> update { dotGlow = v } }
                    SwitchRow(s(R.string.clock_square_dots), prefs.dotSquare) { v -> update { dotSquare = v } }
                    SwitchRow(s(R.string.clock_unlit_dots), prefs.dotGhost) { v -> update { dotGhost = v } }
                }
            }
            Clocks.STRETCH -> {
                Group(s(R.string.clock_stretch)) {
                    SliderRow(s(R.string.clock_by_height), prefs.stretchY, 0.6f..3.5f, { "×%.2f".format(it) }) { v -> update { stretchY = v } }
                    SliderRow(s(R.string.clock_by_width), prefs.stretchX, 0.5f..1.6f, { "×%.2f".format(it) }) { v -> update { stretchX = v } }
                    SliderRow(s(R.string.clock_weight), prefs.weight.toFloat(), 100f..900f, { it.toInt().toString() }) { v -> update { weight = v.toInt() } }
                    SwitchRow(s(R.string.clock_stacked), prefs.stacked) { v -> update { stacked = v } }
                    SliderRow(s(R.string.clock_line_gap), prefs.lineGap, -0.3f..0.6f, { "%.2f".format(it) }) { v -> update { lineGap = v } }
                    SliderRow(s(R.string.clock_tracking), prefs.letterSpacing, -0.15f..0.5f, { "%.2f".format(it) }) { v -> update { letterSpacing = v } }
                }
            }
            Clocks.IOS -> {
                Group("iOS") {
                    SliderRow(s(R.string.clock_weight), prefs.weight.toFloat(), 100f..900f, { it.toInt().toString() }) { v -> update { weight = v.toInt() } }
                    SliderRow(s(R.string.clock_stretch_height), prefs.stretchY, 0.8f..2.6f, { "×%.2f".format(it) }) { v -> update { stretchY = v } }
                    SliderRow(s(R.string.clock_by_width), prefs.stretchX, 0.6f..1.4f, { "×%.2f".format(it) }) { v -> update { stretchX = v } }
                    SwitchRow(s(R.string.clock_liquid_glass), prefs.glass) { v -> update { glass = v } }
                    SliderRow(s(R.string.clock_tracking), prefs.letterSpacing, -0.1f..0.3f, { "%.2f".format(it) }) { v -> update { letterSpacing = v } }
                }
            }
            Clocks.COLOROS -> {
                Group("ColorOS") {
                    SliderRow(s(R.string.clock_weight), prefs.weight.toFloat(), 100f..900f, { it.toInt().toString() }) { v -> update { weight = v.toInt() } }
                    SwitchRow(s(R.string.clock_two_tone), prefs.twoTone) { v -> update { twoTone = v } }
                    SwitchRow(s(R.string.clock_date_by_minutes), prefs.showDate) { v -> update { showDate = v } }
                    SliderRow(s(R.string.clock_stretch_height), prefs.stretchY, 0.8f..2.6f, { "×%.2f".format(it) }) { v -> update { stretchY = v } }
                    SliderRow(s(R.string.clock_line_gap), prefs.lineGap, -0.2f..0.6f, { "%.2f".format(it) }) { v -> update { lineGap = v } }
                }
            }
            Clocks.ORIGIN -> {
                Group("Origin") {
                    SliderRow(s(R.string.clock_weight), prefs.weight.toFloat(), 100f..600f, { it.toInt().toString() }) { v -> update { weight = v.toInt() } }
                    SwitchRow(s(R.string.clock_date_between), prefs.showDate) { v -> update { showDate = v } }
                    SliderRow(s(R.string.clock_stretch_height), prefs.stretchY, 0.8f..2.6f, { "×%.2f".format(it) }) { v -> update { stretchY = v } }
                    SliderRow(s(R.string.clock_tracking), prefs.letterSpacing, -0.1f..0.3f, { "%.2f".format(it) }) { v -> update { letterSpacing = v } }
                }
            }
            Clocks.ANALOG -> {
                Group(s(R.string.clock_hands)) {
                    Label(s(R.string.clock_marks))
                    Segmented(listOf(s(R.string.none), "4", "12", "60"), prefs.ticks) { v -> update { ticks = v } }
                    SliderRow(s(R.string.clock_hand_weight), prefs.handWidth, 0.4f..2.5f, { "×%.2f".format(it) }) { v -> update { handWidth = v } }
                    SwitchRow(s(R.string.clock_ring), prefs.dialRing) { v -> update { dialRing = v } }
                    SwitchRow(s(R.string.clock_accent_seconds), prefs.accentSecond) { v -> update { accentSecond = v } }
                }
            }
        }
        }
        Spacer(Modifier.height(110.dp))                 // место под плавающую панель вкладок
    }
}

@Composable
private fun Preview(id: String, prefs: ClockPrefs, version: Int, aod: Boolean, onChange: (ClockPrefs.() -> Unit) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val bg = if (aod) Brush.verticalGradient(listOf(Color.Black, Color.Black))
    else Brush.verticalGradient(listOf(Color(0xFF2B2B33), Color(0xFF15151A), Color(0xFF0B0B0D)))
    // у цифровых часов язычок тянет цифры (растяг), у точек и стрелок — меняет размер
    val stretchable = id != Clocks.DOTS && id != Clocks.ANALOG
    val current by rememberUpdatedState(prefs)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 300.dp).clip(RoundedCornerShape(28.dp)).background(bg)
            // щипок двумя пальцами — размер часов целиком
            .pointerInput(id) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onChange { size = (current.size * zoom).coerceIn(0.5f, 2.5f) }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val stamp = version + tick                 // новые настройки или новая секунда — перерисовать
            key(id) {
                AndroidView(
                    factory = { c -> Clocks.create(c, id, ClockFace.FACE_LARGE).asView() },
                    update = { view ->
                        val v = view as ClockFace
                        if (stamp >= 0) {
                            v.setPrefs(prefs.copy())
                            v.setDoze(if (aod) 1f else 0f)
                            v.setRegionDark(true)
                            v.refreshTime()
                        }
                    },
                    // высокие часы (растяг ×3) не обрезаем и не раздуваем экран — уменьшаем целиком до 250 dp
                    modifier = Modifier.fillMaxWidth().padding(top = 56.dp).layout { m, c ->
                        val pl = m.measure(c.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                        val maxH = 250.dp.roundToPx()
                        val k = if (pl.height > maxH) maxH.toFloat() / pl.height else 1f
                        layout(pl.width, (pl.height * k).toInt()) {
                            pl.placeWithLayer(0, 0) {
                                scaleX = k; scaleY = k
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                            }
                        }
                    }
                )
            }
            // язычок «как в iOS»: тянешь вниз/вверх — растяг по высоте (или размер), в стороны — по ширине
            Box(
                Modifier.padding(top = 6.dp).size(width = 120.dp, height = 36.dp)
                    .pointerInput(id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onChange {
                                if (stretchable) {
                                    stretchY = (current.stretchY + drag.y / 260f).coerceIn(0.6f, 3.5f)
                                    stretchX = (current.stretchX + drag.x / 520f).coerceIn(0.5f, 1.6f)
                                } else {
                                    size = (current.size + drag.y / 520f).coerceIn(0.5f, 2.5f)
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(width = 44.dp, height = 6.dp).clip(CircleShape).background(Accent)) }
            Text(
                if (stretchable) s(R.string.clock_drag_hint_stretch) else s(R.string.clock_drag_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(bottom = 14.dp)
            )
        }
    }
}

/** Лента живых миниатюр стилей crDroid: показываются по мере прокрутки (LazyRow). */
@Composable
private fun CrdStrip(selected: String, onSelect: (String) -> Unit) {
    val ids = remember { Clocks.IDS.filter { Clocks.isCrd(it) } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ids.indexOf(selected).coerceAtLeast(0))
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ids, key = { it }) { cid ->
            val on = cid == selected
            val cs = MaterialTheme.colorScheme
            Column(
                Modifier.width(132.dp).clip(RoundedCornerShape(16.dp)).background(if (on) cs.primaryContainer else cs.surfaceContainer)
                    .then(if (on) Modifier.border(3.dp, cs.primary, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable { onSelect(cid) }
            ) {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF15151A)),
                    contentAlignment = Alignment.TopCenter) {
                    AndroidView(
                        factory = { c -> Clocks.create(c, cid, ClockFace.FACE_SNAPSHOT).asView() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                Text(Clocks.title(cid), style = t(12, if (on) FontWeight.Medium else FontWeight.Normal, if (on) cs.onPrimaryContainer else cs.onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), maxLines = 1)
            }
        }
    }
}

/** Лента шрифтов: каждый показан на примере времени — сразу видно, как будут выглядеть часы. */
@Composable
private fun FontStrip(selected: String, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val keys = remember { listOf("") + Fonts.all(ctx) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = keys.indexOf(selected).coerceAtLeast(0))
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(keys, key = { it }) { k ->
            val on = k == selected
            val family = remember(k) { if (k.isEmpty()) FontFamily.Default else FontFamily(Fonts.get(ctx, k)) }
            val cs = MaterialTheme.colorScheme
            val r by androidx.compose.animation.core.animateDpAsState(if (on) 24.dp else 14.dp, label = "font")
            Column(
                Modifier.clip(RoundedCornerShape(r)).background(if (on) cs.primaryContainer else cs.surfaceContainer)
                    .clickable { onSelect(k) }.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("12:34", style = TextStyle(fontFamily = family, fontSize = 24.sp, color = if (on) cs.onPrimaryContainer else cs.onSurface), maxLines = 1)
                Text(Fonts.title(k), style = t(11, c = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant), maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(fmt(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ThinSlider(value, range, onChange)
    }
}

/** Ползунок Material 3 Expressive. */
@Composable
internal fun ThinSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    androidx.compose.material3.Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
internal fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Toggle(value, onChange)
    }
}

/** Переключатель Material 3: включённый — с галочкой на ручке. */
@Composable
internal fun Toggle(value: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = value, onCheckedChange = onChange,
        thumbContent = if (value) { { Icon(CheckIcon, null, Modifier.size(SwitchDefaults.IconSize)) } } else null,
    )
}

internal val CheckIcon by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("check", 24.dp, 24.dp, 24f, 24f)
        .addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
        .build()
}

/** Выбор одного из вариантов: соединённая группа кнопок-переключателей (Material 3 Expressive). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val colors = ToggleButtonDefaults.colors(
        containerColor = if (LocalOnCard.current) cs.surfaceContainerHighest else cs.surfaceContainer,
        contentColor = cs.onSurfaceVariant,
        checkedContainerColor = cs.primary, checkedContentColor = cs.onPrimary,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, label ->
            ToggleButton(
                checked = i == selected, onCheckedChange = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Palette(selected: Int, onSelect: (Int) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PALETTE.forEach { c ->
            val argb = Color(c).toArgb()
            val on = argb == selected
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .then(if (on) Modifier.border(3.dp, ring, CircleShape).padding(5.dp).clip(CircleShape) else Modifier.padding(2.dp).clip(CircleShape))
                    .background(Color(c)).clickable { onSelect(argb) }
            )
        }
    }
}

/** Кнопка Material 3 Expressive: главная — заливкой, остальные — тональные; форма пружинит при нажатии. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Button(text: String, primary: Boolean, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (primary) androidx.compose.material3.Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = m) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    } else FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = m) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
