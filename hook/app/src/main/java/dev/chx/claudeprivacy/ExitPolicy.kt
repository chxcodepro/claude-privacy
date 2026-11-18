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
internal const val CLAUDE_PRODUCTION_HOST = "claude.ai"

internal data class ClaudeEdgeExit(val ip: String, val country: String)

internal fun parseClaudeEdgeTrace(body: String): ClaudeEdgeExit {
    val fields = body.lineSequence().map { it.split('=', limit = 2) }
        .filter { it.size == 2 && it[0] in setOf("h", "ip", "loc") }.groupBy({ it[0] }, { it[1] })
    require(fields["h"]?.singleOrNull() == CLAUDE_PRODUCTION_HOST) { "Unexpected Claude trace host" }
    val ip = fields["ip"]?.singleOrNull()?.takeIf(::isPublicIpv4)
        ?: throw IllegalArgumentException("Claude trace has no valid public IPv4")
    val country = fields["loc"]?.singleOrNull()?.takeIf { it in countryCodes }
        ?: throw ExitDenied(ExitRejection.REGION_UNKNOWN, ip)
    return ClaudeEdgeExit(ip, country)
}

internal fun requireAllowlisted(allowed: List<String>, ip: String? = null) {
    if (allowed.isEmpty()) throw ExitDenied(ExitRejection.EMPTY_LIST)
    if (ip != null && ip !in allowed) throw ExitDenied(ExitRejection.NOT_LISTED, ip)
}

internal fun permittedCountry(ip: String, reportedCountry: String): String {
    if (reportedCountry !in countryCodes)
        throw ExitDenied(ExitRejection.REGION_UNKNOWN, ip)
    if (reportedCountry == "CN") throw ExitDenied(ExitRejection.MAINLAND, ip)
    return reportedCountry
}
