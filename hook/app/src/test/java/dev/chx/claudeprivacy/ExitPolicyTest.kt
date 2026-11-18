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
            permittedCountry(ip, ip, "CN")
        }.rejection)
        for (country in listOf("HK", "MO", "TW", "US")) {
            assertEquals(country, permittedCountry(ip, ip, country))
        }
    }

    @Test fun unknownAndMismatchedRegionResultsAreDenied() {
        for ((address, country) in listOf(ip to null, ip to "ZZ", "1.1.1.1" to "US")) {
            assertEquals(ExitRejection.REGION_UNKNOWN, assertThrows(ExitDenied::class.java) {
                permittedCountry(ip, address, country)
            }.rejection)
        }
    }
}
