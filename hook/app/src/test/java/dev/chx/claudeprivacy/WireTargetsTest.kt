package dev.chx.claudeprivacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

class WireTargetsTest {
    @Test fun missingEnabledInterfacesAreRejectedWhileDisabledGroupsNeedNoTarget() {
        val apk = File.createTempFile("claude-empty-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { }
            assertTrue(findPrivacyTargets(listOf(apk), emptySet()).wire.isEmpty())
            assertEquals(null, findPrivacyTargets(listOf(apk), emptySet()).deviceReportConfig)
            assertThrows(WireContractException::class.java) { findPrivacyTargets(listOf(apk), setOf(WireTarget.DEVICE)) }
        } finally { apk.delete() }
    }
    @Test fun findsContractsInBothActualApksWithoutAnAppVersionAllowlist() {
        val previous = System.getenv("CLAUDE_PREVIOUS_APK")
        val current = System.getenv("CLAUDE_CURRENT_APK")
        assumeNotNull(previous, current)
        val samples = listOf(File(previous!!) to TargetApp.CLAUDE, File(current!!) to TargetApp.CLAUDE) +
            listOfNotNull(System.getenv("CLAUDE_DUET_APK")?.let { File(it) to TargetApp.DUET })
        for ((apk, app) in samples) {
            val started = System.nanoTime()
            val discovered = findPrivacyTargets(listOf(apk), app.wireTargets)
            val targets = discovered.wire
            assertEquals(app.wireTargets, targets.keys)
            println("${apk.parentFile?.name}: $targets; scanMs=${(System.nanoTime() - started) / 1_000_000}")
            val types = ZipFile(apk).use { zip -> zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .flatMap { entry -> zip.getInputStream(entry).use { DexBackedDexFile(null, it.readBytes()) }.classes.asSequence() }
                .associateBy { it.type }
            }
            fun allMethods(type: ClassDef): List<com.android.tools.smali.dexlib2.iface.Method> =
                type.methods.toList() + type.interfaces.flatMap { allMethods(types.getValue(it)) }
            fun definition(name: String) = types.getValue("L${name.replace('.', '/')};")
            discovered.deviceReportConfig?.let { name ->
                val decode = definition(name).methods.single { it.parameterTypes.size == 1 && it.returnType == "Ljava/lang/Object;" }
                assertTrue(types.getValue(decode.parameterTypes[0].toString()).accessFlags and 0x200 != 0)
                println("deviceReportConfig=$name")
            }
            for ((target, name) in targets) {
                val type = definition(name)
                if (target == WireTarget.CCT) {
                    val encode = type.methods.single { it.returnType == "V" && it.parameterTypes.size == 2 }
                    val contexts = encode.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
                        .mapNotNull { it.reference as? TypeReference }.mapNotNull { types[it.type] }
                        .filter { it.accessFlags and 0x200 != 0 }.distinctBy { it.type }.toList()
                    assertEquals("CCT encoder must cast to one context interface", 1, contexts.size)
                    val descriptors = allMethods(contexts.single()).filter { it.parameterTypes.size == 2 }
                        .map { it.parameterTypes[0].toString() }.distinct()
                    assertEquals(1, descriptors.size)
                    assertEquals(1, types.getValue(descriptors.single()).instanceFields.count { it.type == "Ljava/lang/String;" })
                } else {
                    val keys = type.methods.filter { it.name == "<clinit>" }
                        .flatMap { it.implementation!!.instructions.filterIsInstance<ReferenceInstruction>() }
                        .mapNotNull { (it.reference as? StringReference)?.string }.toSet()
                    val protectedKeys = when (target) {
                        WireTarget.DEVICE -> setOf("android_id", "mobile_carrier_name", "mobile_iso_country_code", "device_manufacturer", "device_model", "build_tags", "evidence_files_present", "evidence_packages_present", "evidence_properties", "evidence_directories_writable", "installed_apps")
                        WireTarget.STATE -> setOf("battery_level", "battery_state", "battery_health", "plug_state", "network_addresses")
                        WireTarget.PLAID -> setOf("manufacturer", "model", "locale", "country_code", "language_code", "screen_width", "screen_height")
                        WireTarget.CCT -> error("CCT handled separately")
                    }
                    assertTrue("Protected fields changed in $target", keys.containsAll(protectedKeys))
                    assertEquals(1, type.staticFields.count { it.type == type.type })
                    val serialize = type.methods.single { it.returnType == "V" && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "Ljava/lang/Object;" }
                    val encoder = types.getValue(serialize.parameterTypes[0].toString())
                    assertTrue(encoder.accessFlags and 0x200 != 0)
                    val descriptor = type.methods.single { method ->
                        method.parameterTypes.isEmpty() && types[method.returnType]?.let { candidate ->
                            candidate.accessFlags and 0x200 != 0 && allMethods(candidate).any {
                                it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
                            }
                        } == true
                    }.returnType
                    val descriptorMethods = allMethods(types.getValue(descriptor))
                    assertEquals(1, descriptorMethods.count { it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(CharSequence::toString) == listOf("I") })
                    assertEquals(1, descriptorMethods.count { it.returnType == "I" && it.parameterTypes.isEmpty() && it.name != "hashCode" })
                    val structures = allMethods(encoder).filter { method ->
                        method.parameterTypes.map(CharSequence::toString) == listOf(descriptor) &&
                            types[method.returnType]?.let { composite -> composite.accessFlags and 0x200 != 0 &&
                                allMethods(composite).any { it.returnType == "V" && it.parameterTypes.size >= 3 && descriptor in it.parameterTypes && "I" in it.parameterTypes }
                            } == true
                    }
                    assertEquals("One supported structure encoder required for $target", 1, structures.size)
                }
            }
        }
    }
}
