package dev.novacain.ntclocks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Отложенное сохранение настроек (пока ползунок движется, пишем не на каждый кадр). Живёт вне экрана: смена вкладки
 * или выход из приложения не отменяют запись, а при уходе в фон (onPause) всё ожидающее пишется сразу.
 */
internal object Save {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashMap<String, Pair<Job, () -> Unit>>()

    fun later(key: String, delayMs: Long = 200, block: () -> Unit) {
        synchronized(pending) {
            pending.remove(key)?.first?.cancel()
            val job = scope.launch {
                delay(delayMs)
                val run = synchronized(pending) { pending.remove(key)?.second } ?: return@launch
                runCatching(run)
            }
            pending[key] = job to block
        }
    }

    fun flush() {
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        all.forEach { (job, block) -> job.cancel(); runCatching(block) }
    }
}
