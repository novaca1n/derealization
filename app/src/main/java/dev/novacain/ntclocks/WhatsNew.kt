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

/** Что нового в текущей версии (показывается один раз после установки или обновления). */
val NEWS = listOf(
    News(NewsIcon.Home, "Гравитация на столе", "Встряхни телефон на рабочем столе — значки, папки и виджеты падают и складываются в кучу, наклон телефона их пересыпает. Значки можно хватать и бросать, касание открывает. Ещё раз встряхнуть или «домой» — всё на места. Включается в «Анимациях»"),
    News(NewsIcon.Motion, "Панель громкости как на iPhone", "Стеклянная капсула у края экрана: выезжает пружиной, вздрагивает на каждое нажатие, через секунду сжимается в тонкую линию и уезжает. Громкость можно тянуть пальцем, на краях капсула тянется как резина. Включается в «Анимациях» → «Панель громкости» → iPhone"),
    News(NewsIcon.Motion, "Недавние — карусель с глубиной", "В переключателе приложений карточки по бокам меньше и тусклее и плавно вырастают, подъезжая к центру, — как в iOS"),
    News(NewsIcon.Shade, "Уведомление как на iPhone", "Всплывающее уведомление падает целиком из-за верхнего края, садится с упругим перелётом и так же улетает вверх, когда прячется"),
    News(NewsIcon.Motion, "Меню, папки и шторка оживают", "Меню приложений выплывает рядами за пальцем, значки папки раскрываются каскадом, а шторку можно потянуть дальше конца — она тянется как резина и пружинит обратно"),
    News(NewsIcon.Home, "Живой стол", "Окно садится в значок — по столу пробегает едва заметная волна. После разблокировки значки опускаются на места ряд за рядом"),
    News(NewsIcon.Motion, "Шторка без рывка", "Окно шторки готовится уже в момент касания строки состояния — первый кадр шторки в 3–5 раз быстрее"),
    News(NewsIcon.Motion, "Резиновая громкость", "На максимуме или минимуме очередное нажатие упруго вытягивает ползунок за край — как в ColorOS"),
    News(NewsIcon.Motion, "Окно ведёт дальний край", "Значок внизу экрана — окно сначала дотягивается вверх, значок вверху — вниз; и при открытии, и при закрытии. Посередине края идут вровень"),
    News(NewsIcon.Motion, "Ровнее кадры", "Размытие стола теперь считается на уменьшенной копии — лаунчер рисует кадр вдвое быстрее; шторка теряет на четверть меньше кадров, чем у Nothing без модуля"),
    News(NewsIcon.Motion, "Вся система — ColorOS 17", "Пресет «ColorOS 17» теперь меняет не только окна: папки раскрываются пружинами ColorOS, панель громкости вырастает от края с отскоком, плитки упруго сжимаются под пальцем, после разблокировки стол опускается на место, а часы остаются"),
    News(NewsIcon.Home, "Док-стекло заработало", "Стекло под значками дока не рисовалось вовсе — хук висел на методе, которого у дока нет. Теперь рисуется и размывает обои само, без нагрузки на композитор"),
    News(NewsIcon.Motion, "Плавнее", "Прозрачные папки, виджеты и док размывают обои сами — системному композитору больше не нужно пересчитывать размытие каждый кадр. Открытая папка садится в полупрозрачный значок без скачка"),
    News(NewsIcon.Sheet, "Виджеты в Material 3", "Цвета системы, волнистое кольцо шагов, обложка-«печенье», волнистый прогресс трека и кнопка, меняющая форму"),
    News(NewsIcon.Resize, "Растягивание за угол", "Долгое нажатие на значок или папку — рамка с ручкой, тяни за угол, как виджет. Папка — ещё и капсулой 2×1"),
    News(NewsIcon.Home, "Стол и док", "Док-стекло как в iOS 26 с подпрыгиванием значка, подписи без дока или шрифтом Ndot, два пальца вниз — быстрые настройки"),
    News(NewsIcon.Motion, "Разблокировка как в iOS", "Свайп вверх или отпечаток — часы и виджеты уезжают вверх и растворяются, объект с обоев остаётся"),
    News(NewsIcon.Clock, "Своя маска глубины", "Любое фото на экран блокировки: объект выделится сам, поправь пальцем — и он встанет поверх часов. Видно настоящие часы и линию подрезки"),
    News(NewsIcon.Sheet, "Раздел «Глубина»", "В «Обоях и стиле» → экран блокировки: своя маска, глубина без запретов Nothing, подрезка под большие часы"),
    News(NewsIcon.Motion, "Патчер как Dopamine", "Меню-карточка, лист с журналом «как текст песни», затемнение в конце; фон — акцентные цвета системы"),
    News(NewsIcon.Sheet, "Безопасный режим", "Упало три раза подряд — derealization в этом процессе сам выключится; «Патч» вернёт"),
    News(NewsIcon.Home, "«О телефоне» с карточкой", "Живой фон в цветах системы, Nothing OS и версия, patched with derealization"),
    News(NewsIcon.Home, "Теперь это derealization", "Приложение — только патчер: «Патч» внедряет код в SystemUI, лаунчер и «Обои и стиль», «Снять патч» возвращает всё как у Nothing"),
    News(NewsIcon.Sheet, "Настройки — в «Обоях и стиле»", "Главный экран: «Анимации», «Стекло», «Цвета»; экран блокировки: «Часы», «Стекло»"),
    News(NewsIcon.Resize, "Значки растягиваются как виджеты", "Долгое нажатие → «Увеличить»: 2×1, 3×1, 4×1, 1×2, 1×3 или 2×2"),
    News(NewsIcon.Split, "Раздельная шторка, как в NOS 5", "Свайп из правой половины — быстрые настройки, из левой — уведомления"),
    News(NewsIcon.Shade, "Шторка и меню в стиле iOS", "Панель мягко догоняет палец, меню приложений наплывает"),
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
                    Text(if (firstRun) "Добро пожаловать" else "Обновлено", style = t(34, FontWeight.SemiBold))
                    Text(if (firstRun) "derealization $version" else "derealization $version — что нового", style = t(15, c = Dim), modifier = Modifier.padding(top = 4.dp))
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
            contentAlignment = Alignment.Center) { Text("Продолжить", style = t(18, FontWeight.SemiBold, Color.Black)) }
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
