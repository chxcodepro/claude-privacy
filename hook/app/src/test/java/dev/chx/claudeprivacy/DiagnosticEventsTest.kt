package dev.chx.claudeprivacy

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticEventsTest {
    @Test fun exportsFixedFailureCodesWithoutExceptionMessagesOrAddresses() {
        assertEquals("watchdog_unavailable", DiagnosticEvents.failureCode(ExitDenied(ExitRejection.WATCHDOG_UNAVAILABLE)))
        assertEquals("bridge_not_visible", DiagnosticEvents.failureCode(IllegalArgumentException("Unknown authority dev.chx.claudeprivacy.bridge")))
        assertEquals("root_timeout", DiagnosticEvents.failureCode(IllegalStateException("Root command timed out")))
        assertEquals("operation_failed", DiagnosticEvents.failureCode(IllegalStateException("Cookie=private; IP=8.8.4.4")))
        assertEquals("ip_denied", DiagnosticEvents.failureCode(ExitDenied(ExitRejection.NOT_LISTED, "8.8.4.4")))
    }
}
