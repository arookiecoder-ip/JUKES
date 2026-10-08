package `in`.synthora.musicbox.services

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
    @Test fun delayedVolumeIsRejectedAfterSwitchDisconnectOrLeaseReplacement() {
        val target = SharedPlaybackOutput(mode = "phone", owner = "phone2", token = "lease1")
        org.junit.Assert.assertTrue(canSendRemotePhoneVolume(target, target, true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target.copy(token = "lease2"), true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target.copy(owner = "phone1"), true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target.copy(mode = "alexa"), true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target.copy(handoffPending = true), true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target.copy(owner = "", token = ""), true))
        org.junit.Assert.assertFalse(canSendRemotePhoneVolume(target, target, false))
    }
}
