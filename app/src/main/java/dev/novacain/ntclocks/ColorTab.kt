package dev.novacain.ntclocks

import android.app.WallpaperManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.ColorPrefs
import dev.novacain.ntclocks.anim.NavPrefs
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

private const val THEME_KEY = "theme_customization_overlay_packages"
private const val K_STYLE = "android.theme.customization.theme_style"
private const val K_SOURCE = "android.theme.customization.color_source"
private const val K_PALETTE = "android.theme.customization.system_palette"
private const val K_ACCENT = "android.theme.customization.accent_color"

/** Палитры Material You: id, подпись, (светлый, тёмный) тон для образца. */
private val PALETTES get() = listOf(
    Triple("a1", s(R.string.palette_primary), android.R.color.system_accent1_200 to android.R.color.system_accent1_700),
    Triple("a2", s(R.string.palette_secondary), android.R.color.system_accent2_200 to android.R.color.system_accent2_700),
    Triple("a3", s(R.string.palette_tertiary), android.R.color.system_accent3_200 to android.R.color.system_accent3_700),
    Triple("n2", s(R.string.palette_neutral), android.R.color.system_neutral2_200 to android.R.color.system_neutral2_700),
)

private fun readTheme(ctx: android.content.Context): JSONObject =
    runCatching { JSONObject(Settings.Secure.getString(ctx.contentResolver, THEME_KEY) ?: "{}") }.getOrDefault(JSONObject())

/** Правка настройки тем: система (ThemeOverlayController) сама пересчитает палитру и всё перекрасит. */
private fun writeTheme(ctx: android.content.Context, change: JSONObject.() -> Unit) {
    val o = readTheme(ctx).apply(change).put("_applied_timestamp", System.currentTimeMillis())
    runCatching { Settings.Secure.putString(ctx.contentResolver, THEME_KEY, o.toString()) }
}

/** Виды адаптивной перекраски — стили системного Material You (имена — как их понимает ThemeOverlayController). */
private val KINDS get() = listOf(
    "TONAL_SPOT" to s(R.string.kind_tonal), "VIBRANT" to s(R.string.kind_vibrant), "EXPRESSIVE" to s(R.string.kind_expressive),
    "FRUIT_SALAD" to s(R.string.kind_fruit), "RAINBOW" to s(R.string.kind_rainbow),
)

/**
 * Цвета: только «Стоковый» (как у Nothing) или «Адаптивный» — всегда из обоев и сам обновляется при смене обоев.
 * У адаптивного — вид перекраски. Всё остальное (что и каким тоном красить) движок решает сам.
 */
@Composable
internal fun ColorTab() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var on by remember { mutableStateOf(ColorPrefs.read(cr).enabled) }
    var theme by remember { mutableStateOf(readTheme(ctx)) }
    val kind = theme.optString(K_STYLE, "TONAL_SPOT").let { k -> if (KINDS.any { it.first == k }) k else "TONAL_SPOT" }

    fun setAdaptive(v: Boolean) {
        on = v
        // настройки областей — всегда стандартные: перекраска одна, наша
        runCatching { ColorPrefs.write(cr, ColorPrefs().apply { enabled = v }) }
        // цвет — только из обоев
        writeTheme(ctx) { put(K_SOURCE, "home_wallpaper"); remove(K_PALETTE); remove(K_ACCENT) }
        theme = readTheme(ctx)
    }
    fun setKind(k: String) {
        writeTheme(ctx) { put(K_STYLE, k); put(K_SOURCE, "home_wallpaper"); remove(K_PALETTE); remove(K_ACCENT) }
        theme = readTheme(ctx)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group(s(R.string.color_recolor)) {
            Spacer(Modifier.height(10.dp))
            Segmented(listOf(s(R.string.color_stock), s(R.string.color_adaptive)), if (on) 1 else 0) { setAdaptive(it == 1) }
            Text(
                if (on) s(R.string.color_adaptive_hint)
                else s(R.string.color_stock_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (on) {
            Group(s(R.string.color_kind)) {
                val ids = listOf(
                    android.R.color.system_accent1_200, android.R.color.system_accent1_500, android.R.color.system_accent2_300,
                    android.R.color.system_accent3_300, android.R.color.system_neutral2_500, android.R.color.system_neutral2_800,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ids.forEach { id -> Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(ctx.getColor(id)))) }
                }
                KINDS.chunked(3).forEach { row ->
                    Spacer(Modifier.height(8.dp))
                    Segmented(row.map { it.second }, row.indexOfFirst { it.first == kind }) { i -> setKind(row[i].first) }
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

/** Полоска жестов: цвет (как у Nothing или тон палитры), длина, толщина, непрозрачность — меняется на лету. */
@Composable
internal fun NavCard() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var n by remember { mutableStateOf(NavPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    fun update(change: NavPrefs.() -> Unit) {
        n = n.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = n
        Save.later("nav", 120) { runCatching { NavPrefs.write(cr, saved) } }
    }
    Group(s(R.string.nav_title)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.nav_hint), style = t(12, c = Dim), modifier = Modifier.weight(1f).padding(end = 12.dp))
            Toggle(n.enabled) { v -> update { enabled = v } }
        }
        if (n.enabled) {
            // превью полоски
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(44.dp).clip(RoundedCornerShape(14.dp)).background(Bg), contentAlignment = Alignment.Center) {
                val c = when (n.color) {
                    "a1" -> ctx.getColor(android.R.color.system_accent1_200)
                    "a2" -> ctx.getColor(android.R.color.system_accent2_200)
                    "a3" -> ctx.getColor(android.R.color.system_accent3_200)
                    else -> 0xEBFFFFFF.toInt()
                }
                Box(Modifier.width(n.width.dp * 0.8f).height(n.thickness.dp).clip(RoundedCornerShape(50)).background(Color(c).copy(alpha = n.opacity)))
            }
            Text(s(R.string.clock_color), style = t(15), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaletteChip("Nothing", android.R.color.white to android.R.color.black, n.color == "system", Modifier.weight(1f)) { update { color = "system" } }
                PALETTES.take(3).forEach { (pid, label, res) ->
                    PaletteChip(label, res, n.color == pid, Modifier.weight(1f)) { update { color = pid } }
                }
            }
            SliderRow(s(R.string.nav_length), n.width, 60f..260f, { "${it.roundToInt()} dp" }) { v -> update { width = v } }
            SliderRow(s(R.string.clock_weight), n.thickness, 2f..8f, { "%.1f dp".format(it) }) { v -> update { thickness = v } }
            SliderRow(s(R.string.nav_opacity), n.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { opacity = v } }
        }
    }
}

/** Образец палитры: две половинки (светлый и тёмный тон), подпись, рамка у выбранной. */
@Composable
private fun PaletteChip(label: String, res: Pair<Int, Int>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(12.dp))
                .then(if (selected) Modifier.border(3.dp, androidx.compose.material3.MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            Box(Modifier.weight(1f).fillMaxSize().background(Color(ctx.getColor(res.first))))
            Box(Modifier.weight(1f).fillMaxSize().background(Color(ctx.getColor(res.second))))
        }
        Text(label, style = t(12, if (selected) FontWeight.Medium else FontWeight.Normal,
            if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primary else Dim), modifier = Modifier.padding(top = 4.dp))
    }
}
