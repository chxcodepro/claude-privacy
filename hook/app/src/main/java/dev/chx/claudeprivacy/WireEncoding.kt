package dev.chx.claudeprivacy

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

internal class WireContractException(message: String) : IllegalStateException(message)

internal fun disableDeviceReport(config: Any) {
    val fields = config.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    contract(fields.size == 3 && fields.count { it.type == Boolean::class.javaPrimitiveType } == 2 &&
        fields.count { it.type == String::class.java } == 1, "Device report configuration contract changed")
    // This gate is read before hardware collection, report upload and one-off attestation key generation.
    for (field in fields) {
        field.isAccessible = true
        if (field.type == String::class.java) field.set(config, "off") else field.setBoolean(config, false)
    }
}

internal fun serializerDescriptorMethod(type: Class<*>): Method = type.declaredMethods.singleOrNull {
    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType.isInterface &&
        it.returnType.methods.any { member ->
            member.returnType == String::class.java &&
                member.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } && it.returnType.methods.any { member ->
            member.returnType == Int::class.javaPrimitiveType && member.parameterCount == 0 && member.name != "hashCode"
        }
} ?: throw WireContractException("Privacy serializer descriptor unavailable")

internal fun serializerEncodeMethod(type: Class<*>): Method = type.declaredMethods.singleOrNull {
    !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 2 &&
        it.parameterTypes[0].isInterface && it.parameterTypes[1] == Any::class.java
} ?: throw WireContractException("Privacy serializer encode method unavailable")

private fun contract(ok: Boolean, message: String) {
    if (!ok) throw WireContractException(message)
}

private inline fun <T> checked(onFailure: (WireContractException) -> Unit, block: () -> T): T =
    try { block() } catch (error: WireContractException) { onFailure(error); throw error }

private fun forward(method: Method, target: Any, args: Array<Any?>): Any? = try {
    method.isAccessible = true
    method.invoke(target, *args)
} catch (error: InvocationTargetException) { throw error.targetException }

private fun proxy(type: Class<*>, handler: (Method, Array<Any?>) -> Any?): Any {
    contract(type.isInterface, "Privacy encoder interface changed")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
        val args = arguments?.map { it }?.toTypedArray() ?: emptyArray()
        when {
            method.declaringClass != Any::class.java -> handler(method, args)
            method.name == "equals" -> instance === args.firstOrNull()
            method.name == "hashCode" -> System.identityHashCode(instance)
            else -> "ClaudePrivacyEncoder"
        }
    }
}

private fun replacementFor(value: Any, previous: Any?, type: Class<*>): Any {
    contract(previous == null || when (previous) {
        is List<*> -> value is List<*>
        is Number -> value is Number
        else -> previous.javaClass.isInstance(value)
    }, "Privacy field type changed")
    return if (value is Number) when {
        type == Int::class.javaPrimitiveType || previous is Int -> value.toInt()
        type == Long::class.javaPrimitiveType || previous is Long -> value.toLong()
        type == Float::class.javaPrimitiveType || previous is Float -> value.toFloat()
        type == Double::class.javaPrimitiveType || previous is Double -> value.toDouble()
        else -> value
    } else value
}

internal fun replacingEncoder(
    original: Any, encoderType: Class<*>, descriptor: Any, replacements: Map<String, Any>,
    onOverride: () -> Unit, onFailure: (WireContractException) -> Unit,
    omitted: Set<String> = emptySet(),
): Any = checked(onFailure) {
    val keyMethod = descriptor.javaClass.methods.singleOrNull {
        it.returnType == String::class.java && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
    } ?: throw WireContractException("Privacy field names unavailable")
    val countMethod = descriptor.javaClass.methods.singleOrNull {
        it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 0 && it.name != "hashCode"
    } ?: throw WireContractException("Privacy field count unavailable")
    val count = forward(countMethod, descriptor, emptyArray()) as Int
    val keys = (0 until count).map { forward(keyMethod, descriptor, arrayOf(it)) as String }
    contract(keys.containsAll(replacements.keys + omitted), "Privacy field schema changed")
    val begin = encoderType.methods.singleOrNull { method ->
        method.parameterCount == 1 && method.parameterTypes[0].isInstance(descriptor) &&
            method.returnType.isInterface && method.returnType.methods.any {
                it.returnType == Void.TYPE && it.parameterCount >= 3 &&
                    it.parameterTypes.any { parameter -> parameter.isInstance(descriptor) } &&
                    Int::class.javaPrimitiveType in it.parameterTypes
            }
    } ?: throw WireContractException("Privacy structure encoder unavailable")
    proxy(encoderType) { method, args ->
        if (method != begin || args.firstOrNull() !== descriptor) forward(method, original, args)
        else {
            val composite = forward(method, original, args)
                ?: throw WireContractException("Privacy structure encoder returned null")
            checked(onFailure) {
                proxy(method.returnType) composite@{ element, values ->
                    checked(onFailure) {
                        // R8 reorders primitive encoder arguments; identify the descriptor and wire index by contract.
                        val descriptorAt = values.indexOfFirst { it === descriptor }
                        val indexAt = element.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
                        if (element.returnType == Void.TYPE && values.size >= 3 && descriptorAt >= 0 && indexAt >= 0) {
                            val index = values[indexAt] as Int
                            contract(index in keys.indices, "Privacy field index changed")
                            val key = keys[index]
                            if (key in omitted) {
                                onOverride()
                                return@composite null
                            }
                            if (replacements.containsKey(key)) {
                                val valueAt = values.indices.last { it != descriptorAt && it != indexAt }
                                values[valueAt] = replacementFor(replacements.getValue(key), values[valueAt], element.parameterTypes[valueAt])
                                onOverride()
                            }
                        }
                    }
                    forward(element, composite, values)
                }
            }
        }
    }
}

internal fun omittingCctNetwork(
    original: Any, onOmitted: () -> Unit,
    onFailure: (WireContractException) -> Unit,
): Any = checked(onFailure) {
    val interfaces = linkedSetOf<Class<*>>()
    fun collect(type: Class<*>?) {
        if (type == null) return
        for (parent in type.interfaces) if (interfaces.add(parent)) collect(parent)
        collect(type.superclass)
    }
    collect(original.javaClass)
    val candidates = interfaces.filter { type -> type.methods.any {
        it.parameterCount == 2 && it.parameterTypes[0].declaredFields.count { field ->
            !Modifier.isStatic(field.modifiers) && field.type == String::class.java
        } == 1
    } }
    val contextType = candidates.filter { type -> candidates.none { it != type && type.isAssignableFrom(it) } }.singleOrNull()
        ?: throw WireContractException("CCT context interface changed")
    val descriptorTypes = contextType.methods.filter { it.parameterCount == 2 }
        .map { it.parameterTypes[0] }.distinct()
    contract(descriptorTypes.size == 1, "CCT field descriptor changed")
    val name = descriptorTypes.single().declaredFields.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
    } ?: throw WireContractException("CCT field names unavailable")
    name.isAccessible = true
    lateinit var wrapped: Any
    wrapped = proxy(contextType) { method, args ->
        if (args.size == 2 && descriptorTypes.single().isInstance(args[0]) && name.get(args[0]) == "networkConnectionInfo") {
            checked(onFailure) {
                contract(method.returnType == Void.TYPE || method.returnType.isInstance(wrapped), "CCT encoder return type changed")
                onOmitted()
                if (method.returnType == Void.TYPE) null else wrapped
            }
        } else forward(method, original, args).let { if (it === original) wrapped else it }
    }
    wrapped
}
