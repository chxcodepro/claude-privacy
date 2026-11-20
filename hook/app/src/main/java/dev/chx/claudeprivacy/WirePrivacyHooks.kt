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
    fun install(apks: List<File>, config: JSONObject) {
        val flags = config.getJSONObject("spoof")
        val enabled = flags.keys().asSequence().filter { flags.getBoolean(it) }.toSet()
        val data = config.getJSONObject("values")
        val values = data.keys().asSequence().associateWith { data.get(it) }
        val replacements = WireTarget.entries.associateWith {
            wireReplacements(it, enabled, values, config.optString("language", "en-US"))
        }
        val required = WireTarget.entries.filter {
            if (it == WireTarget.CCT) "network" in enabled else replacements.getValue(it).isNotEmpty()
        }.toSet()
        if (required.isEmpty()) return
        val targets = findWireTargets(apks, required)
        for (target in required) {
            val type = Class.forName(targets.getValue(target), false, loader)
            if (target == WireTarget.CCT) installCct(type)
            else installSerializer(type, target, replacements.getValue(target))
            DiagnosticEvents.record("adapter_installed", "target=${target.name}")
        }
    }

    private fun installSerializer(type: Class<*>, target: WireTarget, replacements: Map<String, Any>) {
        val descriptorMethod = type.getDeclaredMethod("getDescriptor").apply { isAccessible = true }
        val instance = type.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == type
        }.apply { isAccessible = true }.get(null)
        val descriptor = checkNotNull(descriptorMethod.invoke(instance))
        val method = type.declaredMethods.single {
            it.name == "serialize" && it.parameterCount == 2 && it.returnType == Void.TYPE
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    param.args[0] = replacingEncoder(param.args[0], method.parameterTypes[0], descriptor, replacements,
                        { audit("wireOverride:${target.name}") {
                            DiagnosticEvents.record("wire_override", "target=${target.name}")
                            true
                        } }, onFailure)
                } catch (error: Throwable) {
                    val failure = error as? WireContractException ?: WireContractException("Privacy serializer setup failed: ${target.name}")
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
