package com.example.juke.network

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NetworkErrorTest {
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
