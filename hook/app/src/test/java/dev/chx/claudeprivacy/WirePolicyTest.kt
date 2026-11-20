package dev.chx.claudeprivacy

import org.junit.Assert.*
import org.junit.Test

class WirePolicyTest {
    private val values = mapOf<String, Any>(
        "androidId" to "0123456789abcdef", "manufacturer" to "Google", "model" to "Pixel 9",
        "carrierName" to "", "simCountry" to "", "batteryPercent" to 50,
        "screenWidth" to 1080, "screenHeight" to 2400,
    )
    @Test fun disabledGroupsDoNotReplaceFieldsOrReadUnusedValues() {
        for (target in WireTarget.entries) assertTrue(wireReplacements(target, emptySet(), emptyMap(), "en-US").isEmpty())
        val root = wireReplacements(WireTarget.DEVICE, setOf("root"), values, "en-US")
        assertEquals(setOf("evidence_files_present", "evidence_packages_present", "evidence_properties", "evidence_directories_writable"), root.keys)
        assertTrue(root.values.all { it == emptyList<String>() })
        assertFalse(root.containsKey("installed_apps"))
    }
    @Test fun preservesExistingBatteryUnitsAndIndependentLocalIpSwitch() {
        assertEquals(mapOf("battery_level" to 0.5, "battery_state" to 3L, "battery_health" to 2L, "plug_state" to 0L),
            wireReplacements(WireTarget.STATE, setOf("battery"), values, "en-US"))
        assertEquals(mapOf("network_addresses" to emptyList<String>()),
            wireReplacements(WireTarget.STATE, setOf("localIps"), values, "en-US"))
    }
    @Test fun mapsDeviceIdentityAndPlaidLanguageAndScreenBySemanticKeys() {
        val device = wireReplacements(WireTarget.DEVICE, setOf("androidId", "device", "carrier", "build", "packages"), values, "en-US")
        assertEquals(mapOf("android_id" to "0123456789abcdef", "device_manufacturer" to "Google", "device_model" to "Pixel 9",
            "mobile_carrier_name" to "", "mobile_iso_country_code" to "", "build_tags" to "release-keys", "installed_apps" to emptyList<String>()), device)
        assertEquals(mapOf("manufacturer" to "Google", "model" to "Pixel 9", "locale" to "en-US",
            "country_code" to "US", "language_code" to "en", "screen_width" to 1080, "screen_height" to 2400),
            wireReplacements(WireTarget.PLAID, setOf("device", "language", "screen"), values, "en-US"))
    }
}
