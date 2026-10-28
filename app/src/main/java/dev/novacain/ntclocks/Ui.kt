package dev.novacain.ntclocks

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Оформление, общее для всех элементов настроек. Патчер (MainActivity) — стекло в духе Dopamine; настройки внутри
 * «Обоев и стиля» (SettingsActivity) — как сами «Обои и стиль»: палитра Material You системы (те же тёплые карточки,
 * акцент и шрифт), без кромок. Экран выбирает оформление до setContent.
 */
internal object Ui {
    var bg = Color(0x40000000)          // «вдавленное» (подложка переключателя на карточке)
    var surface = Color(0x17FFFFFF)     // карточка
    var surface2 = Color(0x29FFFFFF)    // приподнятое
    var line = Color(0x24FFFFFF)        // кромка
    var text = Color.White
    var dim = Color(0xA6FFFFFF)
    var accent = Color(0xFFF0471E)
    var selected = Color.White          // выбранный сегмент / главная кнопка
    var onSelected = Color.Black
    var thumbOff = Color.White.copy(alpha = 0.7f)
    var trackOff = Color(0x40000000)
    var font: FontFamily? = null        // null — Geist
    var picker = false

    fun dopamine() {
        bg = Color(0x40000000); surface = Color(0x17FFFFFF); surface2 = Color(0x29FFFFFF); line = Color(0x24FFFFFF)
        text = Color.White; dim = Color(0xA6FFFFFF); accent = Color(0xFFF0471E)
        selected = Color.White; onSelected = Color.Black; thumbOff = Color.White.copy(alpha = 0.7f); trackOff = Color(0x40000000)
        font = null; picker = false
    }

    /** Как «Обои и стиль»: цвета Material You (тёмная тема) из системы. */
    fun picker(ctx: Context) {
        fun c(id: Int) = Color(ctx.getColor(id))
        bg = c(android.R.color.system_neutral1_900)
        surface = c(android.R.color.system_neutral2_800)
        surface2 = c(android.R.color.system_neutral2_700)
        line = Color.Transparent
        text = c(android.R.color.system_neutral1_50)
        dim = c(android.R.color.system_neutral2_200)
        accent = c(android.R.color.system_accent1_200)
        selected = c(android.R.color.system_accent1_200)
        onSelected = c(android.R.color.system_accent1_800)
        thumbOff = c(android.R.color.system_neutral2_400)
        trackOff = c(android.R.color.system_neutral1_900)
        font = FontFamily.Default
        picker = true
    }
}
