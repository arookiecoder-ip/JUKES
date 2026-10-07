package com.example.juke.network

import org.junit.Assert.*
import org.junit.Test

class ArtworkTest {
    @Test fun googleSquareAndQuerySizesAreUpgradedWithoutLosingSignature() {
        assertEquals("https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj?token=abc", largeArtworkUrl("https://lh3.googleusercontent.com/art=s120?token=abc"))
        assertEquals("https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj", largeArtworkUrl("https://lh3.googleusercontent.com/art=w120-h120-l90-rj"))
    }
    @Test fun youtubeMissingMaxResolutionHasOrderedFallbacks() {
        val choices = artworkCandidates("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg", "aaaaaaaaaaa", true)
        assertEquals(listOf("maxresdefault.jpg", "hq720.jpg"), choices.map { it.substringAfterLast('/') })
    }
    @Test fun unknownSignedImagesAndMiniSizesArePreserved() {
        val image = "https://example.com/art=s120?signature=abc"
        assertEquals(image, largeArtworkUrl(image))
        assertEquals(listOf(image), artworkCandidates(image, null, false))
    }
    @Test fun lowResolutionAndHttp200PlaceholdersStayHidden() {
        assertFalse(isHdArtwork(120, 90))
        assertFalse(isHdArtwork(640, 480))
        assertTrue(isHdArtwork(1280, 720))
        assertTrue(isHdArtwork(1200, 1200))
        assertFalse(isHdArtwork(720, -1))
    }
    @Test fun missingThumbnailUsesSongIdentity() {
        assertTrue(artworkCandidates("", "aaaaaaaaaaa", true).first().contains("/aaaaaaaaaaa/maxresdefault.jpg"))
    }
    @Test fun rowArtworkIsBoundedAndCannotOverwritePlayerHdUrl() {
        val source = "https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj?signature=abc"
        assertEquals("https://lh3.googleusercontent.com/art=w144-h144-l90-rj?signature=abc", rowArtworkUrl(source, 144))
        assertEquals("https://lh3.googleusercontent.com/art=w320-h320-l90-rj?signature=abc", rowArtworkUrl(source, 4000))
        assertEquals(source, largeArtworkUrl(rowArtworkUrl(source, 144)))
        assertEquals("https://unknown.com/art=w1200?signature=abc", rowArtworkUrl("https://unknown.com/art=w1200?signature=abc", 144))
    }
    @Test fun wideVideoCanvasIsRemovedSymmetricallyWithoutLosingHd() {
        val crop = squareArtworkCrop(1280, 720)
        assertEquals(ArtworkCrop(280, 0, 720), crop)
        assertTrue(isHdArtwork(crop.size, crop.size))
        assertEquals(crop.left, 1280 - crop.left - crop.size)
        assertEquals(ArtworkCrop(0, 280, 720), squareArtworkCrop(720, 1280))
        assertEquals(ArtworkCrop(0, 0, 1200), squareArtworkCrop(1200, 1200))
    }
    @Test fun catalogAndCustomArtworkAreNotClassifiedAsVideoCanvases() {
        assertTrue(isVideoArtwork("https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg"))
        assertFalse(isVideoArtwork("https://lh3.googleusercontent.com/art=w1200"))
        assertFalse(isVideoArtwork("https://example.com/ytimg.com/art.jpg"))
    }
}
