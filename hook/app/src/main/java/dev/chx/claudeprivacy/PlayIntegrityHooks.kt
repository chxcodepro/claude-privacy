package dev.chx.claudeprivacy

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

internal fun failedIntegrityTask(type: Class<*>): () -> Any {
    val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
    val fail = type.declaredMethods.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Exception::class.java))
    }?.apply { isAccessible = true } ?: throw WireContractException("Play Integrity task completion unavailable")
    return {
        constructor.newInstance().also { task ->
            fail.invoke(task, SecurityException("Play Integrity blocked by privacy settings"))
        }
    }
}

internal fun installPlayIntegrityHooks(
    loader: ClassLoader, calls: Map<IntegrityCall, IntegrityMethod>,
    onFailure: (WireContractException) -> Unit,
) {
    for ((call, target) in calls) {
        val type = Class.forName(target.owner, false, loader)
        val parameters = target.parameters.map { Class.forName(it.removeSurrounding("L", ";").replace('/', '.'), false, loader) }
        val method = type.getDeclaredMethod(target.name, *parameters.toTypedArray())
        val suppress: (Any) -> Any? = if (call == IntegrityCall.STANDARD) {
            // Complete the queued request before skipping its Binder send, so callers receive a failure immediately.
            val field = type.declaredFields.singleOrNull { field ->
                !Modifier.isStatic(field.modifiers) && field.type.declaredMethods.any {
                    it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Exception::class.java))
                }
            }?.apply { isAccessible = true } ?: throw WireContractException("Play Integrity pending task unavailable")
            val fail = field.type.declaredMethods.single {
                it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Exception::class.java))
            }.apply { isAccessible = true }
            val block: (Any) -> Any? = { owner ->
                fail.invoke(field.get(owner), SecurityException("Play Integrity blocked by privacy settings"))
                null
            }
            block
        } else {
            val failed = failedIntegrityTask(method.returnType)
            val block: (Any) -> Any? = { failed() }
            block
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    param.result = suppress(param.thisObject)
                    DiagnosticEvents.record("play_integrity_blocked", "api=${call.name}")
                } catch (_: Throwable) {
                    val failure = WireContractException("Play Integrity request suppression failed: ${call.name}")
                    onFailure(failure)
                    param.throwable = failure
                }
            }
        })
        DiagnosticEvents.record("play_integrity_installed", "api=${call.name}")
    }
}
