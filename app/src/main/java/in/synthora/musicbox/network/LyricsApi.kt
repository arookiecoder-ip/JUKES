package `in`.synthora.musicbox.network

import `in`.synthora.musicbox.utils.SafeLog as Log
import `in`.synthora.musicbox.models.LRCLibResult
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlin.math.abs

/**
 * Lyrics lookup: LRCLib first, then YouTube captions and YouTube Music lyrics for the track's
 * video id.
 */
object LyricsApi {

    private const val TAG = "LyricsApi"
    private const val LRCLIB_BASE_URL = "https://lrclib.meek.workers.dev"
    private const val YTM_BASE_URL = "https://music.youtube.com"
    private const val YT_BASE_URL  = "https://www.youtube.com"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // LRCLib is a free community service: cap concurrent calls and back off when it says it is busy,
    // instead of retrying (and fanning out into fallback searches) while it is overloaded.
    private val lrclibGate = Semaphore(2)
    @Volatile private var lrclibBlockedUntil = 0L
    private var lrclibBackoffMs = 0L

    /** Body of one LRCLib request, or null when skipped/failed because the service is busy. */
    private suspend fun lrclibGet(block: HttpRequestBuilder.() -> Unit): String? {
        if (System.currentTimeMillis() < lrclibBlockedUntil) return null
        return lrclibGate.withPermit {
            if (System.currentTimeMillis() < lrclibBlockedUntil) return@withPermit null
            val response = ApiClient.httpClient.get(LRCLIB_BASE_URL, block)
            val raw = response.bodyAsText()
            val code = response.status.value
            // Success is a JSON array; an object with statusCode is LRCLib's error envelope.
            val busy = code == 429 || code >= 500 ||
                    (raw.trimStart().startsWith("{") && raw.contains("\"statusCode\""))
            if (busy) {
                lrclibBackoffMs = (if (lrclibBackoffMs == 0L) 5_000L else lrclibBackoffMs * 2).coerceAtMost(120_000L)
                lrclibBlockedUntil = System.currentTimeMillis() + lrclibBackoffMs
                Log.w(TAG, "LRCLib busy ($code); pausing requests for ${lrclibBackoffMs / 1000}s")
                null
            } else {
                lrclibBackoffMs = 0L
                raw
            }
        }
    }

    /**
     * Search for lyrics on LRCLib using specific query parameters.
     * 
     * LRCLib provides both plain text and synced (LRC format) lyrics.
     * Uses track_name, artist_name, and album_name parameters for exact matching.
     * Validates lyrics match against provided track details.
     * 
     * @param title Song title
     * @param artist Artist name
     * @param album Album name (optional)
     * @param duration Optional duration in seconds for better matching
     * @return LRCLibResult or null if not found or doesn't match
     */
    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String = "",
        duration: Int? = null,
        ytVideoId: String? = null
    ): LRCLibResult? {
        return try {
            // 1. Clean Title to remove (From ...) or (feat ...) metadata
            val cleanedTitle = cleanSongTitle(title)
            Log.d(
                TAG,
                "LRCLib Search: $LRCLIB_BASE_URL?track=$cleanedTitle&artist=$artist (Original: $title)"
            )

            val raw = lrclibGet {
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
                )
                parameter("track", cleanedTitle)
                parameter("artist", artist)
                if (album.isNotBlank()) {
                    parameter("album", album)
                }
            }
            val lrclibBusy = raw == null
            Log.d(TAG, "LRCLib Response: ${raw?.take(1000) ?: "skipped (busy)"}")

            val results: List<LRCLibResult> = try {
                if (raw == null) emptyList() else json.decodeFromString(raw)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode LRCLib response: ${e.message}")
                emptyList()
            }

            // Find the best matching result based on validation score AND duration proximity AND synced lyrics availability
            var bestMatch = if (results.isNotEmpty()) {
                results
                    .map { result ->
                        val score = validateLyricsMatch(result, title, artist, duration)
                        val durationDiff =
                            if (duration != null) abs(result.duration - duration) else Double.MAX_VALUE
                        !result.syncedLyrics.isNullOrBlank()
                        Triple(result, score, durationDiff)
                    }
                    .filter { it.second > 0 } // Only consider results with some match
                    .sortedWith(
                        compareByDescending<Triple<LRCLibResult, Int, Double>> { it.second } // 1. Highest Score
                            .thenByDescending { it.first.syncedLyrics?.isNotBlank() == true }    // 2. Has Synced Lyrics
                            .thenBy { it.third } // 3. Lowest Duration Difference
                    )
                    .firstOrNull()
                    ?.first
            } else null

            // 2. Fallback Search (Individual Artists)
            if (bestMatch == null && !lrclibBusy) {
                val separators = charArrayOf(',', '&')
                val individualArtists = artist.split(*separators)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }

                if (individualArtists.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Primary lyrics search failed. Attempting fallback for artists: $individualArtists"
                    )

                    for (singleArtist in individualArtists) {
                        try {
                            val fallbackRaw = lrclibGet {
                                header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
                                )
                                parameter("track", cleanedTitle)
                                parameter("artist", singleArtist)
                            } ?: break // LRCLib went busy: stop the per-artist fan-out

                            val fallbackResults: List<LRCLibResult> = json.decodeFromString(fallbackRaw)

                            // Strict Validation for Fallback
                            // Duration must be within 5% tolerance
                            // Find ALL valid candidates, then pick based on criteria
                            val validFallback = fallbackResults
                                .filter { result ->
                                    val resultTitle = result.trackName.lowercase().trim()
                                    val normalizedTitle = title.lowercase().trim()
                                    val titleMatch =
                                        resultTitle.contains(normalizedTitle) || normalizedTitle.contains(
                                            resultTitle
                                        )

                                    val durationMatch = if (duration != null && duration > 0) {
                                        val tolerance = duration * 0.05 // 5% tolerance
                                        val diff = abs(result.duration - duration)
                                        diff <= tolerance
                                    } else {
                                        true
                                    }

                                    titleMatch && durationMatch
                                }
                                .sortedWith(
                                    compareByDescending<LRCLibResult> { !it.syncedLyrics.isNullOrBlank() } // 1. Has Synced Lyrics
                                        .thenBy { if (duration != null) abs(it.duration - duration) else 0.0 } // 2. Closest Duration
                                )
                                .firstOrNull()

                            if (validFallback != null) {
                                Log.d(TAG, "Fallback lyrics found with artist '$singleArtist'")
                                bestMatch = validFallback
                                break // Stop if we found a good match
                            }
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "Fallback search failed for artist '$singleArtist': ${e.message}"
                            )
                        }
                    }
                }
            }

            // ── Priority chain ────────────────────────────────────────────────
            // LRCLib synced > YT Captions synced > LRCLib plain > YTM plain > YT Captions plain

            // Tier 1: LRCLib synced lyrics — return immediately if found
            if (bestMatch?.syncedLyrics?.isNotBlank() == true) {
                Log.d(TAG, "Tier 1: LRCLib synced lyrics returned for: $title")
                return bestMatch
            }

            // Every account track carries its YouTube video id.
            val resolvedVideoId = ytVideoId

            if (resolvedVideoId != null) {
                Log.d(TAG, "Checking YT sources (videoId=$resolvedVideoId) for: $title")

                // Tier 2: YT Captions synced — beats LRCLib plain
                val captionsText = getYoutubeCaptions(resolvedVideoId)
                val captionsSynced = captionsText?.takeIf { it.startsWith("[") }
                if (captionsSynced != null) {
                    Log.d(TAG, "Tier 2: YT Captions synced LRC returned for: $title")
                    return LRCLibResult(
                        id = -1, name = title, trackName = title, artistName = artist,
                        albumName = album, duration = (duration ?: 0).toDouble(),
                        instrumental = false, plainLyrics = null, syncedLyrics = captionsSynced
                    )
                }

                // Tier 3: LRCLib plain lyrics (if available — no synced from any source)
                if (bestMatch?.plainLyrics?.isNotBlank() == true) {
                    Log.d(TAG, "Tier 3: LRCLib plain lyrics returned for: $title (no synced found)")
                    return bestMatch
                }

                // Tier 4: YTM plain lyrics
                val ytmText = getYoutubeMusicLyrics(resolvedVideoId)
                if (!ytmText.isNullOrBlank()) {
                    Log.d(TAG, "Tier 4: YTM plain lyrics returned for: $title")
                    return LRCLibResult(
                        id = -1, name = title, trackName = title, artistName = artist,
                        albumName = album, duration = (duration ?: 0).toDouble(),
                        instrumental = false, plainLyrics = ytmText, syncedLyrics = null
                    )
                }

                // Tier 5: YT Captions plain (already fetched above)
                val captionsPlain = captionsText?.takeIf { !it.startsWith("[") }
                if (!captionsPlain.isNullOrBlank()) {
                    Log.d(TAG, "Tier 5: YT Captions plain returned for: $title")
                    return LRCLibResult(
                        id = -1, name = title, trackName = title, artistName = artist,
                        albumName = album, duration = (duration ?: 0).toDouble(),
                        instrumental = false, plainLyrics = captionsPlain, syncedLyrics = null
                    )
                }

                Log.d(TAG, "All lyrics sources exhausted for: $title")
            } else {
                // No video ID resolvable — return whatever LRCLib found (plain or null)
                if (bestMatch != null) {
                    Log.d(TAG, "No video ID resolved — returning LRCLib result for: $title")
                    return bestMatch
                }
                Log.d(TAG, "No lyrics found and no video ID for: $title $artist")
            }

            null

        } catch (e: Exception) {
            Log.e(TAG, "Error searching lyrics: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches plain-text lyrics from the YouTube Music dedicated lyrics endpoint.
     *
     * Two-step Innertube flow:
     * 1. POST /youtubei/v1/next (WEB_REMIX) → extract the "Lyrics" tab browseId
     * 2. POST /youtubei/v1/browse           → extract the lyrics description runs
     *
     * @param videoId YouTube video ID
     * @return Plain text lyrics, or null if the track has no official lyrics on YTM
     */
    private suspend fun getYoutubeMusicLyrics(videoId: String): String? {
        return try {
            val ctx = """"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260414.05.00","hl":"en"}}"""

            // Step 1: NEXT → find lyrics tab browseId
            val nextBody = """{$ctx,"videoId":"$videoId"}"""
            val nextJson = JSONObject(
                ApiClient.httpClient.post("$YTM_BASE_URL/youtubei/v1/next") {
                    contentType(ContentType.Application.Json)
                    setBody(nextBody)
                }.bodyAsText()
            )

            val tabs = nextJson
                .optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")

            var browseId: String? = null
            if (tabs != null) {
                for (i in 0 until tabs.length()) {
                    val tab = tabs.getJSONObject(i).optJSONObject("tabRenderer")
                    if (tab?.optString("title") == "Lyrics") {
                        browseId = tab.optJSONObject("endpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                        break
                    }
                }
            }

            if (browseId.isNullOrEmpty()) {
                Log.d(TAG, "YTM: no Lyrics tab for videoId=$videoId")
                return null
            }

            // Step 2: BROWSE → extract lyrics text
            val browseBody = """{$ctx,"browseId":"$browseId"}"""
            val browseJson = JSONObject(
                ApiClient.httpClient.post("$YTM_BASE_URL/youtubei/v1/browse") {
                    contentType(ContentType.Application.Json)
                    setBody(browseBody)
                }.bodyAsText()
            )

            val rawBrowse = browseJson.toString()
            Log.d(TAG, "YTM BROWSE raw response (first 800): ${rawBrowse.take(800)}")

            // Extract all text runs (YTM stores multi-paragraph lyrics as multiple runs)
            val runs = browseJson
                .optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("musicDescriptionShelfRenderer")
                ?.optJSONObject("description")
                ?.optJSONArray("runs")

            val text = if (runs != null && runs.length() > 0) {
                buildString {
                    for (r in 0 until runs.length()) {
                        append(runs.getJSONObject(r).optString("text", ""))
                    }
                }.trim()
            } else null

            if (text.isNullOrBlank()) {
                Log.d(TAG, "YTM: BROWSE had no lyrics text for browseId=$browseId")
                null
            } else {
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "getYoutubeMusicLyrics failed: ${e.message}")
            null
        }
    }

    /**
     * Fetches captions using the TVHTML5 Innertube client.
     * * Bypasses the 'pot' (Proof of Origin Token) and JS signature cipher requirements
     * because Smart TV clients do not implement BotGuard.
     *
     * @param videoId YouTube video ID
     * @return Plain text or Synced LRC captions, or null if none are available
     */
    private suspend fun getYoutubeCaptions(videoId: String): String? {
        return try {
            // Step 1: Use TVHTML5 client to get a pre-signed URL that doesn't need a poToken
            val playerBody = """
                {
                    "context": {
                        "client": {
                            "clientName": "TVHTML5",
                            "clientVersion": "7.20230405.08.01",
                            "hl": "en"
                        }
                    },
                    "videoId": "$videoId"
                }
            """.trimIndent()

            val playerJson = JSONObject(
                ApiClient.httpClient.post("$YT_BASE_URL/youtubei/v1/player") {
                    contentType(ContentType.Application.Json)
                    setBody(playerBody)
                }.bodyAsText()
            )

            val captionTracks = playerJson
                .optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")

            if (captionTracks == null || captionTracks.length() == 0) {
                Log.d(TAG, "YT Captions: no caption tracks for videoId=$videoId")
                return null
            }

            // Extract the baseUrl and append JSON3 format
            val signedUrl = captionTracks.getJSONObject(0).getString("baseUrl") + "&fmt=json3"
            Log.d(TAG, "YT Captions: Extracted TV client signed URL successfully")

            // Step 2: Fetch JSON3 captions (No special headers needed for TV URLs)
            val responseText = ApiClient.httpClient.get(signedUrl).bodyAsText()

            if (responseText.isBlank()) {
                Log.w(TAG, "YT Captions: Timedtext API returned empty body.")
                return null
            }

            val captionsJson = JSONObject(responseText)
            val events = captionsJson.optJSONArray("events") ?: return null
            Log.d(TAG, "YT Captions raw events (first 2): ${events.toString().take(600)}")

            // Build LRC synced format from JSON3 tStartMs timestamps
            val lrcSb = StringBuilder()
            var hasTimestamps = false

            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val tStartMs = event.optLong("tStartMs", -1L)
                val segs = event.optJSONArray("segs") ?: continue

                val lineText = buildString {
                    for (j in 0 until segs.length()) {
                        val seg = segs.getJSONObject(j).optString("utf8", "")
                        if (seg != "\n") append(seg)
                    }
                }.trim()

                if (lineText.isBlank()) continue

                if (tStartMs >= 0) {
                    hasTimestamps = true
                    val totalSec = tStartMs / 1000.0
                    val min = (totalSec / 60).toInt()
                    val sec = totalSec % 60
                    lrcSb.append("[%02d:%05.2f]%s\n".format(min, sec, lineText))
                } else {
                    lrcSb.append("$lineText\n")
                }
            }

            val result = lrcSb.toString().trim()
            Log.d(TAG, "YT Captions: hasTimestamps=$hasTimestamps, lines=${result.lines().size}")
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            Log.w(TAG, "getYoutubeCaptions failed: ${e.message}")
            null
        }
    }

    /**
     * Validate if lyrics result matches the track details.
     * Returns a score from 0-3 based on artist, title, and duration match.
     * 
     * @param result LRCLib result to validate
     * @param expectedTitle Expected track title
     * @param expectedArtist Expected artist name
     * @param expectedDuration Expected duration in seconds (optional)
     * @return Match score (0-3)
     */
    private fun validateLyricsMatch(
        result: LRCLibResult,
        expectedTitle: String,
        expectedArtist: String,
        expectedDuration: Int?
    ): Int {
        var score = 0

        // Normalize strings for comparison (lowercase, trim)
        val normalizedTitle = expectedTitle.lowercase().trim()
        val normalizedArtist = expectedArtist.lowercase().trim()
        val resultTitle = result.trackName.lowercase().trim()
        val resultArtist = result.artistName.lowercase().trim()

        // Artist match (1 point)
        if (resultArtist.contains(normalizedArtist) || normalizedArtist.contains(resultArtist)) {
            score += 1
        }

        // Title match (1 point)
        if (resultTitle.contains(normalizedTitle) || normalizedTitle.contains(resultTitle)) {
            score += 1
        }

        // Duration match (1 point) - within 15 seconds tolerance
        if (expectedDuration != null) {
            val durationDiff = abs(result.duration - expectedDuration)
            if (durationDiff < 15) {
                score += 1
            }
        } else {
            // If no duration provided, give partial credit
            score += 1
        }

        Log.d(
            TAG, "Lyrics validation - Title: '$normalizedTitle' vs '$resultTitle', " +
                    "Artist: '$normalizedArtist' vs '$resultArtist', " +
                    "Duration: $expectedDuration vs ${result.duration}, Score: $score"
        )

        return score
    }

    /**
     * Cleans song titles by removing parenthesized metadata that hurts fuzzy matching
     * (for example: "From ...", "feat ...", "live", or "remaster").
     */
    private fun cleanSongTitle(title: String): String {
        // Regex to match content in parentheses starting with specific keywords
        // Matches: (From ...), (Feat ...), (Ft ...), (With ...), (Live ...), (Remaster ...)
        // Case insensitive (?i)
        // \s* matches optional leading whitespace
        // \( matches opening parenthesis
        // (?i) makes the group case-insensitive
        // (?:...) is a non-capturing group for the keywords
        // .*? matches any character non-greedily
        // \) matches closing parenthesis
        val regex = Regex("""\s*\((?i)(?:from|feat\.?|ft\.?|with|live|remaster).*?\)""")

        return regex.replace(title, "").trim()
    }
}
