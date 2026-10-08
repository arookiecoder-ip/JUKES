package `in`.synthora.musicbox.services

import org.junit.Assert.*
import org.junit.Test

class OutputSwitchRequestsTest {
    @Test fun rapidChoicesAreSerializedAndLatestOutputWins() {
        val requests = OutputSwitchRequests()
        assertTrue(requests.request(null))
        assertFalse(requests.request("echo-one"))
        assertFalse(requests.request("echo-two"))
        assertEquals("echo-two", requests.finish()?.serial)
        assertTrue(requests.request("echo-two"))
        assertNull(requests.finish())
    }
    @Test fun phoneChoiceIsRetainedAndNotConfusedWithNoPendingRequest() {
        val requests = OutputSwitchRequests()
        assertTrue(requests.request("echo"))
        assertFalse(requests.request(null))
        val pending = requests.finish()
        assertNotNull(pending)
        assertNull(pending?.serial)
    }
    @Test fun signoutDiscardsPendingTransfers() {
        val requests = OutputSwitchRequests()
        requests.request("echo"); requests.request(null)
        requests.clearPending()
        assertNull(requests.finish())
        assertTrue(requests.request(null))
    }
}
