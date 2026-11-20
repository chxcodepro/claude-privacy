package dev.chx.claudeprivacy

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.util.zip.ZipFile

internal enum class WireTarget(val markers: Set<String>) {
    DEVICE(setOf("com.anthropic.claude.sift.AndroidDeviceProperties", "android_id", "evidence_files_present")),
    STATE(setOf("com.anthropic.claude.sift.AndroidAppState", "battery_level", "network_addresses")),
    PLAID(setOf("com.plaid.internal.networking.model.DeviceMetadata", "screen_width", "screen_height")),
    CCT(setOf("eventTimeMs", "eventUptimeMs", "networkConnectionInfo")),
}

internal fun findWireTargets(apks: List<File>, required: Set<WireTarget>): Map<WireTarget, String> {
    val matches = required.associateWith { mutableSetOf<String>() }
    for (apk in apks.distinct()) ZipFile(apk).use { zip ->
        for (entry in zip.entries()) {
            if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
            val dex = zip.getInputStream(entry).use { DexBackedDexFile(null, it.readBytes()) }
            for (type in dex.classes) {
                val methods = type.methods.toList()
                val serializer = methods.any { it.name == "getDescriptor" && it.parameterTypes.isEmpty() }
                val strings = methods.asSequence().filter {
                    it.name == "<clinit>" || (serializer && it.name in setOf("<init>", "getDescriptor"))
                }.flatMap { it.implementation?.instructions?.asSequence() ?: emptySequence() }
                    .filterIsInstance<ReferenceInstruction>()
                    .mapNotNull { (it.reference as? StringReference)?.string }.toSet()
                for (target in required) if (strings.containsAll(target.markers)) {
                    matches.getValue(target).add(type.type.removeSurrounding("L", ";").replace('/', '.'))
                }
            }
        }
    }
    return matches.mapValues { (target, names) ->
        if (names.isEmpty()) throw WireContractException("Required privacy interface missing: ${target.name}")
        if (names.size > 1) throw WireContractException("Ambiguous privacy interface: ${target.name}")
        names.single()
    }
}
