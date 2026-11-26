package dev.chx.claudeprivacy

internal enum class TargetApp(val packageName: String, val wireTargets: Set<WireTarget>) {
    CLAUDE("com.anthropic.claude", WireTarget.entries.toSet()),
    DUET("com.anthropic.aibo", setOf(WireTarget.DEVICE, WireTarget.STATE, WireTarget.CCT));

    companion object {
        fun forPackage(name: String) = entries.singleOrNull { it.packageName == name }
    }
}
