package dev.novacain.ntclocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

/**
 * Отладка виджета музыки без звука (закрыто DUMP): тихая медиасессия с названием и обложкой, кнопки пишутся в лог.
 * am broadcast -n dev.novacain.ntclocks/.DebugMedia --ez on true|false
 */
class DebugMedia : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!intent.getBooleanExtra("on", true)) {
            session?.release(); session = null
            return
        }
        val s = session ?: MediaSession(ctx.applicationContext, "derealization-debug").also { session = it }
        fun state(playing: Boolean) = s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f).build())
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { Log.i("derealization", "debug media: play"); state(true) }
            override fun onPause() { Log.i("derealization", "debug media: pause"); state(false) }
            override fun onSkipToNext() { Log.i("derealization", "debug media: next") }
            override fun onSkipToPrevious() { Log.i("derealization", "debug media: previous") }
        })
        val art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 256f, 256f, Paint().apply {
                shader = LinearGradient(0f, 0f, 256f, 256f, 0xFFF0471E.toInt(), 0xFF2B1B6E.toInt(), Shader.TileMode.CLAMP)
            })
        }
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Тихий трек")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "derealization")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build())
        state(true)
        s.isActive = true
    }

    companion object {
        private var session: MediaSession? = null
    }
}
