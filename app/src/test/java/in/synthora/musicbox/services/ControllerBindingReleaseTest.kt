package `in`.synthora.musicbox.services

import org.junit.Assert.*
import org.junit.Test

class ControllerBindingReleaseTest {
    @Test fun alreadyRemovedBindingDoesNotCrashThePostedCleanup() {
        var reported = false
        releaseControllerBinding(
            { throw IllegalArgumentException("Service not registered: Media3Connection") },
            { reported = true }
        )
        assertTrue(reported)
    }

    @Test fun registeredBindingIsReleasedNormally() {
        var released = false
        releaseControllerBinding({ released = true }, { fail("Unexpected missing binding") })
        assertTrue(released)
    }

    @Test fun unrelatedFailuresAreNotHidden() {
        val failures = listOf(IllegalArgumentException("Invalid connection"), SecurityException("Denied"))
        failures.forEach { failure ->
            try {
                releaseControllerBinding({ throw failure }, { fail("Unexpected suppression") })
                fail("Must propagate")
            } catch (actual: RuntimeException) {
                assertSame(failure, actual)
            }
        }
    }
}
