package dev.novacain.ntclocks

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.HomePrefs
import kotlin.math.roundToInt

/** «Стол и док» (главный экран): док-стекло iOS 26, подпрыгивание, подписи, жесты, растягивание за угол. */
@Composable
internal fun HomeTab() {
    val cr = LocalContext.current.contentResolver
    var h by remember { mutableStateOf(HomePrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    fun update(change: HomePrefs.() -> Unit) {
        h = h.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = h
        Save.later("home", 200) { runCatching { HomePrefs.write(cr, saved) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group(s(R.string.dock_title)) {
            SwitchRow(s(R.string.dock_glass), h.dockGlass) { v -> update { dockGlass = v } }
            if (h.dockGlass) {
                SliderRow(s(R.string.glass_blur), h.dockBlur, 0f..120f, { "${it.roundToInt()} dp" }) { v -> update { dockBlur = v } }
                SliderRow(s(R.string.dock_tint), h.dockTint, 0f..0.6f, { "${(it * 100).roundToInt()}%" }) { v -> update { dockTint = v } }
            }
            SwitchRow(s(R.string.dock_bounce), h.bounce) { v -> update { bounce = v } }
            Text(s(R.string.dock_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        }
        Group(s(R.string.labels_title)) {
            Segmented(listOf(s(R.string.labels_nothing), s(R.string.labels_no_dock), s(R.string.labels_none)), h.labels) { v -> update { labels = v } }
            SwitchRow(s(R.string.labels_ndot), h.ndot) { v -> update { ndot = v } }
        }
        Group(s(R.string.gestures_title)) {
            SwitchRow(s(R.string.gesture_two_finger_qs), h.twoFingerQs) { v -> update { twoFingerQs = v } }
            SwitchRow(s(R.string.gesture_corner_resize), h.cornerResize) { v -> update { cornerResize = v } }
            Text(
                s(R.string.gesture_double_tap_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
