package dev.novacain.ntclocks

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Значок пункта «Что нового» — рисуется линиями, в одном стиле. */
enum class NewsIcon { Motion, Blur, Drawer, Shade, Home, Sheet, Clock, Resize, Split }

data class News(val icon: NewsIcon, val title: String, val text: String)

/** Что нового в текущей версии (показывается один раз после установки или обновления). Getter — текст на языке телефона. */
val NEWS get() = listOf(
    News(NewsIcon.Sheet, s(R.string.news_store), s(R.string.news_store_text)),
    News(NewsIcon.Clock, s(R.string.news_clocks), s(R.string.news_clocks_text)),
    News(NewsIcon.Clock, s(R.string.news_depth), s(R.string.news_depth_text)),
    News(NewsIcon.Motion, s(R.string.news_motion), s(R.string.news_motion_text)),
    News(NewsIcon.Shade, s(R.string.news_ios), s(R.string.news_ios_text)),
    News(NewsIcon.Home, s(R.string.news_home), s(R.string.news_home_text)),
    News(NewsIcon.Resize, s(R.string.news_resize), s(R.string.news_resize_text)),
    News(NewsIcon.Split, s(R.string.news_glass), s(R.string.news_glass_text)),
    News(NewsIcon.Sheet, s(R.string.news_settings), s(R.string.news_settings_text)),
    News(NewsIcon.Home, s(R.string.news_safe), s(R.string.news_safe_text)),
)

object WhatsNewState {
    private const val KEY = "seenVersion"
    private fun code(ctx: Context): Long = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    /** Показать? null — нет; true — первая установка («Добро пожаловать»); false — обновление («Обновлено до …»). */
    fun pending(ctx: Context): Boolean? {
        val seen = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getLong(KEY, -1L)
        return when {
            // записи ещё нет: первая установка, если пакет ни разу не обновлялся
            seen == -1L -> runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime } }.getOrDefault(true)
            seen < code(ctx) -> false
            else -> null
        }
    }

    fun markSeen(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong(KEY, code(ctx)).apply()
}

/** Экран «Добро пожаловать / Обновлено»: заголовок, пункты с значками (въезжают по очереди), большая кнопка. */
@Composable
fun WhatsNewScreen(firstRun: Boolean, version: String, onDone: () -> Unit) = AppearScope("news") {
    Column(
        Modifier.fillMaxSize().background(Color(0xF70A0A0D)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        run {
            Appear { PhoneSilhouette(Modifier.size(96.dp)) }
            Spacer(Modifier.height(16.dp))
            Appear {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (firstRun) s(R.string.news_welcome) else s(R.string.news_updated), style = t(34, FontWeight.SemiBold))
                    Text(if (firstRun) "derealization $version" else s(R.string.news_whats_new, version), style = t(15, c = Dim), modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                NEWS.forEach { n ->
                    Appear {
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Surface2).border(1.dp, Line, RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center) { NewsGlyph(n.icon, Modifier.size(24.dp)) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.title, style = t(16, FontWeight.Medium))
                                Text(n.text, style = t(13, c = Dim), modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp).height(60.dp).clip(CircleShape).background(Color.White).clickable(onClick = onDone),
            contentAlignment = Alignment.Center) { Text(s(R.string.action_continue), style = t(18, FontWeight.SemiBold, Color.Black)) }
    }
}

@Composable
private fun NewsGlyph(icon: NewsIcon, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Color.White
        val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
        when (icon) {
            NewsIcon.Motion -> {                     // окно вылетает из точки
                drawCircle(c, w * 0.08f, Offset(w * 0.18f, h * 0.82f))
                drawRoundRect(c, Offset(w * 0.38f, h * 0.12f), Size(w * 0.48f, h * 0.62f), CornerRadius(w * 0.1f), style = st)
                drawLine(c, Offset(w * 0.26f, h * 0.72f), Offset(w * 0.4f, h * 0.58f), w * 0.07f, StrokeCap.Round)
            }
            NewsIcon.Blur -> {                       // размытые круги
                drawCircle(c.copy(alpha = 0.35f), w * 0.34f, Offset(w * 0.4f, h * 0.45f))
                drawCircle(c.copy(alpha = 0.6f), w * 0.24f, Offset(w * 0.6f, h * 0.58f))
                drawCircle(c, w * 0.12f, Offset(w * 0.62f, h * 0.6f))
            }
            NewsIcon.Drawer -> for (i in 0..2) for (j in 0..2)    // сетка значков
                drawRoundRect(c, Offset(w * (0.1f + i * 0.3f), h * (0.1f + j * 0.3f)), Size(w * 0.2f, h * 0.2f), CornerRadius(w * 0.06f))
            NewsIcon.Shade -> {                      // шторка сверху
                drawRoundRect(c, Offset(w * 0.12f, h * 0.1f), Size(w * 0.76f, h * 0.4f), CornerRadius(w * 0.12f))
                drawLine(c, Offset(w * 0.3f, h * 0.68f), Offset(w * 0.7f, h * 0.68f), w * 0.08f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.38f, h * 0.84f), Offset(w * 0.62f, h * 0.84f), w * 0.08f, StrokeCap.Round)
            }
            NewsIcon.Home -> {                       // телефон
                drawRoundRect(c, Offset(w * 0.28f, h * 0.06f), Size(w * 0.44f, h * 0.88f), CornerRadius(w * 0.1f), style = st)
                drawCircle(c, w * 0.06f, Offset(w * 0.42f, h * 0.22f))
            }
            NewsIcon.Sheet -> {                      // лист снизу
                drawRoundRect(c.copy(alpha = 0.4f), Offset(w * 0.18f, h * 0.08f), Size(w * 0.64f, h * 0.5f), CornerRadius(w * 0.1f))
                drawRoundRect(c, Offset(w * 0.08f, h * 0.36f), Size(w * 0.84f, h * 0.62f), CornerRadius(w * 0.14f))
            }
            NewsIcon.Resize -> {                     // значок, растянутый в капсулу
                drawRoundRect(c.copy(alpha = 0.35f), Offset(w * 0.06f, h * 0.3f), Size(w * 0.88f, h * 0.4f), CornerRadius(h * 0.2f))
                drawCircle(c, w * 0.17f, Offset(w / 2, h / 2))
                drawLine(c, Offset(w * 0.12f, h * 0.85f), Offset(w * 0.88f, h * 0.85f), w * 0.07f, StrokeCap.Round)
            }
            NewsIcon.Split -> {                      // шторка из двух половин
                drawRoundRect(c, Offset(w * 0.08f, h * 0.1f), Size(w * 0.38f, h * 0.62f), CornerRadius(w * 0.1f), style = st)
                drawRoundRect(c, Offset(w * 0.54f, h * 0.1f), Size(w * 0.38f, h * 0.36f), CornerRadius(w * 0.1f))
            }
            NewsIcon.Clock -> {                      // циферблат
                drawCircle(c, w * 0.4f, Offset(w / 2, h / 2), style = st)
                drawLine(c, Offset(w / 2, h / 2), Offset(w / 2, h * 0.26f), w * 0.08f, StrokeCap.Round)
                drawLine(c, Offset(w / 2, h / 2), Offset(w * 0.68f, h * 0.6f), w * 0.08f, StrokeCap.Round)
            }
        }
    }
}
