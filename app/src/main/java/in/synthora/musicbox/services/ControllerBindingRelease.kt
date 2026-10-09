package `in`.synthora.musicbox.services

/** Ignore only Android's absent-binding cleanup error; surface all other failures. */
internal fun releaseControllerBinding(unbind: () -> Unit, alreadyUnbound: () -> Unit) {
    try {
        unbind()
    } catch (error: IllegalArgumentException) {
        if (error.message?.startsWith("Service not registered:") != true) throw error
        alreadyUnbound()
    }
}
