package dev.novacain.ntclocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.WidgetPrefs

/** «Виджеты» (экран блокировки): ряд карточек derealization под виджетами Nothing — рисует SystemUI (LockWidgets). */
@Composable
internal fun WidgetsTab() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var w by remember { mutableStateOf(WidgetPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    val cmf = remember { runCatching { ctx.packageManager.getPackageInfo("dev.novacain.cmflink", 0).versionName }.getOrNull() }
    fun update(change: WidgetPrefs.() -> Unit) {
        w = w.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = w
        Save.later("widgets", 200) { runCatching { WidgetPrefs.write(cr, saved) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group("Виджеты экрана блокировки") {
            SwitchRow("Показывать", w.enabled) { v -> update { enabled = v } }
            Text(
                "Ряд карточек Material 3 под часами, в цветах системы. Карточка без данных не показывается, в Always-on ряд скрыт.",
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        if (w.enabled) {
            Group("Пример") {
                // те же карточки, что рисует SystemUI, на примерных данных
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF3A4A5C), androidx.compose.ui.graphics.Color(0xFF1B2229)))
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .background(brush).padding(vertical = 18.dp)
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c -> dev.novacain.ntclocks.anim.WidgetCards.Row(c) },
                        update = { row ->
                            dev.novacain.ntclocks.anim.LockWidgets.prefs = w
                            dev.novacain.ntclocks.anim.LockWidgets.demo()
                            row.update()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Group("Карточки") {
                SwitchRow("Наушники CMF — заряд", w.buds) { v -> update { buds = v } }
                SwitchRow("Шаги с часов", w.steps) { v -> update { steps = v } }
                if (w.steps) {
                    SliderRow("Цель шагов", w.goal.toFloat(), 2000f..20000f, { "${(it / 500).toInt() * 500}" }) { v -> update { goal = (v / 500).toInt() * 500 } }
                }
                SwitchRow("Музыка", w.music) { v -> update { music = v } }
                Text(
                    when {
                        cmf == null -> "Наушники и шаги берутся из cmf link — он не установлен."
                        else -> "Наушники и шаги — из cmf link $cmf (нужна 1.2 или новее); музыка — из любого плеера."
                    },
                    style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
