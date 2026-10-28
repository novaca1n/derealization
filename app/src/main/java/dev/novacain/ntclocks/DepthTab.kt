package dev.novacain.ntclocks

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.novacain.ntclocks.anim.DepthPrefs

/**
 * «Глубина» (экран блокировки): эффект глубины Nothing без их ограничений. Снять запреты «Обоев и стиля», подрезать
 * объект под большие часы и — своя маска: любое фото, объект выделяется сам и правится пальцем (DepthMaskActivity).
 */
@Composable
internal fun DepthTab() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var d by remember { mutableStateOf(DepthPrefs.read(cr)) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun update(change: DepthPrefs.() -> Unit) {
        d = d.copy().apply(change)
        version++
    }
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        val saved = d
        Save.later("depth", 200) { runCatching { DepthPrefs.write(cr, saved) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group(s(R.string.depth_custom_mask)) {
            Text(
                s(R.string.depth_custom_mask_hint),
                style = t(13, c = Dim), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(s(R.string.depth_pick_photo), primary = true) {
                ctx.startActivity(Intent(ctx, DepthMaskActivity::class.java))
            }
            Spacer(Modifier.height(8.dp))
            Button(s(R.string.action_remove), primary = false) {
                scope.launch {
                    val err = withContext(Dispatchers.IO) { DepthApply.remove(ctx) }
                    android.widget.Toast.makeText(ctx, err ?: s(R.string.depth_removed), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Group(s(R.string.depth_any_title)) {
            SwitchRow(s(R.string.depth_any), d.anyWallpaper) { v -> update { anyWallpaper = v } }
            Text(
                s(R.string.depth_any_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Group(s(R.string.depth_big_clock_title)) {
            SwitchRow(s(R.string.depth_cut), d.enabled) { v -> update { enabled = v } }
            if (d.enabled) {
                SliderRow(s(R.string.depth_cover), d.cover, 0f..0.8f, { s(R.string.depth_cover_value, (it * 100).toInt()) }) { v -> update { cover = v } }
            }
            Text(
                s(R.string.depth_cover_hint),
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
