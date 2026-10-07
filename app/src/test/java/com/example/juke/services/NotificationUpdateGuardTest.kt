package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class NotificationUpdateGuardTest {
    private class ForegroundDenied : IllegalStateException()
    @Test fun delayedForegroundDenialInvokesRecoveryInsteadOfCrashing() {
        var recovered = false
        deliverNotificationUpdate({ throw ForegroundDenied() }, { it is ForegroundDenied }, { recovered = true })
        assertTrue(recovered)
    }
    @Test fun successfulArtworkUpdateDoesNotPausePlayback() {
        var updated = false
        deliverNotificationUpdate({ updated = true }, { false }, { fail("Should not pause") })
        assertTrue(updated)
    }
    @Test fun unrelatedProgrammingErrorsRemainVisible() {
        val bug = IllegalStateException("unexpected")
        try {
            deliverNotificationUpdate({ throw bug }, { it is ForegroundDenied }, { fail("Not a foreground denial") })
            fail("Expected the original error")
        } catch (caught: IllegalStateException) { assertSame(bug, caught) }
    }
}
