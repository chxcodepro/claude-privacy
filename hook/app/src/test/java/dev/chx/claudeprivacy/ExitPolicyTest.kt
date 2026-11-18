package dev.chx.claudeprivacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExitPolicyTest {
    private val ip = "8.8.4.4"

    @Test fun emptyAllowlistRejectsBeforeProbing() {
        assertEquals(ExitRejection.EMPTY_LIST, assertThrows(ExitDenied::class.java) {
            requireAllowlisted(emptyList())
        }.rejection)
    }

    @Test fun exitMustMatchAnExactAllowlistEntry() {
        requireAllowlisted(listOf(ip), ip)
        assertEquals(ExitRejection.NOT_LISTED, assertThrows(ExitDenied::class.java) {
            requireAllowlisted(listOf("8.8.8.8"), ip)
        }.rejection)
    }

    @Test fun mainlandAddressIsDeniedEvenIfAllowlisted() {
        requireAllowlisted(listOf(ip), ip)
        assertEquals(ExitRejection.MAINLAND, assertThrows(ExitDenied::class.java) {
            permittedCountry(ip, "CN")
        }.rejection)
        for (country in listOf("HK", "MO", "TW", "US")) {
            assertEquals(country, permittedCountry(ip, country))
        }
    }

    @Test fun onlyTheClaudeEdgeCanReportTheExitAndRegion() {
        val trace = "h=claude.ai\r\nip=$ip\r\nloc=US\r\n"
        assertEquals(ClaudeEdgeExit(ip, "US"), parseClaudeEdgeTrace(trace))
        for (invalid in listOf(trace.replace("claude.ai", "example.com"),
                trace.replace(ip, "192.168.0.1"), trace.replace(ip, "2001:db8::1"), trace + "ip=1.1.1.1")) {
            assertThrows(IllegalArgumentException::class.java) { parseClaudeEdgeTrace(invalid) }
        }
        for (invalid in listOf(trace.replace("loc=US", ""), trace.replace("loc=US", "loc=ZZ"), trace + "loc=CN")) {
            assertEquals(ExitRejection.REGION_UNKNOWN, assertThrows(ExitDenied::class.java) {
                parseClaudeEdgeTrace(invalid)
            }.rejection)
        }
    }
}
