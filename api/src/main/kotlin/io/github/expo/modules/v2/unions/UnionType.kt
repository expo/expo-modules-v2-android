package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.typeCodes
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.anyConverter

/**
 * A registered union: its codec, and the converter of each variant's value. Native code picks the
 * variant from the JavaScript value, and [decode] hands a value that variant refuses to the next
 * one of the same type codes. The compiler plugin's `UnionCheckers` makes sure every variant can be
 * picked.
 */
internal class UnionType<T : Any>(
  val codec: UnionCodec<T>,
) {
  val schema: UnionSchema = codec.schema

  /**
   * The converter of each variant's value, or null for an `object` variant. Resolved on first use:
   * a variant may hold this union again, directly or through a record.
   */
  private val converters: List<TypeConverter<Any>?> by lazy {
    schema.variants.map { it.type?.anyConverter }
  }

  /**
   * For each variant, the next one to try when converting its value throws, or -1: a later variant
   * with the same type codes, such as `String` after `URL`. Native code cannot tell those apart,
   * so it picks the first, and its conversion is what refuses a value.
   */
  private val fallbacks: IntArray by lazy {
    // Every variant's codes, as they are inside this union, written through one writer.
    val codes = TypeCodesWriter()
    val starts = IntArray(converters.size + 1)
    codes.insideUnion(this) {
      converters.forEachIndexed { index, converter ->
        starts[index] = codes.size
        converter?.writeTypeCodes(codes)
      }
    }
    starts[converters.size] = codes.size

    IntArray(converters.size) { index ->
      if (converters[index] == null) {
        return@IntArray -1
      }
      (index + 1 until converters.size).firstOrNull { later ->
        converters[later] != null &&
          codes.rangeEquals(starts[index], starts[index + 1], starts[later], starts[later + 1])
      } ?: -1
    }
  }

  /**
   * `UNION, count, name, variant...`: each variant's own codes, or `UNIT, tag` for an `object`
   * variant. A variant that holds this union again writes a reference back to it.
   */
  fun writeTypeCodes(out: TypeCodesWriter, isNullable: Boolean) {
    if (schema.sameBridge) {
      requireSameBridge()
      // The codes of the bridge value every variant shares, nullable when the union is.
      val head = out.size
      requireNotNull(converters.first()).writeTypeCodes(out)
      if (isNullable) {
        out.addFlags(head, CppType.NULLABLE)
      }
      return
    }

    val converters = converters
    out.add(CppType.UNION.nullableCode(isNullable))
    out.add(converters.size)
    out.addString(schema.name)
    out.insideUnion(this) {
      converters.forEachIndexed { index, converter ->
        if (converter == null) {
          out.add(CppType.UNIT)
          out.addString(schema.variants[index].tag)
        } else {
          converter.writeTypeCodes(out)
        }
      }
    }
  }

  /**
   * Checks the generated schema's [UnionSchema.sameBridge] once: every variant has the same type
   * codes. A union that does not would cross with a type native code does not expect.
   */
  private val sameBridgeChecked: Unit by lazy {
    val first = converters.first()?.typeCodes()
    for ((index, converter) in converters.withIndex()) {
      val codes = converter?.typeCodes()
      check(codes != null && first != null && codes.contentEquals(first)) {
        "${schema.name} cannot cross as one bridge value: ${schema.variants[index].tag} crosses " +
          "as ${codes?.contentToString() ?: "a tag"}, not ${first?.contentToString()}"
      }
    }
  }

  private fun requireSameBridge() = sameBridgeChecked

  fun converterAt(index: Int): TypeConverter<Any>? {
    val converters = converters
    if (index !in converters.indices) {
      noSuchVariant(schema.name, index)
    }
    return converters[index]
  }

  /**
   * The variant at [index] around the value [read] converts with its converter. Native code picked
   * [index] by the kind of the JavaScript value; when the variant's own conversion refuses it -
   * `URL("hello")` - the next variant with the same type codes gets it instead.
   */
  inline fun decode(index: Int, read: (TypeConverter<Any>?) -> Any?): T {
    var at = index
    while (true) {
      // Outside the try: an index with no variant is an error of its own, not a refused value.
      val converter = converterAt(at)
      try {
        return wrap(at, read(converter))
      } catch (e: Exception) {
        at = fallbackOf(at).takeIf { it >= 0 } ?: throw e
      }
    }
  }

  fun fallbackOf(index: Int): Int = fallbacks[index]

  @Suppress("UNCHECKED_CAST")
  fun indexOf(value: Any): Int {
    // Checked here: the codec's bridge method would throw a bare ClassCastException instead.
    val index = if (codec.unionClass.isInstance(value)) codec.indexOf(value as T) else -1
    require(index >= 0) {
      "${value.javaClass.name} is not a variant of ${schema.name}"
    }
    return index
  }

  @Suppress("UNCHECKED_CAST")
  fun unwrap(value: Any): Any? = codec.unwrap(value as T)

  fun wrap(index: Int, payload: Any?): T = codec.wrap(index, payload)
}
