package dev.novacain.ntclocks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Настройки derealization — открываются из «Обоев и стиля» Nothing (кнопки «Анимации», «Стекло», «Цвета», «Часы»,
 * см. PickerHooks) и встают в ту же задачу, поверх страницы «Главный экран». Своей иконки в лаунчере у экрана нет:
 * само приложение derealization — только патчер. Фон и шапка — как у «Обоев и стиля» (цвета Material You системы).
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        section.value = intent.getStringExtra("section") ?: "anim"
        setContent { DereTheme { androidx.compose.runtime.key(section.value) { SettingsPage(section.value) { finish() } } } }
    }

    /** Уже открыт сверху, а просят другой раздел — показать его (из «Обоев и стиля» экран и так открывается заново). */
    private val section = androidx.compose.runtime.mutableStateOf("anim")

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("section")?.let { section.value = it }
    }

    override fun onPause() {
        Save.flush()
        super.onPause()
    }
}

private val TITLES get() = mapOf("anim" to s(R.string.section_anim), "glass" to s(R.string.section_glass), "color" to s(R.string.section_color), "clocks" to s(R.string.section_clocks),
    "depth" to s(R.string.section_depth), "widgets" to s(R.string.section_widgets), "unlock" to s(R.string.section_unlock), "home_dock" to s(R.string.section_home_dock), "home" to "derealization", "lock" to "derealization")

/** Разделы хаба: ключ, название, пояснение. */
private val HUB get() = mapOf(
    "home" to listOf(
        Triple("anim", s(R.string.section_anim), s(R.string.section_anim_hint)),
        Triple("home_dock", s(R.string.section_home_dock), s(R.string.section_home_dock_hint)),
        Triple("glass", s(R.string.section_glass), s(R.string.section_glass_hint)),
        Triple("color", s(R.string.section_color), s(R.string.section_color_hint)),
    ),
    "lock" to listOf(
        Triple("clocks", s(R.string.section_clocks), s(R.string.section_clocks_hint)),
        Triple("depth", s(R.string.section_depth), s(R.string.section_depth_hint)),
        Triple("widgets", s(R.string.section_widgets), s(R.string.section_widgets_hint)),
        Triple("unlock", s(R.string.section_unlock), s(R.string.section_unlock_hint)),
        Triple("glass", s(R.string.section_glass), s(R.string.section_glass_lock_hint)),
    ),
)

@Composable
private fun SettingsPage(start: String, onBack: () -> Unit) {
    var section by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(start) }
    val inHub = HUB.containsKey(section)
    val back = { if (!inHub && HUB.containsKey(start)) section = start else onBack() }
    androidx.activity.compose.BackHandler(onBack = back)
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val type = androidx.compose.material3.MaterialTheme.typography
    // Surface задаёт цвет содержимого (onSurface) — иначе значки рисуются чёрным
    androidx.compose.material3.Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // шапка как у TweakStore: «назад» сверху, крупный заголовок слева
        androidx.compose.material3.IconButton(onClick = back, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            androidx.compose.material3.Icon(BackIcon, s(R.string.action_back))
        }
        Text(TITLES[section] ?: "derealization", style = type.displaySmall, color = cs.onSurface,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp))
        Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
            AppearScope(section) {
                when {
                    inHub -> Column(Modifier.padding(horizontal = 16.dp)) {
                        val items = HUB[section]!!
                        items.forEachIndexed { i, (key, title, sub) ->
                            Appear {
                                androidx.compose.material3.Surface(color = cs.surfaceContainer, shape = groupShape(i, items.size),
                                    onClick = { section = key }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    androidx.compose.foundation.layout.Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(title, style = type.titleMedium, color = cs.onSurface)
                                            Text(sub, style = type.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                                        }
                                        androidx.compose.material3.Icon(ChevronIcon, null, tint = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    section == "glass" -> GlassTab()
                    section == "color" -> ColorTab()
                    section == "clocks" -> ClocksTab()
                    section == "depth" -> DepthTab()
                    section == "widgets" -> WidgetsTab()
                    section == "unlock" -> UnlockTab()
                    section == "home_dock" -> HomeTab()
                    else -> AnimTab()
                }
            }
        }
    }
}
}

/** Соединённый список Material 3 Expressive: у группы крупные внешние углы, между строками — маленькие. */
internal fun groupShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
    val big = 24.dp; val small = 6.dp
    return androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

private fun icon(name: String, path: String) = androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black))
    .build()

/** Material Symbols (Apache-2.0). */
internal val BackIcon by lazy { icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
internal val ChevronIcon by lazy { icon("chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
