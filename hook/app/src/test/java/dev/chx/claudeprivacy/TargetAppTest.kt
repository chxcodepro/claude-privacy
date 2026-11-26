package dev.chx.claudeprivacy

import org.junit.Assert.*
import org.junit.Test

class TargetAppTest {
    @Test fun scopesEachAppToItsAuditedProtocols() {
        assertEquals(TargetApp.CLAUDE, TargetApp.forPackage("com.anthropic.claude"))
        assertEquals(WireTarget.entries.toSet(), TargetApp.CLAUDE.wireTargets)
        assertEquals(TargetApp.DUET, TargetApp.forPackage("com.anthropic.aibo"))
        assertEquals(setOf(WireTarget.DEVICE, WireTarget.STATE, WireTarget.CCT), TargetApp.DUET.wireTargets)
        assertNull(TargetApp.forPackage("com.anthropic.aibo.other"))
    }
}
