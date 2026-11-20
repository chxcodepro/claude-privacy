package dev.chx.claudeprivacy

import java.util.Locale

internal fun wireReplacements(
    target: WireTarget, enabled: Set<String>, values: Map<String, Any>, language: String,
): Map<String, Any> = buildMap {
    when (target) {
        WireTarget.DEVICE -> {
            if ("androidId" in enabled) put("android_id", values.getValue("androidId"))
            if ("carrier" in enabled) {
                put("mobile_carrier_name", values.getValue("carrierName"))
                put("mobile_iso_country_code", values.getValue("simCountry"))
            }
            if ("device" in enabled) {
                put("device_manufacturer", values.getValue("manufacturer"))
                put("device_model", values.getValue("model"))
            }
            if ("build" in enabled) put("build_tags", "release-keys")
            if ("root" in enabled) for (key in listOf("evidence_files_present", "evidence_packages_present", "evidence_properties", "evidence_directories_writable")) put(key, emptyList<String>())
            if ("packages" in enabled) put("installed_apps", emptyList<String>())
        }
        WireTarget.STATE -> {
            if ("battery" in enabled) {
                put("battery_level", (values.getValue("batteryPercent") as Number).toDouble() / 100.0)
                put("battery_state", 3L)
                put("battery_health", 2L)
                put("plug_state", 0L)
            }
            if ("localIps" in enabled) put("network_addresses", emptyList<String>())
        }
        WireTarget.PLAID -> {
            if ("device" in enabled) {
                put("manufacturer", values.getValue("manufacturer"))
                put("model", values.getValue("model"))
            }
            if ("language" in enabled) {
                val locale = Locale.forLanguageTag(language)
                put("locale", locale.toLanguageTag())
                put("country_code", locale.country)
                put("language_code", locale.language)
            }
            if ("screen" in enabled) {
                put("screen_width", values.getValue("screenWidth"))
                put("screen_height", values.getValue("screenHeight"))
            }
        }
        WireTarget.CCT -> Unit
    }
}
