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
        assertEquals(listOf("maxresdefault.jpg", "hq720.jpg", "sddefault.jpg", "mqdefault.jpg"), choices.map { it.substringAfterLast('/') })
    }
    @Test fun unknownSignedImagesAndMiniSizesArePreserved() {
        val image = "https://example.com/art=s120?signature=abc"
        assertEquals(image, largeArtworkUrl(image))
        assertEquals(listOf(image), artworkCandidates(image, null, false))
    }
    @Test fun missingThumbnailUsesSongIdentity() {
        assertTrue(artworkCandidates("", "aaaaaaaaaaa", true).first().contains("/aaaaaaaaaaa/maxresdefault.jpg"))
    }
}
