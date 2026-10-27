package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.converters.BoxedIntConverter
import io.github.expo.modules.v2.converters.FileConverter
import io.github.expo.modules.v2.converters.IntConverter
import io.github.expo.modules.v2.converters.StringConverter
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.modules.v2.types.isNullable

/**
 * The kind of JavaScript value a variant takes - what native code picks a variant by.
 *
 * MUST stay in sync with `UnionVariantKind` in `expo-modules-v2/unions/UnionSchema.h`.
 */
internal enum class UnionVariantKind {
  BOOLEAN,
  NUMBER,
  STRING,
  ARRAY,
  BINARY,

  /** A plain object: a `Map`, or a record. */
  OBJECT,
  SHARED_OBJECT,

  /** Any object at all: a `JavaScriptObject`. */
  ANY_OBJECT,

  /** Any value at all: `Any` or `JavaScriptValue`. */
  ANY,

  /** One string, the variant's tag: an `object` variant. */
  LITERAL;

  companion object {
    /** The kind a variant whose value converts with [converter] takes. */
    fun of(converter: TypeConverter<*>): UnionVariantKind? =
      when (converter.codes.head) {
        CppType.BOOLEAN, CppType.BOX_BOOLEAN -> BOOLEAN
        CppType.INT, CppType.LONG, CppType.FLOAT, CppType.DOUBLE,
        CppType.BOX_INT, CppType.BOX_LONG, CppType.BOX_FLOAT, CppType.BOX_DOUBLE,
          -> NUMBER
        CppType.STRING -> STRING
        CppType.LIST, CppType.DOUBLE_ARRAY, CppType.INT_ARRAY, CppType.LONG_ARRAY,
        CppType.FLOAT_ARRAY, CppType.BOOLEAN_ARRAY,
          -> ARRAY
        CppType.BYTE_ARRAY -> BINARY
        CppType.MAP, CppType.RECORD -> OBJECT
        CppType.SHARED_OBJECT -> SHARED_OBJECT
        CppType.JS_OBJECT -> ANY_OBJECT
        CppType.ANY, CppType.JS_VALUE -> ANY
        else -> null
      }
  }
}

internal class UnionType<T : Any>(
  val id: UnionId,
  val codec: UnionCodec<T>,
) {
  val schema: UnionSchema = codec.schema

  private class Resolved(
    /** The converter of each variant's value, or null for an `object` variant. */
    val converters: List<TypeConverter<Any>?>,
    /** The kind of JavaScript value each variant takes. */
    val kinds: List<UnionVariantKind>,
    /**
     * For each variant, the next one to try when converting its value throws, or -1: a later
     * variant of the same kind that crosses in the same bridge type, such as `String` after `URL`.
     */
    val fallbacks: IntArray,
  )

  /** Resolved on first use: a variant may hold a record that holds this union again. */
  private val resolved: Resolved by lazy {
    val converters = schema.variants.map { it.type?.anyConverter }
    val kinds = validate(schema, converters)
    Resolved(converters, kinds, fallbacksOf(converters, kinds))
  }

  fun converterAt(index: Int): TypeConverter<Any>? {
    val converters = resolved.converters
    if (index !in converters.indices) {
      noSuchVariant(schema.name, index)
    }
    return converters[index]
  }

  /**
   * The variant at [index] around the value [read] converts with its converter. Native code picked
   * [index] by the kind of the JavaScript value; when the variant's own conversion refuses it -
   * `URL("hello")` - the next variant that takes the same bridge value gets it instead.
   */
  inline fun decode(index: Int, read: (TypeConverter<Any>?) -> Any?): T {
    var at = index
    while (true) {
      val converter = converterAt(at)
      try {
        return wrap(at, read(converter))
      } catch (e: Exception) {
        at = fallbackOf(at).takeIf { it >= 0 } ?: throw e
      }
    }
  }

  fun fallbackOf(index: Int): Int = resolved.fallbacks[index]

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

  fun toData(): UnionSchemaData {
    val variants = schema.variants
    val kinds = resolved.kinds
    return UnionSchemaData(
      name = schema.name,
      discriminator = schema.discriminator,
      bufferSafe = schema.bufferSafe,
      variantKinds = IntArray(variants.size) { kinds[it].ordinal },
      variantTags = Array(variants.size) { variants[it].tag },
      variantTypes = marshallCodes(),
    )
  }

  /** Each variant's type codes, length-prefixed; an `object` variant's is a lone `UNIT`. */
  private fun marshallCodes(): IntArray {
    val perVariant = resolved.converters.map { it?.codes?.values ?: intArrayOf(CppType.UNIT.code) }
    val codes = IntArray(perVariant.sumOf { 1 + it.size })
    var at = 0
    perVariant.forEach { variantCodes ->
      codes[at++] = variantCodes.size
      variantCodes.forEach { codes[at++] = it }
    }
    return codes
  }

  private companion object {
    fun fallbacksOf(
      converters: List<TypeConverter<Any>?>,
      kinds: List<UnionVariantKind>,
    ): IntArray = IntArray(converters.size) { index ->
      val codes = converters[index]?.codes ?: return@IntArray -1
      (index + 1 until converters.size).firstOrNull { later ->
        kinds[later] == kinds[index] && converters[later]?.codes?.contentEquals(codes) == true
      } ?: -1
    }

    /**
     * The kind of each variant, after checking that JavaScript can pick every one. Within a kind,
     * the first variant declared that takes a value gets it, so a variant is never picked when one
     * declared before it takes everything it would. The compiler plugin reports the same mistakes
     * at compile time; this catches a codec it did not generate.
     */
    fun validate(
      schema: UnionSchema,
      converters: List<TypeConverter<Any>?>,
    ): List<UnionVariantKind> {
      val variants = schema.variants
      val kinds = variants.mapIndexed { index, variant ->
        val converter = converters[index] ?: return@mapIndexed UnionVariantKind.LITERAL
        require(!converter.isNullable) {
          "${schema.name}.${variant.name} cannot cross to JavaScript: a variant's value cannot be " +
            "nullable - make the whole union nullable instead"
        }
        UnionVariantKind.of(converter) ?: throw IllegalArgumentException(
          "${schema.name}.${variant.name} cannot cross to JavaScript: its value has no JavaScript " +
            "kind to tell it apart by",
        )
      }

      schema.discriminator?.let { discriminator ->
        validateTagged(schema, discriminator, kinds)
      }

      val shapes = variants.indices.map { VariantShape(variants[it], kinds[it], converters[it]) }
      for (later in shapes.indices) {
        for (earlier in 0 until later) {
          val reason = shapes[earlier].shadows(shapes[later], schema.discriminator != null) ?: continue
          throw IllegalArgumentException(
            "${schema.name} cannot cross to JavaScript: ${variants[later].name} is never picked - " +
              "${variants[earlier].name}, declared before it, $reason",
          )
        }
      }
      return kinds
    }

    fun validateTagged(
      schema: UnionSchema,
      discriminator: String,
      kinds: List<UnionVariantKind>,
    ) {
      kinds.forEachIndexed { index, kind ->
        if (kind != UnionVariantKind.OBJECT) {
          return@forEachIndexed
        }

        val variant = schema.variants[index]
        val recordType = (variant.type as? TypeDescriptor.Simple)
          ?.let { RecordRegistry.typeFor(it.javaClass) }
        requireNotNull(recordType) {
          "${schema.name}.${variant.name} cannot cross to JavaScript: a union with a discriminator " +
            "tells its objects apart by '$discriminator', so each must be a @Record"
        }
        require(recordType.schema.fields.none { it.name == discriminator }) {
          "${schema.name}.${variant.name} cannot cross to JavaScript: its field '$discriminator' " +
            "would collide with the union's discriminator"
        }
      }
    }
  }
}

/**
 * What one variant takes, for telling whether an earlier one leaves it anything. MUST stay in sync
 * with `selectUnionVariant` in `expo-modules-v2/unions/UnionDispatch.cpp`, which picks by the same
 * rules, and with the compiler plugin's `UnionCheckers`.
 */
private class VariantShape(
  val variant: UnionVariant,
  val kind: UnionVariantKind,
  val converter: TypeConverter<Any>?,
) {
  /** Strings and `object` variants share one kind of JavaScript value. */
  private val group: UnionVariantKind
    get() = if (kind == UnionVariantKind.LITERAL) UnionVariantKind.STRING else kind

  private val head: CppType? get() = converter?.codes?.head

  private val valueClass: Class<*>?
    get() = when (val type = variant.type) {
      is TypeDescriptor.Simple -> type.javaClass
      is TypeDescriptor.Parametrized -> type.javaClass
      else -> null
    }

  /** Why [later] is never picked with this variant declared before it, or null when it can be. */
  fun shadows(later: VariantShape, hasDiscriminator: Boolean): String? {
    if (group != later.group) {
      return null
    }

    val takesAll = when (kind) {
      UnionVariantKind.BOOLEAN, UnionVariantKind.ARRAY, UnionVariantKind.BINARY,
      UnionVariantKind.ANY_OBJECT, UnionVariantKind.ANY,
        -> true

      UnionVariantKind.LITERAL ->
        return "is the same string".takeIf { later.kind == kind && later.variant.tag == variant.tag }

      UnionVariantKind.STRING ->
        converter is StringConverter || converter is FileConverter

      UnionVariantKind.NUMBER -> when (head) {
        CppType.LONG, CppType.BOX_LONG ->
          if (later.isPlainInt || later.head == CppType.LONG || later.head == CppType.BOX_LONG) {
            return "takes every whole number it would"
          } else {
            false
          }

        // A plain Int takes every whole number in its range; an Int-backed enum only its entries.
        CppType.INT, CppType.BOX_INT ->
          isPlainInt && (later.head == CppType.INT || later.head == CppType.BOX_INT)
        else -> true
      }

      UnionVariantKind.OBJECT -> when {
        hasDiscriminator ->
          return "has the same tag '${variant.tag}'".takeIf { later.variant.tag == variant.tag }

        head == CppType.MAP -> true
        later.head == CppType.MAP -> false
        else -> requiredFields().let { mine ->
          return "takes every object it would: it needs only $mine".takeIf {
            later.requiredFields().containsAll(mine)
          }
        }
      }

      UnionVariantKind.SHARED_OBJECT -> {
        val mine = valueClass ?: return null
        val theirs = later.valueClass ?: return null
        mine.isAssignableFrom(theirs)
      }
    }

    return when {
      takesAll -> "takes every value it would"
      // The same type: whatever one refuses, so does the other.
      valueClass != null && valueClass == later.valueClass -> "has the same type"
      else -> null
    }
  }

  private val isPlainInt: Boolean
    get() = converter is IntConverter || converter is BoxedIntConverter

  /** The fields of a record variant that an object must have to be taken. */
  private fun requiredFields(): Set<String> {
    val record = valueClass?.let { RecordRegistry.typeFor(it) } ?: return emptySet()
    return record.schema.fields
      .filter { !it.isOptional && !it.type.isNullable }
      .mapTo(sortedSetOf()) { it.name }
  }
}
