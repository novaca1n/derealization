package dev.novacain.ntclocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Отладка всплывающего уведомления (закрыто DUMP): своё уведомление с каналом высокой важности — проверять анимацию
 * появления, не трогая чужие приложения. am broadcast -n dev.novacain.ntclocks/.DebugHeadsUp --ez on true|false
 */
class DebugHeadsUp : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!intent.getBooleanExtra("on", true)) {
            nm.cancel(ID)
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Проверка анимаций", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("derealization")
            .setContentText("Проверка появления уведомления")
            .setAutoCancel(true)
            .build())
    }

    private companion object {
        const val CHANNEL = "debug_headsup"
        const val ID = 4242
    }
}
