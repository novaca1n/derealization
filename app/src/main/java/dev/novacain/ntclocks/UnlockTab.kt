package dev.novacain.ntclocks

import android.provider.Settings
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.UnlockAnim
import org.json.JSONObject

/** «Разблокировка» (экран блокировки): часы и виджеты уезжают вверх и растворяются, как в iOS (UnlockAnim в SystemUI). */
@Composable
internal fun UnlockTab() {
    val cr = LocalContext.current.contentResolver
    val saved = remember { runCatching { JSONObject(Settings.Global.getString(cr, UnlockAnim.KEY) ?: "{}") }.getOrDefault(JSONObject()) }
    var enabled by remember { mutableStateOf(saved.optBoolean("enabled", true)) }
    var distance by remember { mutableFloatStateOf(saved.optDouble("distance", 0.22).toFloat()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val json = JSONObject().put("enabled", enabled).put("distance", distance.toDouble()).toString()
        Save.later("unlock", 200) { runCatching { Settings.Global.putString(cr, UnlockAnim.KEY, json) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group(s(R.string.unlock_title)) {
            SwitchRow(s(R.string.unlock_enabled), enabled) { v -> enabled = v; version++ }
            Text(
                s(R.string.unlock_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
            if (enabled) {
                SliderRow(s(R.string.unlock_distance), distance, 0.08f..0.45f, { s(R.string.unlock_distance_value, (it * 100).toInt()) }) { v -> distance = v; version++ }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
