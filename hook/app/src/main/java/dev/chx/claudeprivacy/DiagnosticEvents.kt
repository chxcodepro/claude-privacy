package dev.chx.claudeprivacy

import android.os.Process
import android.util.Log

internal object DiagnosticEvents {
    fun record(event: String, vararg fields: String) {
        val tokens = listOf(event) + fields
        if (tokens.size > 8 || tokens.any { !it.matches(Regex("[A-Za-z0-9_=-]{1,64}")) }) return
        val message = tokens.joinToString(" ")
        if (message.length > 160) return
        Log.i("ClaudePrivacyDiag", "cpdiag t=${System.currentTimeMillis()} pid=${Process.myPid()} $message")
    }

    fun failureCode(error: Throwable): String = when {
        error is ExitDenied -> error.rejection.status
        error is WireContractException -> "privacy_contract_changed"
        error.message == "Unknown authority dev.chx.claudeprivacy.bridge" -> "bridge_not_visible"
        error.message == "Root command timed out" -> "root_timeout"
        error.message == "WATCHDOG_UNAVAILABLE" -> "watchdog_unavailable"
        else -> "operation_failed"
    }
}
