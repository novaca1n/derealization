package dev.novacain.ntclocks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Отступ листа от верха: под ним видна уменьшенная главная — понятно, куда вернёшься (как в cmf link). */
val SheetTop = 14.dp

/**
 * Лист поверх главной (движение — как листы устройств в cmf link). Закрывается: тапом по главной над ним, свайпом вниз
 * за ручку или свайпом вниз, когда содержимое прокручено к началу. Отпущенный не до конца — пружиной назад.
 */
@Composable
fun Sheet(openId: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    LaunchedEffect(openId) { if (drag.value != 0f) drag.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
    val density = LocalDensity.current
    val limit = with(density) { 140.dp.toPx() }
    fun release(velocity: Float) {
        if (drag.value > limit || velocity > 2200f) onDismiss()
        else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)) }
    }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (drag.value > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -drag.value)
                    scope.launch { drag.snapTo(drag.value + used) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    scope.launch { drag.snapTo(drag.value + available.y * 0.6f) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.value > 0f) { release(available.y); return available }
                return Velocity.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(SheetTop))
        }
        Column(
            Modifier.fillMaxSize()
                .padding(top = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + SheetTop)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Color(0xFF111115))
                .nestedScroll(nested),
        ) {
            Box(
                Modifier.fillMaxWidth().height(26.dp).draggable(
                    rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                    Orientation.Vertical, onDragStopped = { v -> release(v) },
                ),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) }
            content()
        }
    }
}

/** Порядковые номера карточек на только что открытом экране — для очереди «въезда» (Appear). */
class AppearOrder { var next = 0; val opened = System.nanoTime() }
val LocalAppear = staticCompositionLocalOf<AppearOrder?> { null }

/** Новая очередь въезда для экрана (вкладки): карточки внутри въезжают снизу по очереди. */
@Composable
fun AppearScope(key: Any, content: @Composable () -> Unit) {
    val order = remember(key) { AppearOrder() }
    CompositionLocalProvider(LocalAppear provides order) { content() }
}

/** Карточка въезжает снизу по очереди (как пункты в cmf link); появившиеся позже (при прокрутке) — сразу на месте. */
@Composable
fun Appear(content: @Composable () -> Unit) {
    val order = LocalAppear.current
    val index = remember { order?.let { it.next++ } ?: 0 }
    val late = order == null || System.nanoTime() - order.opened > 400_000_000L
    val a = remember { Animatable(if (late) 1f else 0f) }
    LaunchedEffect(Unit) { if (!late) a.animateTo(1f, tween(420, delayMillis = 50 * index.coerceAtMost(5), easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40.dp.toPx(); compositingStrategy = CompositingStrategy.ModulateAlpha }) { content() }
}
