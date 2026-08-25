// DUMP_IR

package expo.modules.v2.testdata

import expo.modules.v2.annotations.Buffer
import expo.modules.v2.annotations.JS
import expo.modules.v2.annotations.Record
import expo.modules.v2.jsi.JavaScriptValue
import expo.modules.v2.modules.Module

@Record
data class Point(val x: Double, val y: Double)

@Record(bufferSafe = false)
data class Holder(val value: Any?)

/**
 * Every value shape the emitter has to describe, so the transport decision for each is pinned in one
 * place. The rule under test: buffer whenever the wire format allows it, except a primitive array.
 */
@JS
object Values : Module() {
    // Nullable scalars box, so they ride the buffer; their non-null forms cannot.
    @JS
    fun nullableScalars(a: Int?, b: Long?, c: Float?, d: Double?, e: Boolean?): Int? = a

    @JS
    fun unboxedScalars(a: Int, b: Long, c: Float, d: Double, e: Boolean): Int = a

    // Primitive arrays are already a bulk region copy, so they stay in their slot.
    @JS
    fun arrays(a: IntArray, b: DoubleArray?, c: ByteArray): Int = a.size

    // Containers buffer when nothing inside needs a JNI round trip.
    @JS
    fun containers(
        list: List<String>,
        map: Map<String, Int>,
        set: Set<String>,
        array: Array<String>,
    ): Int = list.size + map.size + set.size + array.size

    // Nullability lives on the element, not the container.
    @JS
    fun nullableElements(values: List<String?>, nullableList: List<Int>?): Int = values.size

    // A record buffers when it declares itself buffer-safe.
    @JS
    fun echoPoint(point: Point): Point = point

    // ...and rides its Map bridge when it does not.
    @JS
    fun echoHolder(holder: Holder): Holder = holder

    // A container of records inherits the record's safety.
    @JS
    fun points(values: List<Point>): Int = values.size

    @JS
    fun holders(values: List<Holder>): Int = values.size

    // Any and the JSI handles have no binary encoding, so they always keep their slot.
    @JS
    fun dynamic(value: Any?, handle: JavaScriptValue): Int = handle.hashCode()

    // Buffer.NO on a value the default would have buffered.
    @JS
    fun optedOut(@JS(buffer = Buffer.NO) values: List<String>): Int = values.size

    // Buffer.YES on the one shape the default declines.
    @JS(buffer = Buffer.YES)
    fun optedIn(values: IntArray): Int = values.size
}

private fun descriptorOf(name: String): String {
    val method = Values::class.java.declaredMethods.firstOrNull { it.name == name }
        ?: return "<absent>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Long::class.javaPrimitiveType -> "J"
    type == Float::class.javaPrimitiveType -> "F"
    type == Double::class.javaPrimitiveType -> "D"
    type == Boolean::class.javaPrimitiveType -> "Z"
    type == Void.TYPE -> "V"
    type.isArray -> "[" + descriptor(type.componentType)
    else -> "L" + type.name.replace('.', '/') + ";"
}

fun box(): String {
    val suffix = "__trampoline\$ExpoModulesV2"
    val expected = mapOf(
        // all five arguments buffered, plus a buffered Int? result
        "nullableScalars$suffix" to "(I)I",
        // nothing to convert and nothing bufferable: no trampoline at all
        "unboxedScalars$suffix" to "<absent>",
        // arrays keep their slots, so again no trampoline
        "arrays$suffix" to "<absent>",
        // four buffered containers and an unboxed Int result
        "containers$suffix" to "(I)I",
        "nullableElements$suffix" to "(I)I",
        // a buffer-safe record rides the payload both ways
        "echoPoint$suffix" to "(I)I",
        // an unsafe record crosses as a JMap in a slot, both ways
        "echoHolder$suffix" to "(Ljava/util/Map;)Ljava/util/Map;",
        "points$suffix" to "(I)I",
        // a list of unsafe records is a JList slot
        "holders$suffix" to "(Ljava/util/List;)I",
        // Any and a handle both stay in slots, and neither needs conversion
        "dynamic$suffix" to "<absent>",
        // Buffer.NO puts the list back in a slot, but it still needs no conversion
        "optedOut$suffix" to "<absent>",
        // Buffer.YES moves the primitive array onto the payload
        "optedIn$suffix" to "(I)I",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(name)
        if (got != want) return "$name: expected $want, got $got"
    }
    return "OK"
}
