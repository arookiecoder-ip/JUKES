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
    @Test fun keysNeverExceedDeviceVolumeLimits() {
        assertEquals(100, remoteVolumeStep(99, 1))
        assertEquals(0, remoteVolumeStep(1, -1))
        assertEquals(50, remoteVolumeStep(50, 0))
    }
}
