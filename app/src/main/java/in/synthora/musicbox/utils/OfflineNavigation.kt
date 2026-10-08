package `in`.synthora.musicbox.utils

/** Downloaded detail routes stay usable beneath the app-wide connectivity gate. */
fun allowsOfflineBrowsing(route: String?): Boolean = route == "library" || route?.startsWith("downloads/") == true || route?.startsWith("settings") == true
