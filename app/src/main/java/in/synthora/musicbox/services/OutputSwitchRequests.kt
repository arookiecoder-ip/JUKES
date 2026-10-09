package `in`.synthora.musicbox.services

internal data class OutputSelection(val serial: String?)

/** Called on the main thread: run one transfer and retain only the latest subsequent choice. */
internal class OutputSwitchRequests {
    private var running = false
    private var pending: OutputSelection? = null
    fun request(serial: String?): Boolean {
        if (running) { pending = OutputSelection(serial); return false }
        running = true
        return true
    }
    fun finish(): OutputSelection? {
        running = false
        return pending.also { pending = null }
    }
    fun clearPending() { pending = null }
}
