package `in`.synthora.musicbox.services

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class HomeFeedCacheTest {
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("home-feed-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val feed = """{"shelves":[{"title":"Recommendations","items":[{"videoId":"song","title":"Song"}]}]}"""

    @Test fun reopeningCacheRestoresFeedWithoutFetchingItAgain() {
        val prefs = preferences()
        HomeFeedCache(prefs).save(feed, 1_000)
        val restored = requireNotNull(HomeFeedCache(prefs).read())
        assertEquals(feed, restored.feed.toString())
        assertTrue(restored.isFresh(1_001))
    }
    @Test fun expiryRetainsFeedForBackgroundRefreshButMarksItStale() {
        val prefs = preferences()
        HomeFeedCache(prefs).save(feed, 1_000)
        val restored = requireNotNull(HomeFeedCache(prefs).read())
        assertTrue(restored.isFresh(1_000 + HOME_CACHE_TTL_MS - 1))
        assertFalse(restored.isFresh(1_000 + HOME_CACHE_TTL_MS))
        assertEquals(feed, restored.feed.toString())
    }
    @Test fun accountInvalidationRemovesFeedAndTimestamp() {
        val prefs = preferences()
        val cache = HomeFeedCache(prefs)
        cache.save(feed, 1_000); cache.clear()
        assertNull(HomeFeedCache(prefs).read())
        assertTrue(prefs.all.isEmpty())
    }
    @Test fun successfulRefreshReplacesFeedAndRenewsExpiry() {
        val cache = HomeFeedCache(preferences())
        cache.save(feed, 1_000)
        val updated = feed.replace("Recommendations", "New recommendations")
        cache.save(updated, 2_000)
        val restored = requireNotNull(cache.read())
        assertEquals(updated, restored.feed.toString())
        assertEquals(2_000L, restored.savedAt)
    }
    @Test fun corruptCacheIsDiscardedSafely() {
        val prefs = preferences()
        for (raw in listOf("broken", "[]", "null")) {
            prefs.edit().putString("feed", raw).putLong("saved_at", 1_000).commit()
            assertNull(HomeFeedCache(prefs).read())
        }
    }
    @Test fun clockRollbackAndMissingTimestampCannotKeepCacheFreshForever() {
        assertFalse(isHomeFeedFresh(1_000, 999))
        assertFalse(isHomeFeedFresh(0, 1_000))
        val prefs = preferences()
        prefs.edit().putString("feed", feed).commit()
        assertNull(HomeFeedCache(prefs).read())
    }
}
