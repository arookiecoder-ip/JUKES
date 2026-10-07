package com.example.juke.services

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteVolumeKeysTest {
    @Test fun repeatedKeysAccumulateFromOptimisticVolume() {
        var volume = 40
        repeat(6) { volume = remoteVolumeStep(volume, 1) }
        assertEquals(70, volume)
        assertEquals(65, remoteVolumeStep(volume, -1))
    }
    @Test fun percentagesRoundToActualAndroidVolumeSteps() {
        assertEquals(11, volumePercentToStreamIndex(remoteVolumeStep(66, 1), 15))
        assertEquals(9, volumePercentToStreamIndex(remoteVolumeStep(66, -1), 15))
        assertEquals(0, volumePercentToStreamIndex(0, 15))
        assertEquals(15, volumePercentToStreamIndex(100, 15))
    }
    @Test fun eachPhoneKeyMovesOneRealVolumeLevel() {
        for (maximum in listOf(1, 5, 7, 15, 25, 100)) {
            for (level in 0..maximum) {
                val current = level * 100 / maximum
                val up = remotePhoneVolumeStep(current, 1, maximum)
                val down = remotePhoneVolumeStep(current, -1, maximum)
                assertEquals((level + 1).coerceAtMost(maximum), volumePercentToStreamIndex(up, maximum))
                assertEquals((level - 1).coerceAtLeast(0), volumePercentToStreamIndex(down, maximum))
            }
        }
    }
    @Test fun keysNeverExceedDeviceVolumeLimits() {
        assertEquals(100, remoteVolumeStep(99, 1))
        assertEquals(0, remoteVolumeStep(1, -1))
        assertEquals(50, remoteVolumeStep(50, 0))
    }
}
