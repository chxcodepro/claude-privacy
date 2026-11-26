package dev.chx.claudeprivacy

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

internal class WirePrivacyHooks(
    private val loader: ClassLoader,
    private val audit: (String, () -> Boolean) -> Unit,
    private val onFailure: (WireContractException) -> Unit,
) {
    fun install(apks: List<File>, config: JSONObject, supported: Set<WireTarget>) {
        val flags = config.getJSONObject("spoof")
        val enabled = flags.keys().asSequence().filter { flags.getBoolean(it) }.toSet()
        val data = config.getJSONObject("values")
        val values = data.keys().asSequence().associateWith { data.get(it) }
        val replacements = WireTarget.entries.associateWith {
            wireReplacements(it, enabled, values, config.optString("language", "en-US"))
        }
        val required = supported.filter {
            if (it == WireTarget.CCT) "network" in enabled else replacements.getValue(it).isNotEmpty()
        }.toSet()
        val targets = findPrivacyTargets(apks, required, "playIntegrity" in enabled)
        targets.deviceReportConfig?.let { installDeviceReportConfig(Class.forName(it, false, loader)) }
        if ("playIntegrity" in enabled) {
            installPlayIntegrityHooks(loader, targets.integrityCalls, onFailure)
            for ((name, keys) in targets.integrityCarriers) {
                installSerializer(Class.forName(name, false, loader), "PLAY_INTEGRITY", emptyMap(), keys)
            }
        }
        for (target in required) {
            val type = Class.forName(targets.wire.getValue(target), false, loader)
            if (target == WireTarget.CCT) installCct(type)
            else installSerializer(type, target.name, replacements.getValue(target))
            DiagnosticEvents.record("adapter_installed", "target=${target.name}")
        }
    }

    private fun installDeviceReportConfig(type: Class<*>) {
        val decode = type.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 1 &&
                it.parameterTypes[0].isInterface && it.returnType == Any::class.java
        } ?: throw WireContractException("Device report configuration decoder unavailable")
        XposedBridge.hookMethod(decode, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                try {
                    disableDeviceReport(checkNotNull(param.result))
                    audit("deviceReportDisabled") {
                        DiagnosticEvents.record("device_report_disabled")
                        true
                    }
                } catch (error: Throwable) {
                    val failure = error as? WireContractException ?: WireContractException("Device report configuration override failed")
                    onFailure(failure)
                    param.throwable = failure
                }
            }
        })
        DiagnosticEvents.record("device_report_adapter_installed")
    }

    private fun installSerializer(type: Class<*>, target: String, replacements: Map<String, Any>, omitted: Set<String> = emptySet()) {
        val descriptorMethod = serializerDescriptorMethod(type).apply { isAccessible = true }
        val instance = type.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == type
        }.apply { isAccessible = true }.get(null)
        val descriptor = checkNotNull(descriptorMethod.invoke(instance))
        val method = serializerEncodeMethod(type)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    param.args[0] = replacingEncoder(param.args[0], method.parameterTypes[0], descriptor, replacements,
                        { audit("wireOverride:$target") {
                            DiagnosticEvents.record("wire_override", "target=$target")
                            true
                        } }, onFailure, omitted)
                } catch (error: Throwable) {
                    val failure = error as? WireContractException ?: WireContractException("Privacy serializer setup failed: $target")
                    onFailure(failure)
                    // Throwing from a before-hook would let Xposed resume the original unprotected call.
                    param.throwable = failure
                }
            }
        })
    }

    private fun installCct(type: Class<*>) {
        val method = type.declaredMethods.single { it.returnType == Void.TYPE && it.parameterCount == 2 }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    param.args[1] = omittingCctNetwork(param.args[1], { audit("cctEncodedEvent") {
                        DiagnosticEvents.record("cct_network_omitted")
                        true
                    } }, onFailure)
                } catch (error: Throwable) {
                    val failure = error as? WireContractException ?: WireContractException("CCT context setup failed")
                    onFailure(failure)
                    param.throwable = failure
                }
            }
        })
    }
}
