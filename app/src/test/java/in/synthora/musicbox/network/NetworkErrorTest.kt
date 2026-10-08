package `in`.synthora.musicbox.network

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkErrorTest {
    @Test fun queueRejectionDoesNotSignOutAValidSession() = runBlocking {
        assertFalse(confirmSessionExpired { })
    }
    @Test fun onlyConfirmedSessionRejectionSignsOut() = runBlocking {
        assertTrue(confirmSessionExpired { throw BackendAuthException("Session required") })
        assertFalse(confirmSessionExpired { throw IOException("Offline") })
        assertFalse(confirmSessionExpired { throw BackendHttpException(520, "Server unavailable") })
    }
    @Test fun cancelledSessionValidationDoesNotSignOut() = runBlocking {
        try {
            confirmSessionExpired { throw CancellationException("New login") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun timeoutUsesRetryMessage() {
        assertEquals("The connection is taking too long. Please try again.", networkErrorMessage(SocketTimeoutException()))
    }
    @Test fun wrappedDisconnectUsesConnectionMessage() {
        assertEquals("Couldn't connect. Check your internet connection and try again.", networkErrorMessage(IllegalStateException("request failed", IOException())))
    }
    @Test fun cancelledNavigationDoesNotBecomeNetworkFailure() {
        assertNull(networkErrorMessage(CancellationException("Screen closed")))
    }
    @Test fun serverAndAccountErrorsKeepTheirOwnMessage() {
        assertNull(networkErrorMessage(IllegalStateException("Wrong password")))
    }
}
