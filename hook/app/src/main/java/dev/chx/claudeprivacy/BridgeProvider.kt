package dev.chx.claudeprivacy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.TimeZone

class BridgeProvider : ContentProvider() {
    private class SuUnavailable(cause: IOException) :
        IllegalStateException("SukiSU su is unavailable to Claude Hook", cause)

    private val control = "/data/adb/modules/claude_privacy/bin/control.sh"
    private val firewall = "/data/adb/modules/claude_privacy/bin/firewall.sh"

    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (method in setOf("health", "prepare") && (uid == 0 || uid == 2000)) {
            val token = Binder.clearCallingIdentity()
            return try {
                for (target in TargetApp.entries) {
                    try {
                        context!!.packageManager.getPackageInfo(target.packageName, 0)
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        continue
                    }
                    context!!.grantUriPermission(
                        target.packageName,
                        Uri.parse("content://dev.chx.claudeprivacy.bridge/config"),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val result = if (method == "health") root("id -u") else "ready"
                if (method == "health" && result != "0") {
                    Bundle().apply { putString("error", "su_unavailable") }
                } else {
                    Bundle().apply { putString("result", result) }
                }
            } catch (error: Exception) {
                if (method != "health") throw error
                // Checked exceptions from a failed su launch do not survive Android Binder.
                Bundle().apply {
                    putString("error", if (error is SuUnavailable) "su_unavailable" else "health_failed")
                    putString("detail", (error.message ?: error.javaClass.simpleName).lineSequence().first().take(300))
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        val target = TargetApp.entries.singleOrNull { it.packageName in packages }
            ?: throw SecurityException("Claude or Claude Duet process required")
        val targetControl = "CP_TARGET=${target.packageName} sh $control"
        val targetFirewall = "CP_TARGET=${target.packageName} sh $firewall"
        DiagnosticEvents.record("bridge_call", "method=$method", "pid=$pid")
        val result = when (method) {
            "config" -> root("sh $control get")
            "block" -> {
                val reason = arg?.takeIf { value ->
                    value in setOf("startup", "probe_failed", "network_changed", "background", "unsupported_version", "hook_failed") ||
                        ExitRejection.entries.any { it.status == value }
                } ?: "startup"
                val result = root("$targetFirewall block $uid $reason")
                val detail = extras?.getString("detail")
                if (!detail.isNullOrBlank()) {
                    val status = JSONObject(root("$targetControl status")).put("detail", detail.take(600))
                    val encoded = Base64.encodeToString(status.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    root("$targetControl report $encoded")
                }
                result
            }
            "probe" -> {
                val addresses = arg?.split(',') ?: throw IllegalArgumentException("Missing probe addresses")
                // VPN Fake-IP DNS answers are valid probe destinations; only observed exits must be public.
                if (addresses.size !in 1..32 || addresses.any { !isIpv4(it) }) throw IllegalArgumentException("Invalid probe addresses")
                root("$targetFirewall probe $uid $pid ${addresses.joinToString(" ")}")
            }
            "observed" -> {
                val fields = arg?.split(',').orEmpty()
                require(fields.size == 3 && isPublicIpv4(fields[0])) { "Invalid observed exit" }
                val (ip, country, zone) = fields
                require(country.matches(Regex("[A-Z]{2}"))) { "Invalid exit country" }
                require(zone == "-" || zone in TimeZone.getAvailableIDs()) { "Invalid exit timezone" }
                root("$targetFirewall observed $uid $ip $country $zone")
            }
            "allow" -> {
                val parts = arg?.split(',') ?: throw IllegalArgumentException("Missing network path")
                if (parts.size != 3 && parts.size != 5) throw IllegalArgumentException("Invalid network path and readback")
                val ip = parts[0].takeIf(::isPublicIpv4) ?: throw IllegalArgumentException("Invalid public IPv4")
                val iface = parts[1].takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) } ?: throw IllegalArgumentException("Invalid interface")
                val country = permittedCountry(ip, parts[2])
                // Claude can still have the previous Hook loaded while the provider APK is being updated.
                val zone = (parts.getOrNull(3) ?: "-").takeIf { it == "-" || it in TimeZone.getAvailableIDs() }
                    ?: throw IllegalArgumentException("Invalid timezone readback")
                val language = (parts.getOrNull(4) ?: "-").takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }
                    ?: throw IllegalArgumentException("Invalid language readback")
                val allowed = JSONObject(root("sh $control get")).getJSONArray("allowedIps")
                requireAllowlisted(List(allowed.length(), allowed::getString), ip)
                root("$targetFirewall allow $uid $ip $iface $country $zone $language $pid")
            }
            else -> throw IllegalArgumentException("Unknown method")
        }
        return Bundle().apply { putString("result", result) }
    }

    private fun root(command: String): String {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: IOException) {
            DiagnosticEvents.record("root_start_failed")
            throw SuUnavailable(error)
        }
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            DiagnosticEvents.record("root_timeout")
            throw IllegalStateException("Root command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) {
            DiagnosticEvents.record("root_failed", "exit=${process.exitValue()}")
            throw IllegalStateException(output.ifEmpty { "Root command failed" })
        }
        return output
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
