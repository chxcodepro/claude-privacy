package dev.chx.claudeprivacy

import java.util.Locale

internal enum class ExitRejection(val status: String) {
    EMPTY_LIST("no_allowed_ips"),
    NOT_LISTED("ip_denied"),
    MAINLAND("mainland_ip"),
    REGION_UNKNOWN("ip_region_unknown"),
}

internal class ExitDenied(val rejection: ExitRejection, val ip: String? = null, cause: Throwable? = null) :
    SecurityException(rejection.status, cause)

private val countryCodes = Locale.getISOCountries().toSet()

internal fun requireAllowlisted(allowed: List<String>, ip: String? = null) {
    if (allowed.isEmpty()) throw ExitDenied(ExitRejection.EMPTY_LIST)
    if (ip != null && ip !in allowed) throw ExitDenied(ExitRejection.NOT_LISTED, ip)
}

internal fun permittedCountry(ip: String, reportedIp: Any?, reportedCountry: Any?): String {
    if (reportedIp != ip || reportedCountry !is String || reportedCountry !in countryCodes)
        throw ExitDenied(ExitRejection.REGION_UNKNOWN, ip)
    if (reportedCountry == "CN") throw ExitDenied(ExitRejection.MAINLAND, ip)
    return reportedCountry
}
