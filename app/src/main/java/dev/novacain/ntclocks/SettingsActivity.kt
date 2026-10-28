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
        Ui.picker(this)
        enableEdgeToEdge()
        val section = intent.getStringExtra("section") ?: "anim"
        setContent { SettingsPage(section) { finish() } }
    }

    override fun onPause() {
        Save.flush()
        super.onPause()
    }
}

private val TITLES = mapOf("anim" to "Анимации", "glass" to "Стекло", "color" to "Цвета", "clocks" to "Часы",
    "depth" to "Глубина", "widgets" to "Виджеты", "unlock" to "Разблокировка", "home_dock" to "Стол и док", "home" to "derealization", "lock" to "derealization")

/** Разделы хаба: ключ, название, пояснение. */
private val HUB = mapOf(
    "home" to listOf(
        Triple("anim", "Анимации", "Открытие приложений, стол, меню, шторка"),
        Triple("home_dock", "Стол и док", "Док-стекло, подписи, жесты, растягивание значков"),
        Triple("glass", "Стекло", "Прозрачность и размытие шторки и меню, виджеты"),
        Triple("color", "Цвета", "Перекраска системы"),
    ),
    "lock" to listOf(
        Triple("clocks", "Часы", "Свои часы экрана блокировки и их вид"),
        Triple("depth", "Глубина", "Объект с обоев поверх часов, своя маска"),
        Triple("widgets", "Виджеты", "Наушники CMF, шаги с часов, музыка"),
        Triple("unlock", "Разблокировка", "Часы уезжают и растворяются, как в iOS"),
        Triple("glass", "Стекло", "Размытие и прозрачность"),
    ),
)

@Composable
private fun SettingsPage(start: String, onBack: () -> Unit) {
    var section by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(start) }
    val inHub = HUB.containsKey(section)
    val back = { if (!inHub && HUB.containsKey(start)) section = start else onBack() }
    androidx.activity.compose.BackHandler(onBack = back)
    val bg = colorResource(android.R.color.system_neutral1_900)
    val card = Ui.surface
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        // шапка как у «Обоев и стиля»: стрелка назад слева, заголовок по центру
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(48.dp).clip(CircleShape).clickable(onClick = back),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    val w = size.width; val s = w * 0.09f
                    drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), s, StrokeCap.Round)
                    drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.2f), s, StrokeCap.Round)
                    drawLine(Ui.text, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.8f), s, StrokeCap.Round)
                }
            }
            Text(TITLES[section] ?: "derealization", style = t(22, FontWeight.Normal), modifier = Modifier.align(Alignment.Center))
        }
        Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
            AppearScope(section) {
                when {
                    inHub -> Column(Modifier.padding(horizontal = 16.dp)) {
                        HUB[section]!!.forEach { (key, title, sub) ->
                            Appear {
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).background(card)
                                    .clickable { section = key }.padding(horizontal = 20.dp, vertical = 18.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = t(18, FontWeight.Medium))
                                        Text(sub, style = t(13, c = Dim), modifier = Modifier.padding(top = 2.dp))
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
