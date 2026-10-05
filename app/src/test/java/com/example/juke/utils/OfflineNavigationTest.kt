package com.example.juke.utils

import org.junit.Assert.*
import org.junit.Test

class OfflineNavigationTest {
    @Test fun connectivityGateKeepsDownloadedCollectionsAccessibleWithoutExposingNetworkPages() {
        for (route in listOf("library", "downloads/{collectionKey}", "downloads/album%3AMPRE123", "downloads/playlist%3APL123", "settings"))
            assertTrue(route, allowsOfflineBrowsing(route))
        for (route in listOf("home", "search", "album/{albumId}", "playlist/{playlistId}", "artist/{artistId}", "history"))
            assertFalse(route, allowsOfflineBrowsing(route))
        assertFalse(allowsOfflineBrowsing(null))
    }
}
