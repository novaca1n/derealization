package dev.novacain.ntclocks.qs

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитки «Яркость» и «Громкость» для быстрых настроек. Сами по себе ничего не делают: благодаря им плитки появляются
 * в списке редактирования Nothing, а модуль (QsSliders в SystemUI) рисует на их месте вертикальный ползунок 1×2.
 */
open class SliderTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
    }
}

class BrightnessTile : SliderTile()
class VolumeTile : SliderTile()
