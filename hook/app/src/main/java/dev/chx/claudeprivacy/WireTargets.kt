package dev.chx.claudeprivacy

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.Method
import java.io.File
import java.util.zip.ZipFile

internal enum class WireTarget(val markers: Set<String>) {
    DEVICE(setOf("com.anthropic.claude.sift.AndroidDeviceProperties", "android_id", "evidence_files_present")),
    STATE(setOf("com.anthropic.claude.sift.AndroidAppState", "battery_level", "network_addresses")),
    PLAID(setOf("com.plaid.internal.networking.model.DeviceMetadata", "screen_width", "screen_height")),
    CCT(setOf("eventTimeMs", "eventUptimeMs", "networkConnectionInfo")),
}

internal enum class IntegrityCall(val marker: String, val argumentCount: Int) {
    CLASSIC("requestIntegrityToken(%s)", 1),
    PREPARE("warmUpIntegrityToken(%s)", 1),
    STANDARD("requestExpressIntegrityToken(%s, %s, %s)", 0);

    fun matches(method: Method) = method.accessFlags and 0x8 == 0 &&
        !method.name.startsWith("<") && method.parameterTypes.size == argumentCount &&
        if (this == STANDARD) method.returnType == "V" else method.returnType.startsWith("L")
}

internal data class IntegrityMethod(val owner: String, val name: String, val parameters: List<String>)
internal data class PrivacyTargets(
    val wire: Map<WireTarget, String>,
    val deviceReportConfig: String?,
    val integrityCalls: Map<IntegrityCall, IntegrityMethod>,
    val integrityCarriers: Map<String, Set<String>>,
)

private fun dexStrings(method: Method): Set<String> = (method.implementation?.instructions?.asSequence() ?: emptySequence())
    .filterIsInstance<ReferenceInstruction>().mapNotNull { (it.reference as? StringReference)?.string }.toSet()

internal fun findPrivacyTargets(apks: List<File>, required: Set<WireTarget>, blockIntegrity: Boolean = false): PrivacyTargets {
    val matches = required.associateWith { mutableSetOf<String>() }
    val reportProtocol = "com.anthropic.claude.devicereport.DeviceReportConfig"
    val reportMarkers = setOf(reportProtocol, "sign_up", "sign_in", "purchase")
    val reportConfigs = mutableSetOf<String>()
    val integrityCalls = if (blockIntegrity) IntegrityCall.entries.associateWith { mutableSetOf<IntegrityMethod>() } else emptyMap()
    val integrityCarriers = mutableMapOf<String, Set<String>>()
    for (apk in apks.distinct()) ZipFile(apk).use { zip ->
        for (entry in zip.entries()) {
            if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
            val dex = zip.getInputStream(entry).use { DexBackedDexFile(null, it.readBytes()) }
            for (type in dex.classes) {
                val methods = type.methods.toList()
                val name = type.type.removeSurrounding("L", ";").replace('/', '.')
                val serializer = methods.any { it.name == "getDescriptor" && it.parameterTypes.isEmpty() }
                val strings = methods.asSequence().filter {
                    it.name == "<clinit>" || (serializer && it.name in setOf("<init>", "getDescriptor"))
                }.flatMap { dexStrings(it).asSequence() }.toSet()
                for (target in required) if (strings.containsAll(target.markers)) {
                    matches.getValue(target).add(name)
                }
                if (reportProtocol in strings) {
                    if (!strings.containsAll(reportMarkers)) throw WireContractException("Device report configuration schema changed")
                    reportConfigs.add(name)
                }
                if (blockIntegrity) {
                    val tokenKeys = strings.intersect(setOf("play_integrity_token", "play_integrity_standard_token"))
                    if (tokenKeys.isNotEmpty() && strings.any { it.startsWith("com.anthropic.claude.api.") } &&
                        methods.any { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString).let { parameters ->
                            parameters.size == 2 && parameters[1] == "Ljava/lang/Object;"
                        } }
                    ) integrityCarriers[name] = tokenKeys
                    for (method in methods) {
                        val candidates = IntegrityCall.entries.filter { it.matches(method) }
                        if (candidates.isEmpty()) continue
                        val literals = dexStrings(method)
                        for (call in candidates) if (call.marker in literals) {
                            integrityCalls.getValue(call).add(IntegrityMethod(name, method.name, method.parameterTypes.map(CharSequence::toString)))
                        }
                    }
                }
            }
        }
    }
    val wire = matches.mapValues { (target, names) ->
        if (names.isEmpty()) throw WireContractException("Required privacy interface missing: ${target.name}")
        if (names.size > 1) throw WireContractException("Ambiguous privacy interface: ${target.name}")
        names.single()
    }
    if (reportConfigs.size > 1) throw WireContractException("Ambiguous device report configuration")
    val calls = integrityCalls.mapValues { (call, candidates) ->
        if (candidates.size != 1) throw WireContractException("Play Integrity interface missing or ambiguous: ${call.name}")
        candidates.single()
    }
    if (blockIntegrity && integrityCarriers.isEmpty()) throw WireContractException("Play Integrity token carriers missing")
    return PrivacyTargets(wire, reportConfigs.singleOrNull(), calls, integrityCarriers)
}
