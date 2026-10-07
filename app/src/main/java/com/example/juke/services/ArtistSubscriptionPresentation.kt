package com.example.juke.services

import com.example.juke.network.BrowseItem

data class ArtistSubscriptionChange(val artist: BrowseItem, val subscribed: Boolean)

/** YouTube library reads can lag accepted mutations; reconcile without a stale reappearance. */
class ArtistSubscriptionPresentation {
    private data class Pending(val change: ArtistSubscriptionChange, val until: Long)
    private val pending = linkedMapOf<String, Pending>()
    @Synchronized fun accepted(change: ArtistSubscriptionChange, now: Long) {
        pending[change.artist.id] = Pending(change, now + 120_000)
    }
    @Synchronized fun present(artists: List<BrowseItem>, now: Long, confirmSnapshot: Boolean = true): List<BrowseItem> {
        val result = artists.toMutableList()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, value) = iterator.next()
            val present = artists.any { it.id == id }
            if ((confirmSnapshot && present == value.change.subscribed) || now >= value.until) {
                iterator.remove()
                continue
            }
            if (value.change.subscribed) result.add(0, value.change.artist)
            else result.removeAll { it.id == id }
        }
        return result
    }
    @Synchronized fun clear() = pending.clear()
}
