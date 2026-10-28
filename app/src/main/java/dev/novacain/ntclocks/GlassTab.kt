package dev.novacain.ntclocks

import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.GlassPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Модуль Magisk, который включает размытие окон в SurfaceFlinger (на CMF Phone 2 Pro Nothing его выключила). */
private const val BLUR_MODULE = "/data/adb/modules/ntweaker_blur"

private fun su(cmd: String): Pair<Boolean, String> = runCatching {
    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    val out = p.inputStream.bufferedReader().readText()
    (p.waitFor() == 0) to out
}.getOrDefault(false to "")

/** Состояние модуля: установлен и не помечен на удаление. */
private fun blurModuleOn(): Boolean =
    su("[ -f $BLUR_MODULE/system.prop ] && [ ! -f $BLUR_MODULE/remove ] && [ ! -f $BLUR_MODULE/disable ]").first

private fun setBlurModule(on: Boolean): Boolean = if (on) su(
    "mkdir -p $BLUR_MODULE && rm -f $BLUR_MODULE/remove $BLUR_MODULE/disable && " +
        "printf 'id=ntweaker_blur\\nname=derealization: window blur\\nversion=1\\nversionCode=1\\nauthor=novaca1n\\n" +
        "description=Turns window blur on (ro.surface_flinger.supports_background_blur)\\n' > $BLUR_MODULE/module.prop && " +
        "printf 'ro.surface_flinger.supports_background_blur=1\\n' > $BLUR_MODULE/system.prop"
).first else su("touch $BLUR_MODULE/remove").first

@Composable
internal fun GlassTab() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()
    var g by remember { mutableStateOf(GlassPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    // размытие работает прямо сейчас (флаг SurfaceFlinger и «Разрешить размытие окон» в параметрах разработчика)
    val blurNow = remember { runCatching { ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled }.getOrDefault(false) }
    var module by remember { mutableStateOf<Boolean?>(null) }
    var moduleChanged by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { module = withContext(Dispatchers.IO) { blurModuleOn() } }

    fun update(change: GlassPrefs.() -> Unit) {
        g = g.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = g
        Save.later("glass", 200) { runCatching { GlassPrefs.write(cr, saved) } }
    }

    var sub by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SubTabs(listOf(s(R.string.glass_tab_shade), s(R.string.glass_tab_home), s(R.string.glass_tab_system)), sub) { sub = it }
        if (sub < 2) Group(s(R.string.glass_title)) {
            SwitchRow(s(R.string.enable), g.enabled) { v -> update { enabled = v } }
            Text(
                if (g.enabled) s(R.string.glass_on_hint)
                else s(R.string.glass_off_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp)
            )
        }
        when (sub) {
        0 -> {
        Group(s(R.string.glass_shade_qs)) {
            SliderRow(s(R.string.glass_bg_opacity), g.shadeAlpha, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { shadeAlpha = v } }
            SliderRow(s(R.string.glass_blur), g.shadeBlur, 0f..120f, { "${it.roundToInt()} dp" }) { v -> update { shadeBlur = v } }
            SliderRow(s(R.string.glass_grain), g.shadeGrain, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { shadeGrain = v } }
        }
        Group(s(R.string.glass_notifications)) {
            SwitchRow(s(R.string.glass_notif_round), g.notifRound) { v -> update { notifRound = v } }
            if (g.notifRound) {
                SliderRow(s(R.string.glass_notif_radius), g.notifRadius, 8f..40f, { "${it.roundToInt()} dp" }) { v -> update { notifRadius = v } }
                SliderRow(s(R.string.glass_notif_gap), g.notifGap, 0f..24f, { "${it.roundToInt()} dp" }) { v -> update { notifGap = v } }
            }
            Text(s(R.string.applies_after_reboot), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        }
        Group(s(R.string.glass_panels)) {
            SliderRow(s(R.string.glass_panel_alpha), g.panelAlpha, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { panelAlpha = v } }
            Text(s(R.string.glass_panel_alpha_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        }
        QsSlidersCard()
        }
        1 -> {
        Group(s(R.string.glass_drawer)) {
            SliderRow(s(R.string.glass_bg_opacity), g.drawerAlpha, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { drawerAlpha = v } }
            SliderRow(s(R.string.glass_wallpaper_blur), g.drawerBlur, 0f..120f, { "${it.roundToInt()} dp" }) { v -> update { drawerBlur = v } }
            SliderRow(s(R.string.glass_grain), g.drawerGrain, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { drawerGrain = v } }
            SliderRow(s(R.string.glass_search_alpha), g.searchAlpha, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { searchAlpha = v } }
        }
        LucentCard()
        NavCard()
        }
        else -> {
        Group(s(R.string.glass_system_blur)) {
            Text(
                if (blurNow) s(R.string.glass_blur_works) else s(R.string.glass_blur_off),
                style = t(15), modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                s(R.string.glass_blur_module_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
            module?.let { on ->
                SwitchRow(s(R.string.glass_blur_module), on) { v ->
                    scope.launch {
                        if (withContext(Dispatchers.IO) { setBlurModule(v) }) { module = v; moduleChanged = true }
                    }
                }
            }
            if (moduleChanged) Text(s(R.string.reboot_to_apply), style = t(12, c = Accent), modifier = Modifier.padding(top = 2.dp))
        }
        }
        }
        Spacer(Modifier.height(110.dp))
    }
}

/** Папка или виджет рабочего стола (список присылает лаунчер — см. LucentHooks.publish). */
private data class HomeItem(val id: Int, val folder: Boolean, val title: String, val page: Int)

private fun homeItems(ctx: android.content.Context): List<HomeItem> = runCatching {
    val a = org.json.JSONArray(ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getString("homeItems", "[]"))
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val folder = o.optString("type") == "folder"
        val title = o.optString("title").trim().ifEmpty { if (folder) s(R.string.lucent_untitled) else s(R.string.lucent_widget) }
        HomeItem(o.getInt("id"), folder, title, o.optInt("page", 1))
    }
}.getOrDefault(emptyList())

/** Прозрачные папки и виджеты — как тема Lucent в Nothing OS 5. */
@Composable
private fun LucentCard() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var l by remember { mutableStateOf(dev.novacain.ntclocks.anim.LucentPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    val items = remember { homeItems(ctx) }
    fun update(change: dev.novacain.ntclocks.anim.LucentPrefs.() -> Unit) {
        l = l.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = l
        Save.later("lucent", 150) { runCatching { dev.novacain.ntclocks.anim.LucentPrefs.write(cr, saved) } }
    }
    Group(s(R.string.lucent_title)) {
        Text(s(R.string.lucent_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 4.dp))
        SwitchRow(s(R.string.enable), l.enabled) { v -> update { enabled = v } }
        if (l.enabled) {
            SliderRow(s(R.string.lucent_opacity), l.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { opacity = v } }
            SwitchRow(s(R.string.glass_wallpaper_blur), l.blur) { v -> update { blur = v } }
            if (l.blur) {
                SliderRow(s(R.string.lucent_blur_radius), l.blurRadius, 4f..80f, { "${it.roundToInt()} dp" }) { v -> update { blurRadius = v } }
                SliderRow(s(R.string.glass_grain), l.grain, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { grain = v } }
            }
            SwitchRow(s(R.string.lucent_all), l.all) { v -> update { all = v } }
            if (!l.all) {
                if (items.isEmpty()) {
                    Text(s(R.string.lucent_list_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 6.dp))
                }
                items.forEach { it ->
                    SwitchRow(s(if (it.folder) R.string.lucent_folder_row else R.string.lucent_widget_row, it.title, it.page), it.id in l.ids) { v ->
                        update { if (v) ids.add(it.id) else ids.remove(it.id) }
                    }
                }
            }
        }
    }
}


/** Вертикальные ползунки — это плитки «Яркость» и «Громкость» в сетке быстрых настроек. */
@Composable
private fun QsSlidersCard() {
    Group(s(R.string.glass_qs)) {
        Text(s(R.string.glass_sliders), style = t(15), modifier = Modifier.padding(top = 10.dp))
        Text(
            s(R.string.glass_sliders_hint),
            style = t(12, c = Dim), modifier = Modifier.padding(top = 4.dp)
        )
    }
}
