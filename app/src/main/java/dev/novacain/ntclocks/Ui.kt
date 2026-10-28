package dev.novacain.ntclocks

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * Одно оформление на всё приложение — Material 3 Expressive на цветах системы (как TweakStore): динамическая палитра,
 * выразительные пружины, системный шрифт. Патчер оставляет раскладку и жесты Dopamine (заголовок, карточка меню, кнопка,
 * разворачивающаяся в журнал), но берёт цвета и формы отсюда; настройки — компоненты Material 3 Expressive.
 * Слоты ниже — старые имена цветов, на которые опираются все вкладки; их заполняет [DereTheme] из ColorScheme.
 */
internal object Ui {
    var bg = Color(0x40000000)          // «вдавленное» внутри карточки
    var surface = Color(0x17FFFFFF)     // карточка
    var surface2 = Color(0x29FFFFFF)    // приподнятое
    var line = Color.Transparent        // кромка (в Material не нужна)
    var text = Color.White
    var dim = Color(0xA6FFFFFF)
    var accent = Color(0xFFF0471E)
    var selected = Color.White
    var onSelected = Color.Black
    var thumbOff = Color.White.copy(alpha = 0.7f)
    var trackOff = Color(0x40000000)
    var font: FontFamily? = FontFamily.Default
    var warn = Color(0xFFFFD27A)        // предупреждения в шапке патчера
    var onAccent = Color.Black
    var raised = Color(0x29FFFFFF)      // лист/диалог над карточкой

    fun material(c: ColorScheme) {
        bg = c.surfaceContainerHighest; surface = c.surfaceContainer; surface2 = c.surfaceContainerHigh
        line = Color.Transparent; text = c.onSurface; dim = c.onSurfaceVariant; accent = c.primary
        selected = c.primary; onSelected = c.onPrimary; thumbOff = c.outline; trackOff = c.surfaceContainerHighest
        font = FontFamily.Default
        warn = c.tertiary; onAccent = c.onPrimary; raised = c.surfaceContainerHigh
    }
}

/** Тема приложения. dark = null — как в системе (настройки); патчер всегда тёмный: его фон — живые пятна цвета. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DereTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx: Context = LocalContext.current
    val isDark = dark ?: isSystemInDarkTheme()
    val scheme = if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    Ui.material(scheme)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
