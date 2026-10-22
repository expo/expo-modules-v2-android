package io.github.expo.modules.v2.binary

import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.records.Record
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.records.SchemaId
import io.github.expo.modules.v2.records.readers.BufferRecordReader
import io.github.expo.modules.v2.records.writers.BufferRecordWriter
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.kolibri.binary.BinaryTag

/**
 * The schema-directed codec a [io.github.expo.modules.v2.converters.TypeConverter]'s buffer pass writes and reads
 * through, so a converter can encode and decode its bridge values (elements included) without
 * owning any wire format itself. Stateless, so one instance serves every thread.
 */
object BinaryValueCodec {

  internal fun writeDynamic(buf: BinaryBuffer, value: Any?) = writeDynamic(buf, value, plain = false)

  /**
   * [writeDynamic] for a value native code holds in a JNI slot and would otherwise walk with a JNI
   * call per element. A record or a map key that is not a String throws [SlotOnlyValueException]
   * instead, so such a value keeps that walk and what it makes of them.
   */
  internal fun writePlainDynamic(buf: BinaryBuffer, value: Any?) = writeDynamic(buf, value, plain = true)

  private fun writeDynamic(buf: BinaryBuffer, value: Any?, plain: Boolean) {
    when (value) {
      null -> buf.putTaggedNull()
      is Boolean -> buf.putTaggedBoolean(value)
      is Int -> buf.putTaggedInt(value)
      is Long -> buf.putTaggedLong(value)
      is Float -> buf.putTaggedFloat(value)
      is Double -> buf.putTaggedDouble(value)
      is String -> buf.putTaggedString(value)
      is List<*> -> writeDynamicList(buf, value, plain)
      is Map<*, *> -> writeDynamicMap(buf, value, plain)
      is DoubleArray -> buf.putListHeader(value.size, BinaryTag.DOUBLE).putDoubles(value)
      is IntArray -> buf.putListHeader(value.size, BinaryTag.INT).putInts(value)
      is LongArray -> buf.putListHeader(value.size, BinaryTag.LONG).putLongs(value)
      is FloatArray -> buf.putListHeader(value.size, BinaryTag.FLOAT).putFloats(value)
      is BooleanArray -> {
        buf.putListHeader(value.size, BinaryTag.BOOLEAN)
        for (element in value) buf.putBoolean(element)
      }
      is ByteArray -> buf.putTaggedByteArray(value)
      is JavaScriptValue, is JavaScriptObject, Unit -> throw SlotOnlyValueException()
      else -> if (plain) throw SlotOnlyValueException() else writeDynamicExternalSchema(buf, value)
    }
  }

  internal fun readDynamic(buf: BinaryBuffer): Any? = readDynamic(buf, plain = false)

  /**
   * [readDynamic] for a value native code would otherwise build in a JNI slot. Its maps start at
   * the default capacity, as that build's do, so they iterate in the same order.
   */
  internal fun readPlainDynamic(buf: BinaryBuffer): Any? = readDynamic(buf, plain = true)

  private fun readDynamic(buf: BinaryBuffer, plain: Boolean): Any? {
    return when (val tag = buf.getTag()) {
      BinaryTag.NULL -> null
      BinaryTag.BOOLEAN -> buf.getBoolean()
      BinaryTag.INT -> buf.getInt()
      BinaryTag.LONG -> buf.getLong()
      BinaryTag.FLOAT -> buf.getFloat()
      BinaryTag.DOUBLE -> buf.getDouble()
      BinaryTag.STRING -> buf.getString()
      BinaryTag.LIST -> readDynamicList(buf, plain)
      BinaryTag.MAP -> readDynamicMap(buf, plain)
      BinaryTag.BYTE_ARRAY -> buf.getByteArray()
      BinaryTag.EXTERNAL_SCHEMA -> {
        val schemaId = SchemaId(buf.getExternalSchemaId())
        RecordRegistry.typeFor(schemaId).codec.decode(BufferRecordReader(buf))
      }

      else -> throw IllegalStateException("Unknown binary tag: $tag")
    }
  }

  private fun writeDynamicList(buf: BinaryBuffer, list: List<*>, plain: Boolean) {
    // A list whose elements are all one non-null scalar type rides an untagged bulk run; anything
    // else (mixed types, nulls, nested values) needs self-describing entries.
    val scalarTag = dynamicScalarTagOf(list)
    if (scalarTag == null) {
      buf.putListHeader(list.size, BinaryTag.TAGGED)
      for (element in list) writeDynamic(buf, element, plain)
      return
    }
    buf.putListHeader(list.size, scalarTag)
    for (element in list) writeDynamicScalar(buf, element!!, scalarTag)
  }

  private fun readDynamicList(buf: BinaryBuffer, plain: Boolean): ArrayList<Any?> {
    val (count, elementTag) = buf.getListHeader()
    val list = ArrayList<Any?>(count)
    when (elementTag) {
      BinaryTag.TAGGED -> repeat(count) { list.add(readDynamic(buf, plain)) }
      BinaryTag.DOUBLE -> buf.getDoubles(count).forEach { list.add(it) }
      BinaryTag.INT -> buf.getInts(count).forEach { list.add(it) }
      BinaryTag.LONG -> buf.getLongs(count).forEach { list.add(it) }
      BinaryTag.FLOAT -> buf.getFloats(count).forEach { list.add(it) }
      BinaryTag.BOOLEAN -> repeat(count) { list.add(buf.getBoolean()) }
      else -> throw IllegalStateException("Unknown list element tag: $elementTag")
    }
    return list
  }

  /** The bulk-run tag [list] qualifies for, or null when it needs tagged elements. */
  private fun dynamicScalarTagOf(list: List<*>): Int? {
    val tag = dynamicScalarTagOf(list.firstOrNull() ?: return null) ?: return null
    return tag.takeIf { candidate ->
      list.all { it != null && dynamicScalarTagOf(it) == candidate }
    }
  }

  private fun dynamicScalarTagOf(value: Any): Int? = when (value) {
    is Double -> BinaryTag.DOUBLE
    is Int -> BinaryTag.INT
    is Long -> BinaryTag.LONG
    is Float -> BinaryTag.FLOAT
    is Boolean -> BinaryTag.BOOLEAN
    else -> null
  }

  /**
   * Writes a bulk-run element. The list header already named the type, so this carries no tag of
   * its own; the casts are safe because [dynamicScalarTagOf] classified every element first.
   */
  private fun writeDynamicScalar(buf: BinaryBuffer, value: Any, scalarTag: Int) {
    when (scalarTag) {
      BinaryTag.DOUBLE -> buf.putDouble(value as Double)
      BinaryTag.INT -> buf.putInt(value as Int)
      BinaryTag.LONG -> buf.putLong(value as Long)
      BinaryTag.FLOAT -> buf.putFloat(value as Float)
      BinaryTag.BOOLEAN -> buf.putBoolean(value as Boolean)
      else -> throw IllegalStateException("Not a bulk-run element tag: $scalarTag")
    }
  }

  private fun writeDynamicMap(buf: BinaryBuffer, map: Map<*, *>, plain: Boolean) {
    buf.putMapHeader(map.size)
    for ((key, value) in map) {
      if (plain && key !is String) {
        throw SlotOnlyValueException()
      }
      require(key is String) { "Cannot binary-encode a Map with non-String key" }
      buf.putString(key)
      writeDynamic(buf, value, plain)
    }
  }

  private fun readDynamicMap(buf: BinaryBuffer, plain: Boolean): HashMap<String, Any?> {
    val count = buf.getMapSize()
    val map = if (plain) HashMap() else HashMap<String, Any?>(count * 4 / 3 + 1)
    repeat(count) {
      map[buf.getString()] = readDynamic(buf, plain)
    }
    return map
  }

  private fun writeDynamicExternalSchema(buf: BinaryBuffer, value: Any) {
    val type = RecordRegistry.typeFor(value.javaClass)
      ?: throw IllegalArgumentException(
        "No external schema registered for ${value.javaClass.name}; annotate the class with " +
          "@io.github.expo.modules.v2.Record, or call RecordRegistry.register before the " +
          "payload encode begins",
      )
    buf.putExternalSchemaHeader(type.schemaId.value)
    val writer = BufferRecordWriter(buf)
    type.anyCodec.encode(value as Record, writer)
  }
}

/**
 * Thrown by a buffer write that meets a value only a JNI slot can carry: a JSI handle or `Unit`
 * inside an `Any`, and for [BinaryValueCodec.writePlainDynamic] a record too.
 * [io.github.expo.modules.v2.args.Trampoline.writeResult] catches it and hands the whole value over
 * as an overflow result, the same way as a payload that does not fit.
 *
 * Without a stack trace: it is always caught, and a result that holds such a value throws it on
 * every call.
 */
internal class SlotOnlyValueException : RuntimeException(null, null, false, false)
