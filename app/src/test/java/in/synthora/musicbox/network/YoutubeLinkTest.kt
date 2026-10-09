package `in`.synthora.musicbox.network

import org.junit.Assert.*
import org.junit.Test

class YoutubeLinkTest {
    @Test fun supportedSongUrlsResolveExactVideoIdentity() {
        for (url in listOf("https://music.youtube.com/watch?v=abcdefghijk&si=share", "https://youtu.be/abcdefghijk?t=30", "https://www.youtube.com/shorts/abcdefghijk", "https://youtube.com/embed/abcdefghijk"))
            assertEquals("abcdefghijk", youtubeLink(url)?.videoId)
    }
    @Test fun playlistUrlsAreRecognizedWithoutOverridingSongUrls() {
        assertEquals("PLfixture123", youtubeLink("https://music.youtube.com/playlist?list=PLfixture123")?.playlistId)
        assertEquals("abcdefghijk", youtubeLink("https://youtube.com/watch?v=abcdefghijk&list=PLfixture123")?.videoId)
    }
    @Test fun pastedTextSpoofedHostsAndIncompleteIdsDoNotStartPlayback() {
        for (url in listOf("song name", "https://youtube.com.evil.test/watch?v=abcdefghijk", "https://evil.test/watch?v=abcdefghijk", "https://youtube.com/watch?v=abc", "javascript:alert(1)", "https://youtube.com/watch?v=abcdefghijkAAAA")) assertNull(youtubeLink(url))
    }
}
