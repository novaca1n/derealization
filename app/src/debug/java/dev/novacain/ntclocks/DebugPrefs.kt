package dev.novacain.ntclocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.novacain.ntclocks.faces.ClockPrefs

/**
 * Отладка из adb (закрыто разрешением DUMP — обычным приложениям недоступно):
 * am broadcast -n dev.novacain.ntclocks/.DebugPrefs --es id NC_STRETCH --es json '{"stretchY":3}'
 * Переданные ключи накладываются поверх текущих настроек.
 */
class DebugPrefs : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val patch = org.json.JSONObject(intent.getStringExtra("json") ?: "{}")
        val cur = org.json.JSONObject(Store.json(ctx, id))
        patch.keys().forEach { cur.put(it, patch.get(it)) }
        Store.save(ctx, id, ClockPrefs.fromJson(cur.toString(), dev.novacain.ntclocks.faces.Clocks.defaults(id)))
        Log.i("NtClocks", "отладка: $id ← $patch")
    }
}
