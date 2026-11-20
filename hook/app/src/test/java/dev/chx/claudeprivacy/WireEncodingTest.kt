package dev.chx.claudeprivacy

import org.junit.Assert.*
import org.junit.Test

class WireEncodingTest {
    interface Descriptor { fun key(index: Int): String; fun size(): Int }
    class Schema(private val keys: List<String>) : Descriptor {
        override fun key(index: Int) = keys[index]
        override fun size() = keys.size
    }
    interface Encoder { fun renamedBegin(descriptor: Descriptor): Composite }
    interface Composite {
        fun str(descriptor: Descriptor, index: Int, value: String)
        fun number(index: Int, value: Int, descriptor: Descriptor)
        fun list(descriptor: Descriptor, index: Int, serializer: Any, value: Any)
        fun end()
    }
    class Sink : Encoder, Composite {
        val data = mutableMapOf<String, Any>()
        var error: RuntimeException? = null
        override fun renamedBegin(descriptor: Descriptor) = this
        override fun str(descriptor: Descriptor, index: Int, value: String) {
            error?.let { throw it }; data[descriptor.key(index)] = value
        }
        override fun number(index: Int, value: Int, descriptor: Descriptor) { data[descriptor.key(index)] = value }
        override fun list(descriptor: Descriptor, index: Int, serializer: Any, value: Any) { data[descriptor.key(index)] = value }
        override fun end() {}
    }
    class Field(val obfuscatedName: String)
    interface Context { fun renamedAdd(field: Field, value: Any): Context }
    class Fields : Context {
        val data = mutableMapOf<String, Any>()
        override fun renamedAdd(field: Field, value: Any): Context { data[field.obfuscatedName] = value; return this }
    }

    @Test fun replacesSelectedKeysAfterFieldReorderingAndKeepsOtherValues() {
        val descriptor = Schema(listOf("message", "screen_width", "model", "network_addresses"))
        val sink = Sink()
        var changes = 0
        val encoder = replacingEncoder(sink, Encoder::class.java, descriptor,
            mapOf("model" to "Pixel 9", "screen_width" to 1080, "network_addresses" to emptyList<String>()),
            { changes++ }, { throw it }) as Encoder
        val composite = encoder.renamedBegin(descriptor)
        composite.str(descriptor, 0, "unchanged message")
        composite.number(1, 1440, descriptor)
        composite.str(descriptor, 2, "real model")
        composite.list(descriptor, 3, Any(), listOf("192.0.2.1"))
        composite.end()
        assertEquals(mapOf("message" to "unchanged message", "screen_width" to 1080,
            "model" to "Pixel 9", "network_addresses" to emptyList<String>()), sink.data)
        assertEquals(3, changes)
    }

    @Test fun omitsOnlyCctNetworkMetadataAndPreservesFluentCalls() {
        val sink = Fields()
        var omitted = 0
        val wrapped = omittingCctNetwork(sink, { omitted++ }, { throw it }) as Context
        wrapped.renamedAdd(Field("eventTimeMs"), 123L)
            .renamedAdd(Field("networkConnectionInfo"), "real network")
            .renamedAdd(Field("eventCode"), 456)
        assertEquals(mapOf<String, Any>("eventTimeMs" to 123L, "eventCode" to 456), sink.data)
        assertEquals(1, omitted)
    }

    @Test fun rejectsChangedContractsAndPreservesOriginalEncoderExceptions() {
        val descriptor = Schema(listOf("model"))
        assertThrows(WireContractException::class.java) {
            replacingEncoder(Sink(), Encoder::class.java, descriptor,
                mapOf("missing_field" to "fake"), {}, {})
        }
        val sink = Sink().apply { error = IllegalArgumentException("original encoder failed") }
        var privacyErrors = 0
        val encoder = replacingEncoder(sink, Encoder::class.java, descriptor,
            mapOf("model" to "fake"), {}, { privacyErrors++ }) as Encoder
        val error = assertThrows(IllegalArgumentException::class.java) {
            encoder.renamedBegin(descriptor).str(descriptor, 0, "real")
        }
        assertSame(sink.error, error)
        assertEquals(0, privacyErrors)
    }

    @Test fun aChangedFieldTypeReportsFailureBeforeWritingTheOriginalValue() {
        val descriptor = Schema(listOf("model"))
        val sink = Sink()
        var privacyErrors = 0
        val encoder = replacingEncoder(sink, Encoder::class.java, descriptor,
            mapOf("model" to 17), {}, { privacyErrors++ }) as Encoder
        assertThrows(WireContractException::class.java) {
            encoder.renamedBegin(descriptor).str(descriptor, 0, "real model")
        }
        assertEquals(1, privacyErrors)
        assertTrue(sink.data.isEmpty())
    }
}
