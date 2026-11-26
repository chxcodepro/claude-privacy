package dev.chx.claudeprivacy

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

internal const val MAX_CONFIG_BYTES = 16384

internal fun parseConfigBackup(text: String, defaults: JSONObject): JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "配置文件超过 16 KiB" }
    val data = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("请选择有效的 JSON 配置文件") }
    fun string(objectValue: JSONObject, key: String): String =
        objectValue.opt(key) as? String ?: throw IllegalArgumentException("配置字段 $key 无效")
    fun objectValue(key: String) = data.opt(key) as? JSONObject ?: throw IllegalArgumentException("配置字段 $key 无效")

    val ips = data.opt("allowedIps") as? JSONArray ?: throw IllegalArgumentException("允许的 IPv4 列表无效")
    for (i in 0 until ips.length()) {
        require((ips.opt(i) as? String)?.let(::isPublicIpv4) == true) { "允许的 IPv4 列表无效" }
    }
    val regions = objectValue("ipRegions")
    for (ip in regions.keys()) {
        require(isPublicIpv4(ip) && string(regions, ip).length <= 160) { "IPv4 地区数据无效" }
    }
    // Backups from installed versions before 0.1.6 have no per-IP timezone map.
    if (!data.has("ipTimezones")) data.put("ipTimezones", JSONObject())
    val zones = objectValue("ipTimezones")
    val knownZones = TimeZone.getAvailableIDs().toSet()
    for (ip in zones.keys()) {
        val zone = string(zones, ip)
        require(isPublicIpv4(ip) && zone.length <= 80 && zone in knownZones) { "IPv4 时区数据无效" }
    }
    require(string(data, "timezoneMode") in setOf("manual", "ip")) { "时区模式无效" }
    require(string(data, "timezone") in knownZones) { "时区无效" }
    require(string(data, "language").matches(Regex("[a-z]{2,3}(?:-[A-Za-z]{2,8})*"))) { "语言代码无效" }

    val values = objectValue("values")
    val defaultValues = defaults.getJSONObject("values")
    for (key in defaultValues.keys()) {
        when (defaultValues.get(key)) {
            is String -> require(string(values, key).length <= if (key == "buildFingerprint") 200 else 80) { "$key 无效" }
            is Number -> {
                val value = values.opt(key) as? Number ?: throw IllegalArgumentException("$key 无效")
                val number = value.toDouble()
                val range = if (key == "batteryPercent") 0..100 else 320..10000
                require(number.isFinite() && number == value.toInt().toDouble() && number >= range.first && number <= range.last) { "$key 无效" }
            }
        }
    }
    for (key in listOf("manufacturer", "brand", "model", "device", "product")) {
        require(string(values, key).isNotBlank()) { "$key 不能为空" }
    }
    val fingerprint = string(values, "buildFingerprint")
    require(fingerprint.isNotEmpty() && !Regex("[\\s\"']").containsMatchIn(fingerprint)) { "构建指纹无效" }
    for (key in listOf("simCountry", "networkCountry")) {
        val country = string(values, key)
        require(country.isEmpty() || country.matches(Regex("[A-Za-z]{2}"))) { "$key 需使用两位国家代码" }
    }
    val id = string(values, "androidId")
    require(id.isEmpty() || id.matches(Regex("[0-9a-f]{16}"))) { "Android ID 无效" }
    val spoof = objectValue("spoof")
    if (!spoof.has("playIntegrity")) spoof.put("playIntegrity", false)
    for (key in defaults.getJSONObject("spoof").keys()) {
        require(spoof.opt(key) is Boolean) { "缺少 $key 开关" }
    }
    require(data.toString().toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "配置文件超过 16 KiB" }
    return data
}
