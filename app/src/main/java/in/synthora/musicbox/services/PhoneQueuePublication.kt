package `in`.synthora.musicbox.services

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Mirror the song immediately; recommendation lookup must not gate the shared cursor. */
internal suspend fun <T> publishPhoneQueueInStages(initial: T, publish: suspend (T) -> Unit,
    expand: suspend (T) -> T) {
    publish(initial)
    currentCoroutineContext().ensureActive()
    val expanded = expand(initial)
    currentCoroutineContext().ensureActive()
    if (expanded != initial) publish(expanded)
}
