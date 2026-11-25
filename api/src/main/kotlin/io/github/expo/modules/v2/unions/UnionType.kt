package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.typeCodes
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.anyConverter

/**
 * A registered union: its codec, and the converter of each variant's value. Native code picks the
 * variant from the JavaScript value, and [decode] hands a value that variant refuses to the next
 * one that can still take it: one of the same type codes, or - on the JNI path - one that takes a
 * dynamic value. The compiler plugin's `UnionCheckers` makes sure every variant can be picked.
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

  /** Every variant's codes, as they are inside this union, and where each one starts. */
  private class VariantCodes(val codes: TypeCodesWriter, val starts: IntArray) {
    fun same(index: Int, other: Int): Boolean =
      codes.rangeEquals(starts[index], starts[index + 1], starts[other], starts[other + 1])

    /** Whether the variant's codes are one plain [CppType.ANY]: `Any`, or `Color`. */
    fun isDynamic(index: Int): Boolean =
      starts[index + 1] - starts[index] == 1 && codes[starts[index]] == CppType.ANY.code

    /** Whether the variant crosses as a lone scalar or string, which is also a dynamic value. */
    fun isDynamicLeaf(index: Int): Boolean =
      starts[index + 1] - starts[index] == 1 && (codes[starts[index]] and CODE_MASK) in DYNAMIC_LEAVES

    private companion object {
      /** The code without its [CppType.NULLABLE]/[CppType.USES_BUFFER] flags. */
      const val CODE_MASK = 0xFF

      val DYNAMIC_LEAVES = setOf(
        CppType.BOOLEAN.code,
        CppType.STRING.code,
        CppType.INT.code,
        CppType.LONG.code,
        CppType.FLOAT.code,
        CppType.DOUBLE.code,
      )
    }
  }

  private val variantCodes: VariantCodes by lazy {
    val codes = TypeCodesWriter()
    val starts = IntArray(converters.size + 1)
    codes.insideUnion(this) {
      converters.forEachIndexed { index, converter ->
        starts[index] = codes.size
        converter?.writeTypeCodes(codes)
      }
    }
    starts[converters.size] = codes.size
    VariantCodes(codes, starts)
  }

  /**
   * For each variant, the next one to try when converting its value throws, or -1: a later variant
   * with the same type codes, such as `String` after `URL`. Native code cannot tell those apart,
   * so it picks the first, and its conversion is what refuses a value.
   *
   * The buffer path can only use these: a value on the buffer is laid out for the codes native
   * code picked, so only a variant of the same codes can read it again.
   */
  private val fallbacks: IntArray by lazy {
    fallbacksWhere { index, later -> variantCodes.same(index, later) }
  }

  /**
   * [fallbacks], plus a later variant that takes a dynamic value - `Any`, or `Color` - after a
   * scalar or string variant: `Preset(StringEnum)` then `Custom(Color)`, where native code offers
   * `"#ff0000"` to the enum first. The JNI path can take these, because a refused scalar or string
   * is already a dynamic value. A JavaScript number arrives as the refusing variant's bridge type,
   * such as an `Int`, where a dynamic value alone would be a `Double`.
   *
   * A container, record or object variant is left out: its value may hold what a dynamic value
   * cannot, such as a shared object. `TransportPolicy` never buffers a union with a dynamic variant,
   * so its values always take this path.
   */
  private val jniFallbacks: IntArray by lazy {
    fallbacksWhere { index, later ->
      variantCodes.same(index, later) ||
        (variantCodes.isDynamicLeaf(index) && variantCodes.isDynamic(later))
    }
  }

  private inline fun fallbacksWhere(crossing: (index: Int, later: Int) -> Boolean): IntArray =
    IntArray(converters.size) { index ->
      if (converters[index] == null) {
        return@IntArray -1
      }
      (index + 1 until converters.size).firstOrNull { later ->
        converters[later] != null && crossing(index, later)
      } ?: -1
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
   * `URL("hello")` - the next variant that can take it gets it instead: see [fallbacks], and
   * [jniFallbacks] when [fromJni] says the value is a JNI bridge value rather than one on a buffer.
   */
  inline fun decode(index: Int, fromJni: Boolean, read: (TypeConverter<Any>?) -> Any?): T {
    var at = index
    while (true) {
      // Outside the try: an index with no variant is an error of its own, not a refused value.
      val converter = converterAt(at)
      try {
        return wrap(at, read(converter))
      } catch (e: Exception) {
        at = fallbackOf(at, fromJni).takeIf { it >= 0 } ?: throw e
      }
    }
  }

  fun fallbackOf(index: Int, fromJni: Boolean): Int =
    if (fromJni) jniFallbacks[index] else fallbacks[index]

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
