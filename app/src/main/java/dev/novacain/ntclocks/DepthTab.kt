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
        Group("Своя маска") {
            Text(
                "Любое фото на экран блокировки: объект выделится сам, поправь его пальцем — и он встанет поверх часов.",
                style = t(13, c = Dim), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button("Выбрать фото", primary = true) {
                ctx.startActivity(Intent(ctx, DepthMaskActivity::class.java))
            }
            Spacer(Modifier.height(8.dp))
            Button("Убрать", primary = false) {
                scope.launch {
                    val err = withContext(Dispatchers.IO) { DepthApply.remove(ctx) }
                    android.widget.Toast.makeText(ctx, err ?: "Экран блокировки снова с обоями рабочего стола", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Group("Для любых обоев") {
            SwitchRow("Не запрещать эффект", d.anyWallpaper) { v -> update { anyWallpaper = v } }
            Text(
                "«Обои и стиль» разрешают глубину и со стеклом обоев, с широким виджетом и когда объект не заходит на часы.",
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Group("Под мои большие часы") {
            SwitchRow("Подрезать объект по часам", d.enabled) { v -> update { enabled = v } }
            if (d.enabled) {
                SliderRow("Объект закрывает часы снизу", d.cover, 0f..0.8f, { "до ${(it * 100).toInt()}%" }) { v -> update { cover = v } }
            }
            Text(
                "Объект заходит только на нижнюю часть часов — цифры остаются читаемыми.",
                style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
