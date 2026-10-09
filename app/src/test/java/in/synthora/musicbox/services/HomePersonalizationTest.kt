package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.viewmodels.HomeShelf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class HomePersonalizationTest {
    private fun track(id: String, artist: String = "Artist") = Track(id, id, artist, durationSec = 180, ytVideoId = id)
    private fun shelf(tracks: List<Track>) = HomeShelf("shelf", "For you", "songs", tracks.map {
        BrowseParser.item(Json.parseToJsonElement("""{"videoId":"${it.ytVideoId}","title":"${it.title}"}""").jsonObject)
    }, tracks)
    @Test fun speedDialPrefersRecentSongsAndDeduplicatesRecommendationCopies() {
        val recent = track("recent")
        val items = speedDialItems(listOf(shelf(listOf(track("new"), recent))), listOf(recent, recent))
        assertEquals(listOf("recent", "new"), items.map { it.videoId })
    }
    @Test fun dialIsBoundedAndUnplayableHistoryIsExcluded() {
        val items = speedDialItems(listOf(shelf((1..30).map { track("song$it") })), listOf(track("local").copy(ytVideoId = null)))
        assertEquals(8, items.size)
        assertFalse(items.any { it.id == "local" })
    }
    @Test fun albumsAndPlaylistsAreKeptButArtistsAreExcludedFromPlaybackDial() {
        val items = listOf("album", "playlist", "artist").map {
            BrowseParser.item(Json.parseToJsonElement("""{"kind":"$it","id":"$it","title":"$it"}""").jsonObject)
        }
        assertEquals(listOf("album", "playlist"), speedDialItems(listOf(HomeShelf("s", "s", "", items, emptyList())), emptyList()).map { it.kind })
    }
    @Test fun radioAvoidsRepeatingCurrentSongWhenAnotherSeedExists() {
        val songs = listOf(track("one"), track("two"))
        repeat(20) { assertEquals("two", chooseHomeRadioSeed(listOf(shelf(songs)), emptyList(), emptySet(), "one", Random(it))?.ytVideoId) }
    }
    @Test fun likesAndFamiliarArtistsBiasPersonalizedSeeds() {
        val songs = listOf(track("liked", "Familiar"), track("other", "New artist"))
        val random = Random(42)
        val picks = (1..1_000).count {
            chooseHomeRadioSeed(listOf(shelf(songs)), listOf(track("history", "Familiar")), setOf("liked"), null, random)?.ytVideoId == "liked"
        }
        assertTrue(picks > 500)
    }
    @Test fun emptyFeedCanUseHistoryButMissingSeedsNeverCrash() {
        assertEquals("history", chooseHomeRadioSeed(emptyList(), listOf(track("history")), emptySet(), null)?.ytVideoId)
        assertNull(chooseHomeRadioSeed(emptyList(), listOf(track("local").copy(ytVideoId = null)), emptySet(), null))
        assertEquals("only", chooseHomeRadioSeed(emptyList(), listOf(track("only")), emptySet(), "only")?.ytVideoId)
    }
}
